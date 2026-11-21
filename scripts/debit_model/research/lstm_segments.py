"""Where does a candidate gain or lose against a reference? RPS gap by segment of the test rows.

Segments: season, age of the canyon's last report, snow (share of the basin above 2000 m), karst,
basin size, observed level, and rain of the last 3 days. Candidates use the ``lstm_compare.py``
syntax (``&`` averages runs).

    PYTHONPATH=scripts/debit_model:scripts/debit_model/research .venv/bin/python \
        scripts/debit_model/research/lstm_segments.py --reference cb_full_s0 "cb_full+w64_s0"
"""
from __future__ import annotations

import argparse

import numpy as np
import pandas as pd

import features as F
import lstm_compare as C
import lstm_data as D


def per_row_rps(p: np.ndarray, y: np.ndarray) -> np.ndarray:
    c_pred = np.cumsum(p, axis=1)[:, :-1]
    c_true = (np.arange(5)[None, :] >= y[:, None]).astype(float)
    return np.sum((c_pred - c_true) ** 2, axis=1) / 5


def segments(test: pd.DataFrame) -> dict[str, pd.Series]:
    months = pd.Series([F.from_epoch_day(int(d)).month for d in test["day"]], index=test.index)
    season = months.map(lambda m: "winter" if m in (12, 1, 2) else "spring" if m in (3, 4, 5) else "summer" if m in (6, 7, 8) else "autumn")
    since = test["histDaysSinceLast"]
    age = pd.cut(since.where(since >= 0, 10**6), [-1, 7, 30, 365, 10**5, 10**7], labels=["0-7 d", "8-30 d", "31-365 d", "> 1 y", "never"])
    snow = pd.cut(test["fractionAbove2000m"].where(test["fractionAbove2000m"] > F.MISSING + 1), [-0.01, 0.01, 0.2, 1.01], labels=["none", "some", "> 20 %"])
    karst = test["karstIndicator"].map(lambda v: "karst" if v is not None and v > 0.5 else "other")
    area = pd.cut(test["areaKm2Log"].where(test["areaKm2Log"] > F.MISSING + 1), [-10, np.log(5), np.log(30), 20], labels=["< 5 km2", "5-30 km2", "> 30 km2"])
    level = test["rank"].map(lambda r: F.LEVELS[r])
    rain = pd.cut(test["wPrecip3d"].where(test["wPrecip3d"] > F.MISSING + 1), [-1, 5, 30, 10**4], labels=["< 5 mm", "5-30 mm", "> 30 mm"])
    return {"season": season, "last report": age, "snow": snow, "karst": karst, "area": area, "observed": level, "rain 3 d": rain}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("candidates", nargs="+")
    parser.add_argument("--reference", required=True)
    parser.add_argument("--folds", default="2024,2025-2026")
    args = parser.parse_args()
    for fold in args.folds.split(","):
        frame = D.fold(D.day(D.FOLDS[fold][0]))
        _, _, test = D.split(frame, fold)
        test = test.drop_duplicates("observationId").set_index("observationId", drop=False)
        reference = C.load(args.reference, fold)
        obs = reference["test_obs"]
        rows = test.loc[obs]
        y = rows["rank"].to_numpy()
        ref = per_row_rps(reference["test_p"], y)
        groups = segments(rows.reset_index(drop=True))
        for candidate in args.candidates:
            run = C.load(candidate, fold)
            index = pd.Index(run["test_obs"]).get_indexer(obs)
            gap = per_row_rps(run["test_p"][index], y) - ref
            print(f"\n== fold {fold}: {candidate} vs {args.reference} (RPS gap, negative = better; all rows {gap.mean():+.5f})")
            for name, labels in groups.items():
                cells = []
                for label in labels.dropna().unique().tolist():
                    mask = (labels == label).to_numpy()
                    cells.append(f"{label}: {gap[mask].mean():+.5f} (n={mask.sum()}, ref {ref[mask].mean():.4f})")
                print(f"  {name:12s} " + " | ".join(sorted(cells)))


if __name__ == "__main__":
    main()
