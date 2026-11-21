"""Small neural network averaged with CatBoost in the debit models, and its binary format read by
the app (``NeuralNetworkModel.kt``).

CatBoost and a network trained on the same features make different errors: averaging their
probabilities lowers the walk-forward RPS by 1 to 2 % on every test period (study in
``docs/debit-prediction-model.md``, « LSTM pour l'état hydrologique »).

Network: every input standardised on the training rows and clipped to ±6, a missing value (NaN or
``F.MISSING``) set to 0 plus a missing flag for the columns that have missing values in training;
two hidden layers (256 and 128 units, GELU in its tanh form, dropout 0.2); softmax over the levels.
Several seeds ("members") are trained on the same rows and their probabilities averaged. Training
runs a fixed number of epochs (one-cycle learning rate), so the walk-forward evaluates exactly the
procedure of the final fit.

Layout (little endian):

    magic        4 bytes  b"DCMN"
    version      int32    1
    features     int32    number of input features (feature_spec.json order)
    classes      int32    number of classes (feature_spec.json "labels" order)
    members      int32
    layers       int32    number L of linear layers
    widths       int32 * (L + 1)          widths[0] = features + flags, widths[L] = classes
    missingAt    float32                  a value <= missingAt (or NaN) is missing
    mean         float32 * features
    scale        float32 * features
    flags        int32                    number of missing flags
    flagFeature  int32 * flags            input feature of each flag, in increasing order
    then, member by member and layer by layer:
    weight       float32 * widths[l + 1] * widths[l]   row-major (output x input)
    bias         float32 * widths[l + 1]

Input of a row: z_i = clip((x_i - mean_i) / scale_i, -6, 6), 0 when x_i is missing, followed by the
flags (1 when the flagged feature is missing). Hidden layers apply gelu(h) =
0.5 h (1 + tanh(sqrt(2 / pi) (h + 0.044715 h^3))); the last one gives the logits.
"""
from __future__ import annotations

import math
import struct
import time
import warnings
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

import features as F

MAGIC = b"DCMN"
VERSION = 1
HIDDEN = (256, 128)
DROPOUT = 0.2
LEARNING_RATE = 2e-3
WEIGHT_DECAY = 1e-4
EPOCHS = 16
BATCH = 256
SEEDS = (0, 1, 2)
CLIP = 6.0
MISSING_AT = F.MISSING + 1
# Share of the network in the probabilities averaged with CatBoost (validation optimum 0.4-0.6, flat).
WEIGHT = 0.5


@dataclass
class Network:
    mean: np.ndarray  # float32 (features,)
    scale: np.ndarray  # float32 (features,)
    flags: np.ndarray  # int32 (flags,)
    # members[m][l] = (weight float32 (out, in), bias float32 (out,))
    members: list[list[tuple[np.ndarray, np.ndarray]]] = field(default_factory=list)
    max_diff_vs_torch: float = 0.0

    @property
    def feature_count(self) -> int:
        return len(self.mean)

    @property
    def class_count(self) -> int:
        return self.members[0][-1][1].shape[0]

    @property
    def widths(self) -> list[int]:
        layers = self.members[0]
        return [layers[0][0].shape[1]] + [bias.shape[0] for _, bias in layers]

    def encode(self, matrix: np.ndarray) -> np.ndarray:
        x = np.asarray(matrix, dtype=np.float64)
        missing = np.isnan(x) | (x <= MISSING_AT)
        z = np.clip((x - self.mean.astype(np.float64)) / self.scale.astype(np.float64), -CLIP, CLIP)
        z = np.where(missing, 0.0, z)
        return np.concatenate([z, missing[:, self.flags].astype(np.float64)], axis=1)

    def predict_proba(self, matrix: np.ndarray) -> np.ndarray:
        """Reference implementation of the app's evaluator (float64)."""
        z = self.encode(matrix)
        total = np.zeros((z.shape[0], self.class_count))
        for layers in self.members:
            h = z
            for index, (weight, bias) in enumerate(layers):
                h = h @ weight.astype(np.float64).T + bias.astype(np.float64)
                if index < len(layers) - 1:
                    h = gelu(h)
            total += softmax(h)
        return total / len(self.members)

    def write(self, path: Path) -> None:
        widths = self.widths
        parts = [MAGIC, struct.pack("<5i", VERSION, self.feature_count, self.class_count, len(self.members), len(widths) - 1)]
        parts.append(struct.pack(f"<{len(widths)}i", *widths))
        parts.append(struct.pack("<f", MISSING_AT))
        parts.append(self.mean.astype("<f4").tobytes())
        parts.append(self.scale.astype("<f4").tobytes())
        parts.append(struct.pack("<i", len(self.flags)))
        parts.append(self.flags.astype("<i4").tobytes())
        for layers in self.members:
            for weight, bias in layers:
                parts.append(np.ascontiguousarray(weight, dtype="<f4").tobytes())
                parts.append(np.ascontiguousarray(bias, dtype="<f4").tobytes())
        Path(path).write_bytes(b"".join(parts))

    @staticmethod
    def read(path: Path) -> "Network":
        data = Path(path).read_bytes()
        if data[:4] != MAGIC:
            raise ValueError(f"{path}: not a DCMN file")
        offset = 4
        version, features, classes, member_count, layer_count = struct.unpack_from("<5i", data, offset)
        offset += 20
        if version != VERSION:
            raise ValueError(f"{path}: version {version}")
        widths = list(struct.unpack_from(f"<{layer_count + 1}i", data, offset))
        offset += 4 * (layer_count + 1)
        (missing_at,) = struct.unpack_from("<f", data, offset)
        offset += 4
        if not math.isclose(missing_at, MISSING_AT, rel_tol=1e-6):
            raise ValueError(f"{path}: missingAt {missing_at}")

        def floats(count: int) -> np.ndarray:
            nonlocal offset
            values = np.frombuffer(data, dtype="<f4", count=count, offset=offset).astype(np.float32)
            offset += 4 * count
            return values

        mean, scale = floats(features), floats(features)
        (flag_count,) = struct.unpack_from("<i", data, offset)
        offset += 4
        flags = np.frombuffer(data, dtype="<i4", count=flag_count, offset=offset).astype(np.int32)
        offset += 4 * flag_count
        members = []
        for _ in range(member_count):
            layers = []
            for l in range(layer_count):
                weight = floats(widths[l + 1] * widths[l]).reshape(widths[l + 1], widths[l])
                layers.append((weight, floats(widths[l + 1])))
            members.append(layers)
        if offset != len(data):
            raise ValueError(f"{path}: {len(data) - offset} trailing bytes")
        network = Network(mean, scale, flags, members)
        if widths[0] != features + flag_count or network.class_count != classes:
            raise ValueError(f"{path}: widths {widths} do not match {features} features, {flag_count} flags, {classes} classes")
        return network


