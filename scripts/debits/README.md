# Debit Modelling Pipeline

This pipeline prepares a hydrology-safe training dataset from descente-canyon.com debit reports.

It assumes every observation is attached to the local day at `08:00`.

## Outputs

The pipeline is split into these stages:

1. `build_observation_dataset.py`
   - scrapes canyon debit pages
   - reuses a persistent HTML cache in `build/debit-pipeline/cache/debit-html`
   - automatically reuses an older cache in `<output-dir>/html-cache` if it already contains more files
   - parses one observation event per debit row
   - records in `authorProfiles`, for each author who wrote a remark, whether they have a site account, their forum id
     (avatar file name) and the site counters; `scripts/app/extract_forum_users.py` builds the app's members from it
   - applies conservative quality filtering
   - excludes known false debit posts via `scripts/debits/observation_overrides.json`

2. `plan_weather_windows.py`
   - selects one weather target per canyon
   - uses watershed bbox center when available
   - falls back to entry / upstream parking / other geo points
   - plans `J-7 08:00 -> J 08:00` observation windows for feature extraction
   - builds one historical fetch window per target by default with `--fetch-strategy history_daily`

3. `fetch_open_meteo_archive.py`
   - fetches daily historical weather from Open-Meteo archive
   - batches multiple targets into one Open-Meteo request with `--max-batch-targets`
   - resumes from `weather_window_manifest.jsonl` and `raw-json/` on rerun
   - writes flattened daily rows incrementally to avoid losing progress
   - retries rate limits / temporary API failures with exponential backoff

4. `build_training_features.py`
     - joins valid observations with daily weather cache
     - computes extended daily hydrology features for modelling
     - adds derived hydrology and prior-confidence features used by the model/runtime
     - adds temporal priors by canyon / massif / region using past observations only
     - adds heuristic historical flags for regulated and snowmelt-sensitive canyons
     - adds watershed shape and response-proxy features derived from basin geometry and canyon relief
     - defaults to the full `weather-archive-v21` daily cache and `batch-run-world` watershed descriptors when present

5. `export_runtime_lookups.py`
   - reads `training_features.jsonl`
   - rebuilds the final historical priors snapshot used by the model
   - exports per-canyon runtime lookup values for Android embedding

6. `train_baseline_model.py`
      - supports `random_forest` and `catboost`
      - defaults to `3` classes: `LOW`, `MEDIUM`, `HIGH`
      - uses temporal `train / calibration / test` splits for calibrated probability outputs
      - reports `HIGH` threshold policies for balanced and prudent operating modes
      - reports feature coverage and drops all-missing / constant features unless requested otherwise

7. `export_mobile_embedded_model.py`
   - trains an embedded `random_forest` model using the active feature set retained by coverage audit
   - exports `modele_statistique/model.onnx`
   - exports `feature_spec.json`, `canyon_static_features.json`, `runtime_feature_lookups.json` and `thresholds.json`

7b. `export_mobile_ordinal_model.py`
   - trains a six-level CatBoost ordinal classifier candidate
   - exports an ONNX model with raw labels `SEC`, `FILET`, `CORRECT`, `GROS`, `TRES_GROS`, `CRUE`
   - writes LOW/MEDIUM/HIGH ordinal score thresholds for app-side routing
   - targets candidate validation before replacing `modele_statistique/`

8. `evaluate_model_reliability.py`
   - compares temporal and cold-canyon validation splits
   - runs feature ablations to quantify historical priors vs weather vs physical descriptors
    - tests a canyon-history dropout variant for better cold-start robustness
    - reports HIGH threshold policies, calibration, history-bucket metrics and high-confidence errors

9. `train_ordinal_model.py`
   - trains an ordinal regressor on the six ordered debit levels
   - calibrates LOW/MEDIUM/HIGH thresholds on the calibration split
   - supports `extra_trees`, `random_forest`, `hist_gradient_boosting`, `catboost` and `catboost_classifier`
   - currently useful as the stronger teacher/candidate model before mobile export/distillation

10. `build_canyon_day_dataset.py`
   - aggregates raw observation rows into one consensus target per canyon/day
   - drops consensus ties and LOW/HIGH contradictions by default
   - adds `sampleWeight` to reduce domination by heavily reported canyons

