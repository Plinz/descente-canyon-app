# Debit model v3 (hydrological state + ordinal CatBoost)

Production pipeline of the debit prediction embedded in the app (`modele_statistique/`).
Design notes and evaluation: `docs/debit-prediction-model.md`.

## Pipeline

Run from the repository root with the project virtualenv.

```bash
# 1. Observations: rescrape every canyon (manual overrides applied), new HTML cache directory
.venv/bin/python scripts/debits/build_observation_dataset.py --all \
  --output-dir build/debit-pipeline/descente-refresh-YYYY-MM-DD \
  --html-cache-dir build/debit-pipeline/cache/debit-html-YYYY-MM-DD --workers 3
#    then point DEFAULT_OBSERVATIONS / DEFAULT_HISTORY_OBSERVATIONS of build_dataset.py to it.
#    Canyons that became forbidden publish no report any more: their previous reports are restored
#    from DEFAULT_ARCHIVED_* (keep the previous scrape).

# 2. Weather store: ERA5 daily (precipitation, mean temperature) per weather target.
.venv/bin/python scripts/debit_model/weather_store.py \
  --output build/debit-model/weather/era5_daily_base.npz            # converts the legacy archive cache
.venv/bin/python scripts/debit_model/fetch_era5_gap.py \
  --targets-path build/debit-pipeline/weather-planning/weather_targets.jsonl \
  --target-ids-path <ids.txt> --start <YYYY-MM-DD> --end <YYYY-MM-DD> \
  --output-dir build/debit-pipeline/era5-gap-<name>                 # extends the record (resumable)
.venv/bin/python scripts/debit_model/weather_store.py \
  --base-store build/debit-model/weather/era5_daily_base.npz \
  --gap-dir build/debit-pipeline/era5-gap-<name> \
  --output build/debit-model/weather/era5_daily.npz

#    After a watershed change (room-import watersheds.json): weather targets at the new bbox
#    centres (the app's weather point), ERA5 series derived from the same ERA5 cell, then the
#    static source (catalog + watersheds + descriptors of the world and overlay packages).
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/update_weather_targets.py \
  --output-targets build/debit-pipeline/weather-planning-YYYY-MM-DD/weather_targets.jsonl \
  --output-store build/debit-model/weather/era5_daily-YYYY-MM-DD.npz
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/build_static_source.py \
  --output build/debit-model/source/canyon_static_descriptors-YYYY-MM-DD.json
#    then point DEFAULT_TARGETS / DEFAULT_WEATHER / DEFAULT_STATIC of build_dataset.py (and the
#    --targets / --weather defaults of export_app_assets.py) to them.

# 3. Real-time sources (open data, no key): Météo-France daily climatology and MeteoSwiss OGD
.venv/bin/python scripts/debit_model/fetch_meteofrance.py                      # RR-T-Vent, all departments
.venv/bin/python scripts/debit_model/fetch_meteoswiss.py                       # stations within 25 km

# 4. Dataset (time, static, weather/hydro, neighbour reports, rain gauges + lagged copies; ~3 min)
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/build_dataset.py

# 5. Honest walk-forward evaluation (J0 and J+1/J+2 models per test period, ~25 min per fold)
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/train.py walkforward --output-dir build/debit-model/walkforward-v4

# 6. Final models on all rows + decision thresholds from out-of-fold predictions
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/train.py final --walkforward-dir build/debit-model/walkforward-v4 --output-dir build/debit-model/final-v4

# 7. App assets (model.onnx, model_horizon.onnx, lookups...), ONNX parity check and golden vectors
PYTHONPATH=scripts/debit_model .venv/bin/python scripts/debit_model/export_app_assets.py --final-dir build/debit-model/final-v4 --walkforward-dir build/debit-model/walkforward-v4
bash gradlew :core:data:testDebugUnitTest --tests '*DebitFeatureBuilderGoldenTest*'
```

## Modules

| File | Role |
| --- | --- |
| `hydro.py` | Snow (5 elevation bands) + GR4J production store + linear reservoirs 1–90 days. |
| `features.py` | Feature contract (`FEATURE_NAMES`), static/hydro/history feature functions. |
| `history.py` | Causal canyon history and massif/region priors for training rows. |
| `build_dataset.py` | Builds `build/debit-model/dataset/`. |
| `update_weather_targets.py` | Moves weather targets to the current watershed bbox centres, ERA5 series from the same cell. |
| `build_static_source.py` | Static per-canyon source: catalog, watersheds, descriptors of the world and overlay packages. |
| `neighbors.py` | Recent reports of canyons within 30 km (site "derniers débits" day grid). |
| `meteofrance.py`, `meteoswiss.py` | Rain gauges within 25 km (Météo-France + MeteoSwiss networks). |
| `experiment.py`, `providers.py` | A/B harness for candidate sources and parameters (2 folds). |
| `gauges.py`, `snow.py`, `fetch_hubeau.py` | Tested and rejected sources (Hub'Eau discharge, snow depth). |
| `label_quality.py` | Message-only high-flow reports (fire closures, works...) excluded from labels/history. |
| `validate_recent.py` | Replays the exported app assets on reports published after training. |
| `evaluation.py` | Cumulative ordinal decision rule, threshold tuning, metrics. |
| `train.py` | Walk-forward evaluation and final fit. |
| `runtime_reference.py` | Single-prediction replica of the app path (golden vectors). |
| `export_app_assets.py` | Writes `modele_statistique/` and `core/data/src/test/resources/debit/golden_vectors.json`. |

## Contract with the app

`DebitHydroModel.kt` and `DebitFeatureBuilder.kt` are line-by-line ports of `hydro.py` and
`features.py`, and `DebitReportQuality.kt` of `label_quality.py`. Any change to a constant, a feature or the decision rule must be made on both sides
and the golden vectors regenerated; `DebitFeatureBuilderGoldenTest` fails otherwise.

Rules that keep training and runtime identical:

* weather is ERA5 (`models=era5`) for the past and ECMWF IFS 0.25° (`models=ecmwf_ifs025`) for the
  last days and the forecast — never Open-Meteo `best_match`, whose sharper convective peaks are
  outside the training distribution;
* every feature only uses reports dated strictly before the target day; massif/region priors in
  training are causal (never leave-one-out: it leaks the label);
* the hydrological model always runs on the 400-day window ending on the target day from the same
  initial state.