def gelu(h: np.ndarray) -> np.ndarray:
    return 0.5 * h * (1.0 + np.tanh(math.sqrt(2.0 / math.pi) * (h + 0.044715 * h ** 3)))


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = np.exp(logits - logits.max(axis=1, keepdims=True))
    return shifted / shifted.sum(axis=1, keepdims=True)


def blend(tree_probabilities: np.ndarray, network_probabilities: np.ndarray, weight: float = WEIGHT) -> np.ndarray:
    return (1.0 - weight) * tree_probabilities + weight * network_probabilities


def encoder(matrix: np.ndarray) -> Network:
    """Standardisation and missing flags fitted on the training rows (no members yet)."""
    x = np.asarray(matrix, dtype=np.float64)
    x = np.where(np.isnan(x) | (x <= MISSING_AT), np.nan, x)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=RuntimeWarning)  # columns missing on every row
        mean = np.nanmean(x, axis=0)
        std = np.nanstd(x, axis=0)
    mean = np.where(np.isnan(mean), 0.0, mean)
    std = np.where(~(std > 1e-9), 1.0, std)
    flags = np.flatnonzero(np.isnan(x).any(axis=0)).astype(np.int32)
    return Network(mean.astype(np.float32), std.astype(np.float32), flags)


def train(matrix: np.ndarray, labels: np.ndarray, seeds=None, epochs: int | None = None, threads: int = 8, log=print) -> Network:
    """Fits one member per seed (default ``SEEDS``) on ``matrix`` (rows x features, F.MISSING for
    missing values)."""
    import torch
    from torch import nn

    seeds = SEEDS if seeds is None else seeds
    epochs = EPOCHS if epochs is None else epochs

    torch.set_num_threads(threads)
    network = encoder(matrix)
    x = torch.from_numpy(network.encode(matrix).astype(np.float32))
    y = torch.from_numpy(np.array(labels, dtype=np.int64))
    class_count = len(F.LEVELS)
    widths = [x.shape[1], *HIDDEN, class_count]
    check = x[: min(len(x), 2000)]
    for seed in seeds:
        started = time.time()
        torch.manual_seed(seed)
        rng = np.random.default_rng(seed)
        layers: list[nn.Module] = []
        for index in range(len(widths) - 1):
            layers.append(nn.Linear(widths[index], widths[index + 1]))
            if index < len(widths) - 2:
                layers += [nn.GELU(approximate="tanh"), nn.Dropout(DROPOUT)]
        model = nn.Sequential(*layers)
        optimizer = torch.optim.AdamW(model.parameters(), lr=LEARNING_RATE, weight_decay=WEIGHT_DECAY)
        steps = (len(x) + BATCH - 1) // BATCH
        scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=LEARNING_RATE, total_steps=epochs * steps, pct_start=0.1)
        for epoch in range(epochs):
            model.train()
            permutation = torch.from_numpy(rng.permutation(len(x)))
            total = 0.0
            for start in range(0, len(x), BATCH):
                rows = permutation[start:start + BATCH]
                loss = nn.functional.cross_entropy(model(x[rows]), y[rows])
                optimizer.zero_grad()
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                optimizer.step()
                scheduler.step()
                total += loss.item() * len(rows)
            if epoch == epochs - 1 or epoch % 4 == 3:
                log(f"network seed {seed} epoch {epoch + 1}/{epochs} loss {total / len(x):.4f} ({time.time() - started:.0f} s)")
        model.eval()
        linear = [module for module in model if isinstance(module, nn.Linear)]
        network.members.append([(m.weight.detach().numpy().astype(np.float32).copy(), m.bias.detach().numpy().astype(np.float32).copy()) for m in linear])
        with torch.no_grad():
            reference = torch.softmax(model(check), dim=1).numpy()
        single = Network(network.mean, network.scale, network.flags, [network.members[-1]])
        mine = single.predict_proba(matrix[: len(check)])
        network.max_diff_vs_torch = max(network.max_diff_vs_torch, float(np.abs(mine - reference).max()))
    return network
