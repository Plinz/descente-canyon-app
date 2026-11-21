"""Train one recurrent debit model on a walk-forward fold (see ``lstm_README.md``).

The network reads the daily weather of the canyon's weather target (log rain, temperature, valid
flag, day-of-year harmonics) together with an embedding of the static basin descriptors. Its hidden
state on the report day goes, with the tabular features of ``--mode``, through an MLP head to the
6 levels.

Training shares the recurrent pass between the reports of one canyon: rows are grouped in chunks
(canyon, block of ``--span`` days); a chunk starts ``--warm`` days before its block, so every report
sees between ``warm`` and ``warm + span`` days of weather. Blocks are shifted by a random offset at
every epoch, and aligned on epoch day 0 for validation, test and out-of-fold predictions.

Modes (tabular features of the head):
* ``static``: time + static descriptors only, no recurrent part (floor);
* ``weather``: time + static, weather through the recurrent part only;
* ``hydro``: ``weather`` + the hand-made hydro features (``F.WEATHER_FEATURES``);
* ``full``: every production feature except the hand-made hydro ones (history, neighbours, gauges);
* ``fullhydro``: every production feature, plus the recurrent part.

Same rows, validation year and threshold tuning as ``experiment.py``. Writes
``build/debit-model/lstm/runs/<tag>_<fold>_s<seed>.npz/.json``; with ``--oof K``, also
``oof_<tag>_<fold>.npz``: out-of-fold outputs on every row before the test period (K time blocks,
as many epochs as the main fit) and the main model's outputs on the test rows, for stacking.

    PYTHONPATH=scripts/debit_model:scripts/debit_model/research .venv/bin/python \
        scripts/debit_model/research/lstm_train.py --fold 2024 --mode weather --tag w64
"""
from __future__ import annotations

import argparse
import json
import math
import time

import numpy as np
import pandas as pd
import torch
from torch import nn

import evaluation as E
import features as F
import history as H
import lstm_data as D
import train as T

RUNS = D.CACHE / "runs"
HYDRO = list(F.WEATHER_FEATURES)
REALTIME = F.HISTORY_FEATURES + F.REALTIME_FEATURES
MODES = {
    "static": (False, F.TIME_FEATURES + F.STATIC_FEATURES),
    "weather": (True, F.TIME_FEATURES + F.STATIC_FEATURES),
    "hydro": (True, F.TIME_FEATURES + F.STATIC_FEATURES + HYDRO),
    "full": (True, F.TIME_FEATURES + F.STATIC_FEATURES + REALTIME),
    "fullhydro": (True, F.TIME_FEATURES + F.STATIC_FEATURES + HYDRO + REALTIME),
    # Controls without a recurrent pass: is a gain of the "full" networks due to the LSTM or to
    # an MLP being a different learner than CatBoost?
    "mlpfull": (False, F.TIME_FEATURES + F.STATIC_FEATURES + REALTIME),
    "mlpfullhydro": (False, F.TIME_FEATURES + F.STATIC_FEATURES + HYDRO + REALTIME),
}


class Tabular:
    """Standardises columns on the training rows; NaN/MISSING -> 0 plus a missing flag."""

    def __init__(self, frame: pd.DataFrame, names: list[str]):
        values = self._raw(frame, names)
        self.names = names
        self.mean = np.nanmean(values, axis=0)
        self.std = np.nanstd(values, axis=0)
        self.mean = np.where(np.isnan(self.mean), 0.0, self.mean)
        self.std = np.where(~(self.std > 1e-9), 1.0, self.std)
        self.flags = np.isnan(values).any(axis=0)

    @staticmethod
    def _raw(frame: pd.DataFrame, names: list[str]) -> np.ndarray:
        values = frame[names].to_numpy(dtype=np.float64)
        return np.where(values <= F.MISSING + 1, np.nan, values)

    def __call__(self, frame: pd.DataFrame) -> np.ndarray:
        values = self._raw(frame, self.names)
        missing = np.isnan(values)
        z = np.clip((values - self.mean) / self.std, -6, 6)
        z = np.where(missing, 0.0, z)
        return np.concatenate([z, missing[:, self.flags].astype(np.float64)], axis=1).astype(np.float32)


