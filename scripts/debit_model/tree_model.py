"""Compact binary format of the CatBoost debit models, read by the app (``ObliviousTreeModel.kt``).

CatBoost grows oblivious trees: every level of a tree tests the same (feature, border) pair, so a
tree of depth d is d splits plus 2^d leaf vectors, and the leaf index is the d-bit number made of
the split outcomes (bit j = ``feature > border`` of split j). The app evaluates that directly.

Layout (little endian):

    magic       4 bytes  b"DCOT"
    version     int32    1
    features    int32    number of input features (feature_spec.json order)
    classes     int32    number of classes (feature_spec.json "labels" order)
    trees       int32
    scale       float64
    bias        float64 * classes
    depths      uint8   * trees
    splitFeat   int16   * sum(depths)       input column of each split
    splitBorder float32 * sum(depths)
    leaves      float32 * sum(2^depth) * classes

Probabilities are softmax(scale * sum(leaves) + bias), like ``CatBoostClassifier.predict_proba``.

    python scripts/debit_model/tree_model.py export --final-dir build/debit-model/final-v6 --output-dir modele_statistique
"""
from __future__ import annotations

import argparse
import json
import struct
import tempfile
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MAGIC = b"DCOT"
VERSION = 1


@dataclass
class TreeModel:
    feature_count: int
    class_count: int
    scale: float
    bias: np.ndarray
    depths: np.ndarray
    split_feature: np.ndarray
    split_border: np.ndarray
    leaves: np.ndarray

    def raw(self, matrix: np.ndarray) -> np.ndarray:
        x = np.asarray(matrix, dtype=np.float32)
        result = np.zeros((x.shape[0], self.class_count))
        split = 0
        leaf = 0
        for depth in self.depths.tolist():
            index = np.zeros(x.shape[0], dtype=np.int64)
            for j in range(depth):
                index |= (x[:, self.split_feature[split + j]] > self.split_border[split + j]).astype(np.int64) << j
            block = self.leaves[leaf: leaf + (1 << depth) * self.class_count].reshape(1 << depth, self.class_count)
            result += block[index].astype(np.float64)
            split += depth
            leaf += (1 << depth) * self.class_count
        return result * self.scale + self.bias

    def predict_proba(self, matrix: np.ndarray) -> np.ndarray:
        raw = self.raw(matrix)
        raw -= raw.max(axis=1, keepdims=True)
        exp = np.exp(raw)
        return exp / exp.sum(axis=1, keepdims=True)


def from_catboost(model, feature_names: list[str], labels: list[str]) -> TreeModel:
    """Converts a fitted CatBoostClassifier (float features only) to the app format."""
    if list(model.feature_names_) != list(feature_names):
        raise ValueError("model features differ from the exported feature order")
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "model.json"
        model.save_model(str(path), format="json")
        payload = json.loads(path.read_text())
    info = payload["features_info"]
    if info.get("categorical_features") or info.get("text_features") or info.get("embedding_features"):
        raise ValueError("only float features are supported")
    column_of = {f["feature_index"]: f["flat_feature_index"] for f in info["float_features"]}
    classes = [str(c) for c in model.classes_]
    order = [classes.index(label) for label in labels]
    trees = payload["oblivious_trees"]
    depths, split_feature, split_border, leaves = [], [], [], []
    for tree in trees:
        splits = tree["splits"]
        if any(s.get("split_type", "FloatFeature") != "FloatFeature" for s in splits):
            raise ValueError("only float feature splits are supported")
        depths.append(len(splits))
        split_feature.extend(column_of[s["float_feature_index"]] for s in splits)
        split_border.extend(s["border"] for s in splits)
        values = np.asarray(tree["leaf_values"], dtype=np.float64).reshape(1 << len(splits), len(classes))
        leaves.append(values[:, order].ravel())
    scale, bias = payload.get("scale_and_bias", [1.0, [0.0] * len(classes)])
    bias = np.asarray(bias if isinstance(bias, list) else [bias] * len(classes), dtype=np.float64)
    return TreeModel(
        feature_count=len(feature_names),
        class_count=len(labels),
        scale=float(scale),
        bias=bias[order],
        depths=np.asarray(depths, dtype=np.uint8),
        split_feature=np.asarray(split_feature, dtype=np.int16),
        split_border=np.asarray(split_border, dtype=np.float32),
        leaves=np.concatenate(leaves).astype(np.float32),
    )