11. `dump_opencanyon_reports.py` and `prepare_opencanyon_observations.py`
    - dump public OpenCanyon reports with all statuses and imported reports included
    - exclude reports imported from descente-canyon.com while retaining imports from other sources
    - map conservative exact-name/region matches to local canyon ids for candidate enrichment

## Example

```bash
python scripts/debits/build_observation_dataset.py --all --workers 6 --output-dir build/debit-pipeline/observations
python scripts/debits/plan_weather_windows.py --output-dir build/debit-pipeline/weather-planning --fetch-strategy history_daily
python scripts/debits/fetch_open_meteo_archive.py --output-dir build/debit-pipeline/weather-archive --workers 1 --max-batch-targets 25 --request-delay-ms 5000
python scripts/debits/build_training_features.py --output-dir build/debit-pipeline/training-features
python scripts/debits/export_runtime_lookups.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/runtime-lookups
python scripts/debits/train_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --model random_forest --calibration-method sigmoid
python scripts/debits/train_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-catboost-v23 --model catboost --calibration-method sigmoid
python scripts/debits/train_baseline_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-catboost-derived-current --model catboost --calibration-method sigmoid
python scripts/debits/evaluate_model_reliability.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-reliability
python scripts/debits/train_ordinal_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-ordinal-hgb --model hist_gradient_boosting --n-estimators 420 --learning-rate 0.035 --max-depth 10 --max-leaf-nodes 63 --min-samples-leaf 20 --canyon-history-dropout-rate 0.15 --no-class-balanced-weights
python scripts/debits/train_ordinal_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/model-ordinal-catboost --model catboost_classifier --n-estimators 900 --max-depth 8 --learning-rate 0.035 --l2-leaf-reg 8 --canyon-history-dropout-rate 0.15 --no-class-balanced-weights
python scripts/debits/build_canyon_day_dataset.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/training-features-canyon-day
python scripts/debits/dump_opencanyon_reports.py --output-dir build/opencanyon/reports
python scripts/debits/prepare_opencanyon_observations.py --reports-path build/opencanyon/reports/opencanyon_reports.jsonl --output-dir build/opencanyon/prepared-debit-observations
python scripts/debits/merge_observation_sources.py --output-dir build/debit-pipeline/observations-merged
python scripts/debits/export_mobile_embedded_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir modele_statistique --default-policy balanced --canyon-history-dropout-rate 0.15
python scripts/debits/export_mobile_ordinal_model.py --features-path build/debit-pipeline/training-features/training_features.jsonl --output-dir build/debit-pipeline/mobile-ordinal-catboost-candidate --iterations 900 --depth 8 --learning-rate 0.035 --l2-leaf-reg 8 --canyon-history-dropout-rate 0.15 --default-policy balanced
```

To force a fresh debit download, add:

```bash
python scripts/debits/build_observation_dataset.py --all --refresh-html-cache
```

If you only changed the filtering rules and want to relabel an existing dataset without re-reading all cached HTML:

```bash
python scripts/debits/build_observation_dataset.py --all \
  --reuse-observations-path build/debit-pipeline/observations/all_debit_observations.jsonl \
  --output-dir build/debit-pipeline/observations
```

If the weather archive fetch is interrupted, rerun the exact same command. Already completed target histories are skipped automatically.

For the CatBoost variant, install:

```bash
python -m pip install catboost
```

For the embedded mobile export, install:

```bash
python -m pip install numpy scikit-learn skl2onnx onnx
```

## Quality Filter

The filter is intentionally precision-first:

- `valid`
  - descended observations
  - non-descended observations with hydrology-specific comments

- `invalid`
  - explicit info-only posts unrelated to flow
  - route / access / logistics / training announcements
  - manual overrides for known bad examples

- `uncertain`
  - non-descended observations without enough signal
  - rows with an unparsed date

Keep `invalid` and `uncertain` rows for manual review. Only `valid` rows should feed the training set.

## Notes

- The weather target logic mirrors the app: watershed center first, then canyon geo points.
- The historical weather fetch defaults to Open-Meteo `era5` daily data for temporal consistency and fewer API calls.
- The generated JSONL files are easy to import into DuckDB or Parquet conversion jobs on the server.