class PeriodicEmbedding(nn.Module):
    """PLR embeddings of numerical features (Gorishniy et al. 2022): sin/cos of learned
    frequencies, then a per-feature linear layer and ReLU."""

    def __init__(self, n_features: int, frequencies: int, dim: int, sigma: float):
        super().__init__()
        self.frequency = nn.Parameter(torch.randn(n_features, frequencies) * sigma)
        self.weight = nn.Parameter(torch.randn(n_features, 2 * frequencies, dim) / math.sqrt(2 * frequencies))
        self.bias = nn.Parameter(torch.zeros(n_features, dim))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        angle = 2 * math.pi * self.frequency[None] * x[..., None]
        features = torch.cat([torch.sin(angle), torch.cos(angle)], dim=-1)
        return torch.relu(torch.einsum("bnk,nkd->bnd", features, self.weight) + self.bias).flatten(1)


class Net(nn.Module):
    def __init__(self, n_dynamic: int, n_static: int, n_tab: int, args, use_rnn: bool, n_numeric: int = 0):
        super().__init__()
        self.use_rnn = use_rnn
        hidden = args.hidden
        self.n_numeric = n_numeric if args.num_embed == "plr" else 0
        self.embedding = PeriodicEmbedding(n_numeric, 16, args.plr_dim, args.plr_sigma) if self.n_numeric else None
        if self.embedding is not None:
            n_tab = n_tab - n_numeric + n_numeric * args.plr_dim
        if use_rnn:
            self.static_embedding = nn.Sequential(nn.Linear(n_static, args.static_dim), nn.Tanh())
            cell = {"lstm": nn.LSTM, "gru": nn.GRU}[args.cell]
            self.rnn = cell(n_dynamic + args.static_dim, hidden, num_layers=args.layers, batch_first=True, dropout=args.dropout if args.layers > 1 else 0.0)
        width = (hidden if use_rnn else 0) + n_tab
        self.drop = nn.Dropout(args.dropout)
        self.head = nn.Sequential(
            nn.Linear(width, args.head), nn.GELU(), nn.Dropout(args.dropout),
            nn.Linear(args.head, args.head // 2), nn.GELU(), nn.Dropout(args.dropout),
            nn.Linear(args.head // 2, 6),
        )

    def sequence(self, dynamic: torch.Tensor, static: torch.Tensor) -> torch.Tensor:
        embedding = self.static_embedding(static)
        x = torch.cat([dynamic, embedding[:, None, :].expand(-1, dynamic.shape[1], -1)], dim=2)
        output, _ = self.rnn(x)
        return output

    def forward(self, state: torch.Tensor, tab: torch.Tensor) -> torch.Tensor:
        if self.embedding is not None:
            tab = torch.cat([self.embedding(tab[:, :self.n_numeric]), tab[:, self.n_numeric:]], dim=1)
        z = torch.cat([self.drop(state), tab], dim=1) if self.use_rnn else tab
        return self.head(z)


def rps_loss(logits: torch.Tensor, y: torch.Tensor) -> torch.Tensor:
    cumulative = torch.cumsum(torch.softmax(logits, dim=1), dim=1)[:, :-1]
    truth = (torch.arange(5, device=y.device)[None, :] >= y[:, None]).float()
    return ((cumulative - truth) ** 2).sum(dim=1).mean() / 5


class Data:
    """Weather tensors shared by every part (train, validation, test, out-of-fold blocks).

    ``inputs``: ``raw`` (weather target series), ``hybrid`` (valid flag + daily states of the
    hand-made hydro model of the canyon) or ``both``.
    """

    def __init__(self, weather: dict, inputs: str = "raw"):
        series = torch.from_numpy(weather["series"].copy())
        mean = series[..., :2].reshape(-1, 2).mean(dim=0)
        std = series[..., :2].reshape(-1, 2).std(dim=0)
        series[..., :2] = (series[..., :2] - mean) / std
        days = torch.arange(series.shape[1]) + weather["day0"]
        angle = 2 * np.pi * (days % 365.25) / 365.25  # annual cycle (phase is irrelevant)
        self.calendar = torch.stack([torch.sin(angle), torch.cos(angle)], dim=1).float()
        self.series = series if inputs != "hybrid" else series[..., 2:]
        self.day0 = weather["day0"]
        self.target_of = weather["targetOf"]
        self.last = series.shape[1] - 1
        self.hydro = None
        self.canyon_row = {}
        if inputs != "raw":
            channels = D.hydro_channels(weather)
            hydro = torch.from_numpy(channels["series"])
            flat = hydro.reshape(-1, hydro.shape[2])
            hydro = (hydro - flat.mean(dim=0)) / flat.std(dim=0)
            self.hydro = hydro
            self.canyon_row = channels["rowOf"]

    @property
    def channels(self) -> int:
        return self.series.shape[2] + self.calendar.shape[1] + (self.hydro.shape[2] if self.hydro is not None else 0)


class Part:
    """Rows of one split, their tabular tensors and their chunks."""

    def __init__(self, frame: pd.DataFrame, data: Data, static_encoder: Tabular, tab_encoder: Tabular):
        self.frame = frame
        self.n = len(frame)
        self.static = torch.from_numpy(static_encoder(frame))
        self.tab = torch.from_numpy(tab_encoder(frame))
        self.y = torch.tensor(frame["rank"].to_numpy(), dtype=torch.long)
        self.offset = frame["day"].to_numpy() - data.day0
        self.target = np.array([data.target_of[c] for c in frame["canyonId"]])
        self.canyon = np.array([data.canyon_row.get(c, 0) for c in frame["canyonId"]])
        cold = frame["_cold"].to_numpy() if "_cold" in frame else np.zeros(self.n, dtype=bool)
        self.group_key = frame["canyonId"].to_numpy().astype(np.int64) * 2 + cold.astype(np.int64)

    def chunks(self, span: int, warm: int, shift: int = 0) -> dict:
        block = (self.offset + shift) // span
        keys = pd.MultiIndex.from_arrays([self.group_key, block])
        codes, uniques = pd.factorize(keys)
        order = np.argsort(codes, kind="stable")
        counts = np.bincount(codes, minlength=len(uniques))
        pointer = np.concatenate([[0], np.cumsum(counts)])
        first = order[pointer[:-1]]
        start = block[first] * span - shift - warm
        return {
            "order": torch.from_numpy(order),
            "pointer": torch.from_numpy(pointer),
            "target": torch.from_numpy(self.target[first]),
            "canyon": torch.from_numpy(self.canyon[first]),
            "start": torch.from_numpy(start),
            "static_row": torch.from_numpy(first),
            "count": len(uniques),
        }


def gather(data: Data, part: Part, chunks: dict, index: torch.Tensor, length: int):
    days = (chunks["start"][index, None] + torch.arange(length)[None, :]).clamp(0, data.last)
    pieces = [data.series[chunks["target"][index, None], days], data.calendar[days]]
    if data.hydro is not None:
        pieces.append(data.hydro[chunks["canyon"][index, None], days])
    dynamic = torch.cat(pieces, dim=2)
    static = part.static[chunks["static_row"][index]]
    counts = chunks["pointer"][index + 1] - chunks["pointer"][index]
    batch_position = torch.repeat_interleave(torch.arange(len(index)), counts)
    offsets = torch.cumsum(counts, 0) - counts
    rows = chunks["order"][torch.arange(int(counts.sum())) - torch.repeat_interleave(offsets, counts) + torch.repeat_interleave(chunks["pointer"][index], counts)]
    positions = torch.from_numpy(part.offset)[rows] - chunks["start"][index][batch_position]
    return dynamic, static, rows, batch_position, positions


def fit(model: Net, data: Data, train: Part, args, epochs: int, validation: Part | None = None, log=print):
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    length = args.warm + args.span
    rng = np.random.default_rng(args.seed)
    plan = [train.chunks(args.span, args.warm, int(rng.integers(0, args.span))) for _ in range(epochs)]
    batches_per_epoch = [(c["count"] + args.batch - 1) // args.batch for c in plan]
    scheduler = torch.optim.lr_scheduler.OneCycleLR(optimizer, max_lr=args.lr, total_steps=sum(batches_per_epoch), pct_start=0.1)
    best = (float("inf"), -1, None)
    history = []
    bad = 0
    started = time.time()
    for epoch in range(epochs):
        chunks = plan[epoch]
        permutation = torch.randperm(chunks["count"])
        losses = []
        model.train()
        for start in range(0, chunks["count"], args.batch):
            index = permutation[start:start + args.batch]
            if model.use_rnn:
                dynamic, static, rows, batch_position, positions = gather(data, train, chunks, index, length)
                states = model.sequence(dynamic, static)[batch_position, positions]
            else:
                rows = chunks["order"][torch.cat([torch.arange(chunks["pointer"][i], chunks["pointer"][i + 1]) for i in index.tolist()])]
                states = None
            logits = model(states if states is not None else train.tab[rows][:, :0], train.tab[rows])
            y = train.y[rows]
            if args.loss == "ce":
                loss = nn.functional.cross_entropy(logits, y)
            elif args.loss == "rps":
                loss = rps_loss(logits, y)
            else:
                loss = nn.functional.cross_entropy(logits, y) + 10 * rps_loss(logits, y)
            optimizer.zero_grad()
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            losses.append(loss.item())
        entry = {"epoch": epoch, "loss": float(np.mean(losses)), "seconds": round(time.time() - started)}
        if validation is not None:
            p, _ = predict(model, data, validation, args)
            entry["valRps"] = E.ranked_probability_score(validation.y.numpy(), p)
            if entry["valRps"] < best[0] - 1e-6:
                best = (entry["valRps"], epoch, {k: v.clone() for k, v in model.state_dict().items()})
                bad = 0
            else:
                bad += 1
        history.append(entry)
        log(json.dumps(entry))
        if validation is not None and bad >= args.patience:
            break
    if validation is not None:
        model.load_state_dict(best[2])
    return best, history


def predict(model: Net, data: Data, part: Part, args) -> tuple[np.ndarray, np.ndarray]:
    model.eval()
    probabilities = np.zeros((part.n, 6))
    states = np.zeros((part.n, args.hidden if model.use_rnn else 0), dtype=np.float32)
    with torch.no_grad():
        if model.use_rnn:
            chunks = part.chunks(args.span, args.warm, 0)
            for start in range(0, chunks["count"], 128):
                index = torch.arange(start, min(start + 128, chunks["count"]))
                dynamic, static, rows, batch_position, positions = gather(data, part, chunks, index, args.warm + args.span)
                state = model.sequence(dynamic, static)[batch_position, positions]
                probabilities[rows.numpy()] = torch.softmax(model(state, part.tab[rows]), dim=1).numpy()
                states[rows.numpy()] = state.numpy()
        else:
            for start in range(0, part.n, 4096):
                rows = torch.arange(start, min(start + 4096, part.n))
                probabilities[rows.numpy()] = torch.softmax(model(part.tab[rows][:, :0], part.tab[rows]), dim=1).numpy()
    return probabilities, states


def time_blocks(days: np.ndarray, k: int) -> np.ndarray:
    """Block number (0..k-1) of each row: contiguous periods with about the same number of rows."""
    order = np.argsort(days, kind="stable")
    block = np.empty(len(days), dtype=np.int64)
    block[order] = np.minimum((np.arange(len(days)) * k) // len(days), k - 1)
    return block


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fold", required=True, choices=list(D.FOLDS))
    parser.add_argument("--mode", default="weather", choices=list(MODES))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--cell", default="lstm", choices=["lstm", "gru"])
    parser.add_argument("--inputs", default="raw", choices=["raw", "hybrid", "both"])
    parser.add_argument("--warm", type=int, default=365, help="days of weather before a chunk's block")
    parser.add_argument("--span", type=int, default=365, help="days of a chunk's block")
    parser.add_argument("--hidden", type=int, default=64)
    parser.add_argument("--layers", type=int, default=1)
    parser.add_argument("--static-dim", type=int, default=16)
    parser.add_argument("--head", type=int, default=256)
    parser.add_argument("--dropout", type=float, default=0.2)
    parser.add_argument("--num-embed", default="none", choices=["none", "plr"], help="embeddings of the numerical tabular features")
    parser.add_argument("--plr-dim", type=int, default=8)
    parser.add_argument("--plr-sigma", type=float, default=0.2)
    parser.add_argument("--lr", type=float, default=2e-3)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--batch", type=int, default=48, help="chunks per batch")
    parser.add_argument("--epochs", type=int, default=16)
    parser.add_argument("--patience", type=int, default=4)
    parser.add_argument("--loss", default="ce", choices=["ce", "rps", "mix"])
    parser.add_argument("--cold", action="store_true", help="add cold-canyon copies of 12 %% of the training rows")
    parser.add_argument("--oof", type=int, default=0, help="also write out-of-fold outputs (K time blocks)")
    parser.add_argument("--oof-only", action="store_true", help="reuse the main run of --tag/--seed, only compute out-of-fold outputs")
    parser.add_argument("--horizon", action="store_true", help="J+1/J+2 model: lagged real-time features (D.horizon), tested at J+1")
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--threads", type=int, default=8)
    parser.add_argument("--max-train", type=int, default=0, help="subsample training rows (quick tests)")
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    torch.set_num_threads(args.threads)
    started = time.time()
    use_rnn, names = MODES[args.mode]
    stem = RUNS / f"{args.tag}_{args.fold}_s{args.seed}"
    RUNS.mkdir(parents=True, exist_ok=True)

    frame = D.fold(D.day(D.FOLDS[args.fold][0]))
    data = Data(D.weather(frame["canyonId"].unique()), args.inputs)
    frame = frame[frame["canyonId"].isin(data.target_of.keys())]
    train, validation, test = D.split(frame, args.fold)
    if args.horizon:
        train, validation, test = D.horizon(train, validation, test, D.day(D.FOLDS[args.fold][0]))
        names = names + F.INFO_FEATURES
    if args.max_train:
        train = train.sample(n=min(args.max_train, len(train)), random_state=args.seed)

    def augmented(rows: pd.DataFrame, seed: int) -> pd.DataFrame:
        rows = rows.assign(_cold=False)
        if not args.cold:
            return rows
        sample = rows.sample(frac=T.COLD_AUGMENT_FRACTION, random_state=seed)
        return pd.concat([rows, H.cold_canyon(sample).assign(_cold=True)], ignore_index=True)

    train_rows = augmented(train, 13)
    static_encoder = Tabular(train_rows, F.STATIC_FEATURES)
    tab_encoder = Tabular(train_rows, names)
    parts = {name: Part(rows, data, static_encoder, tab_encoder) for name, rows in (("train", train_rows), ("validation", validation), ("test", test))}
    n_dynamic = data.channels

    def new_model() -> Net:
        return Net(n_dynamic, parts["train"].static.shape[1], parts["train"].tab.shape[1], args, use_rnn, len(names))

    if args.oof_only:
        previous = json.loads(stem.with_suffix(".json").read_text())
        with np.load(stem.with_suffix(".npz"), allow_pickle=True) as npz:
            test_p, test_state = npz["test_p"], npz["test_state"]
        best = (previous["valRps"], previous["bestEpoch"], None)
    else:
        model = new_model()
        print(json.dumps({"ready": round(time.time() - started), "train": parts["train"].n, "chunks": parts["train"].chunks(args.span, args.warm)["count"], "parameters": sum(p.numel() for p in model.parameters())}), flush=True)
        best, history = fit(model, data, parts["train"], args, args.epochs, parts["validation"], log=lambda line: print(line, flush=True))

        val_p, val_state = predict(model, data, parts["validation"], args)
        test_p, test_state = predict(model, data, parts["test"], args)
        y_val = parts["validation"].y.numpy()
        thresholds = E.tune_thresholds(y_val, val_p)
        y_test = parts["test"].y.numpy()
        metrics = {**E.level_metrics(y_test, E.decide(test_p, thresholds)), **E.probabilistic_metrics(y_test, test_p)}
        np.savez_compressed(
            stem.with_suffix(".npz"),
            val_p=val_p, test_p=test_p, val_state=val_state, test_state=test_state,
            val_obs=validation["observationId"].to_numpy(), test_obs=test["observationId"].to_numpy(), thresholds=thresholds,
        )
        report = {
            "args": vars(args), "bestEpoch": best[1], "valRps": best[0], "history": history,
            "test": metrics, "thresholds": thresholds.tolist(), "seconds": round(time.time() - started),
            "rows": {"train": parts["train"].n, "validation": len(y_val), "test": len(y_test)},
        }
        stem.with_suffix(".json").write_text(json.dumps(report, indent=1))
        print(json.dumps({"tag": args.tag, "fold": args.fold, "seed": args.seed, "valRps": round(best[0], 5), "testRps": round(metrics["rps"], 5), "accuracy": round(metrics["accuracy"], 4), "highF1": round(metrics["highF1"], 4), "seconds": report["seconds"]}), flush=True)

    if args.oof:
        # Out-of-fold outputs on every row before the test period, for a second-stage CatBoost.
        before = pd.concat([train, validation])
        blocks = time_blocks(before["day"].to_numpy(), args.oof)
        oof_p = np.zeros((len(before), 6))
        oof_state = np.zeros((len(before), args.hidden if use_rnn else 0), dtype=np.float32)
        for k in range(args.oof):
            inside = before[blocks != k]
            block_rows = augmented(inside, 13 + k)
            block_model = new_model()
            torch.manual_seed(args.seed * 100 + k)
            block_static, block_tab = static_encoder, tab_encoder  # same input layout as the main model
            fit(block_model, data, Part(block_rows, data, block_static, block_tab), args, best[1] + 1, None, log=lambda line, k=k: print(f"oof{k} {line}", flush=True))
            held = Part(before[blocks == k], data, block_static, block_tab)
            p, state = predict(block_model, data, held, args)
            oof_p[blocks == k] = p
            oof_state[blocks == k] = state
        np.savez_compressed(
            RUNS / f"oof_{args.tag}_{args.fold}.npz",
            obs=np.concatenate([before["observationId"].to_numpy(), test["observationId"].to_numpy()]),
            p=np.concatenate([oof_p, test_p]), state=np.concatenate([oof_state, test_state]),
        )
        print(json.dumps({"oof": args.oof, "rps": E.ranked_probability_score(before["rank"].to_numpy(), oof_p), "seconds": round(time.time() - started)}), flush=True)


if __name__ == "__main__":
    main()