def write(model: TreeModel, path: Path) -> None:
    header = MAGIC + struct.pack("<iiiid", VERSION, model.feature_count, model.class_count, len(model.depths), model.scale)
    with path.open("wb") as out:
        out.write(header)
        out.write(model.bias.astype("<f8").tobytes())
        out.write(model.depths.astype("u1").tobytes())
        out.write(model.split_feature.astype("<i2").tobytes())
        out.write(model.split_border.astype("<f4").tobytes())
        out.write(model.leaves.astype("<f4").tobytes())


def read(path: Path) -> TreeModel:
    data = path.read_bytes()
    if data[:4] != MAGIC:
        raise ValueError(f"{path}: not a DCOT model")
    version, features, classes, trees, scale = struct.unpack_from("<iiiid", data, 4)
    if version != VERSION:
        raise ValueError(f"{path}: unsupported version {version}")
    offset = 4 + struct.calcsize("<iiiid")

    def take(dtype: str, count: int) -> np.ndarray:
        nonlocal offset
        array = np.frombuffer(data, dtype=dtype, count=count, offset=offset)
        offset += array.nbytes
        return array

    bias = take("<f8", classes)
    depths = take("u1", trees)
    splits = int(depths.astype(np.int64).sum())
    model = TreeModel(
        feature_count=features,
        class_count=classes,
        scale=scale,
        bias=bias.astype(np.float64),
        depths=depths,
        split_feature=take("<i2", splits),
        split_border=take("<f4", splits),
        leaves=take("<f4", int((1 << depths.astype(np.int64)).sum()) * classes),
    )
    if offset != len(data):
        raise ValueError(f"{path}: {len(data) - offset} trailing bytes")
    return model


def export(model, feature_names: list[str], labels: list[str], path: Path, sample: np.ndarray) -> float:
    """Writes the model and returns the largest probability gap with CatBoost on ``sample``."""
    converted = from_catboost(model, feature_names, labels)
    write(converted, path)
    reread = read(path)
    order = [[str(c) for c in model.classes_].index(label) for label in labels]
    expected = model.predict_proba(sample)[:, order]
    gap = float(np.abs(reread.predict_proba(sample) - expected).max())
    if gap > 1e-5:
        raise SystemExit(f"{path.name}: tree model differs from CatBoost by {gap}")
    return gap


def main() -> None:
    import pandas as pd
    from catboost import CatBoostClassifier

    import features as F

    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    cmd = sub.add_parser("export", help="convert final CatBoost models to the app format")
    cmd.add_argument("--final-dir", required=True)
    cmd.add_argument("--output-dir", default="modele_statistique")
    args = parser.parse_args()

    final_dir, output_dir = Path(args.final_dir), Path(args.output_dir)
    for name, file_name in (("today", "model.trees"), ("horizon", "model_horizon.trees")):
        model = CatBoostClassifier()
        model.load_model(str(final_dir / f"model_{name}.cbm"))
        sample = pd.read_pickle(final_dir / f"sample_rows_{name}.pkl")[F.FEATURE_NAMES].to_numpy(dtype=np.float64)
        sample = np.where(np.isnan(sample), F.MISSING, sample).astype(np.float32)
        gap = export(model, F.FEATURE_NAMES, F.LEVELS, output_dir / file_name, sample)
        print(f"{file_name}: {(output_dir / file_name).stat().st_size / 1e6:.2f} MB, max gap vs CatBoost {gap:.2e}")


if __name__ == "__main__":
    main()
