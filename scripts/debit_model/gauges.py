"""Measured river discharge near the canyon (Hub'Eau, France) as nowcast features.

For each canyon, gauged sites within 30 km (``fetch_hubeau.py``). For a target day J the app knows
the daily mean discharge up to J-1 (computed from real-time data), so features use J-1 and before:

* z-score of log discharge at J-1 against the site's own monthly climatology;
* 3-day trend (log Q(J-1) - log Q(J-3)) and the 3-day maximum z-score;
* distance and basin size of the site, so the model can weight relevant gauges.
"""
from __future__ import annotations

import json
import math
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

import features as F

MAX_SITES = 3
MIN_YEARS = 3.0
GAUGE_FEATURES = [
    "g1Z", "g1ZAll", "g1Trend3", "g1MaxZ3", "g1DistKm", "g1LogAreaKm2",
    "g2Z", "g2Trend3", "g2DistKm", "g2LogAreaKm2",
    "gMedZ", "gMaxZ", "gMeanTrend3", "gCount",
]


class SiteSeries:
    def __init__(self, dates: list[str], q: list[float]):
        days = np.array([F.to_epoch_day(date.fromisoformat(d)) for d in dates], dtype=np.int64)
        self.start = int(days.min())
        values = np.full(int(days.max()) - self.start + 1, np.nan)
        values[days - self.start] = np.log1p(np.maximum(np.array(q, dtype=float), 0.0) / 1000.0)  # m3/s
        self.log_q = values
        months = np.array([F.from_epoch_day(self.start + i).month for i in range(len(values))])
        valid = ~np.isnan(values)
        self.years = valid.sum() / 365.25
        self.month_mean = np.full(13, np.nan)
        self.month_std = np.full(13, np.nan)
        for month in range(1, 13):
            selected = values[valid & (months == month)]
            if len(selected) > 60:
                self.month_mean[month] = selected.mean()
                self.month_std[month] = max(selected.std(), 0.05)
        self.all_mean = float(values[valid].mean()) if valid.any() else math.nan
        self.all_std = float(max(values[valid].std(), 0.05)) if valid.any() else math.nan

    def value(self, day: int) -> float:
        index = day - self.start
        if 0 <= index < len(self.log_q):
            return float(self.log_q[index])
        return math.nan


def load_sites(directory: Path) -> tuple[dict[str, SiteSeries], dict[str, list[dict]]]:
    canyon_sites = json.loads((directory / "canyon_sites.json").read_text())
    series: dict[str, SiteSeries] = {}
    for path in (directory / "series").glob("*.json"):
        payload = json.loads(path.read_text())
        if len(payload["dates"]) < MIN_YEARS * 365:
            continue
        site = SiteSeries(payload["dates"], payload["q"])
        if site.years >= MIN_YEARS and not math.isnan(site.all_mean):
            series[payload["code_site"]] = site
    return series, canyon_sites


def features_for(canyon_id: int, target_day: int, series: dict[str, SiteSeries], canyon_sites: dict[str, list[dict]]) -> dict[str, float | None]:
    month = F.from_epoch_day(target_day - 1).month
    found = []
    for site in canyon_sites.get(str(canyon_id), []):
        data = series.get(site["code_site"])
        if data is None or math.isnan(data.month_mean[month]):
            continue
        q1 = data.value(target_day - 1)
        if math.isnan(q1):
            continue
        q3 = data.value(target_day - 3)
        z = [(data.value(target_day - k) - data.month_mean[month]) / data.month_std[month] for k in (1, 2, 3)]
        z = [v for v in z if not math.isnan(v)]
        area = site.get("surfaceBvKm2")
        found.append({
            "z": (q1 - data.month_mean[month]) / data.month_std[month],
            "zAll": (q1 - data.all_mean) / data.all_std,
            "trend": q1 - q3 if not math.isnan(q3) else math.nan,
            "maxZ": max(z),
            "dist": site["distanceKm"],
            "logArea": math.log1p(area) if area else math.nan,
        })
        if len(found) >= MAX_SITES:
            break
    out: dict[str, float | None] = {name: None for name in GAUGE_FEATURES}
    out["gCount"] = float(len(found))
    if found:
        first = found[0]
        out.update({"g1Z": first["z"], "g1ZAll": first["zAll"], "g1Trend3": first["trend"], "g1MaxZ3": first["maxZ"], "g1DistKm": first["dist"], "g1LogAreaKm2": first["logArea"]})
        if len(found) > 1:
            second = found[1]
            out.update({"g2Z": second["z"], "g2Trend3": second["trend"], "g2DistKm": second["dist"], "g2LogAreaKm2": second["logArea"]})
        zs = [f["z"] for f in found]
        trends = [f["trend"] for f in found if not math.isnan(f["trend"])]
        out["gMedZ"] = float(np.median(zs))
        out["gMaxZ"] = float(max(zs))
        out["gMeanTrend3"] = float(np.mean(trends)) if trends else None
    return {k: (None if v is None or (isinstance(v, float) and math.isnan(v)) else v) for k, v in out.items()}


def build_frame(base: pd.DataFrame, directory: Path) -> pd.DataFrame:
    series, canyon_sites = load_sites(directory)
    rows = [features_for(int(c), int(d), series, canyon_sites) for c, d in zip(base["canyonId"], base["day"])]
    return pd.DataFrame(rows, index=base.index).astype(float)
