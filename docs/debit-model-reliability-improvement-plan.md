# Debit Model Reliability Improvement Plan

> **Historique — remplacé par le modèle v3** (`docs/debit-prediction-model.md`, `scripts/debit_model/`). Les métriques « récentes » ci-dessous étaient mesurées sur des observations incluses dans l'entraînement et ne sont pas comparables.

## Priority Objective

Improve practical reliability by reducing dangerous under-estimation, especially `GROS`, `TRES_GROS` and `CRUE` predicted too low.

## 1. Reliable Measurement

- Track future observations against predictions made before the observation date.
- Report ordinal MAE, percentage within +/-1 level, severe errors >= 2 levels, and recall for `GROS/TRES_GROS/CRUE`.
- Segment metrics by country, massif, canyon, season, weather context and canyon history depth.
- Keep a top-errors report for manual inspection.

## 2. Label Quality

The debit label itself remains the primary signal. A missing comment is not a quality problem: a debit selected without comment keeps normal weight.

The main risk is a label attached to a comment that is only informational and not hydrological, for example roadworks, closed access, canyon closure, parking, regulation, or equipment notes. This risk is most important for high levels because `TRES_GROS` or `CRUE` can be selected just to warn users, without describing real water flow.

Rules:

- Keep observations without comment at normal weight.
- Keep hydrological comments at normal weight, with a small bonus when the text agrees with the selected level.
- Do not penalize `non parcouru`; dangerous water is often reported precisely because the canyon was not descended.
- Keep `SEC`, `FILET` and `CORRECT` at normal weight by default, even with a non-hydrological comment.
- For `GROS`, `TRES_GROS` and `CRUE` with a purely non-hydrological comment, mark as suspected info-only only when the text contains an explicit information-only motif such as `canyon interdit`, `arrêté municipal`, `travaux`, `route fermée` or `accès interdit`.
- Do not use broad logistics words such as `route`, `parking`, `groupe`, `voiture`, `accès`, `stage` or `formation` as exclusion triggers by themselves.
- By default, do not exclude suspected high observations automatically; write them to a manual review file first.
- After manual validation, explicitly approved suspected high observations are centralized in `scripts/debits/observation_overrides.json`, the same place as the raw-pipeline manual exclusions.
- The canyon-day script applies `observation_overrides.json` and only generates new candidates for review; it should not become a second source of exclusions.
- If several same-day observations support a high level, keep the suspected row by default and audit it.
- Drop or audit strong same-day contradictions such as `SEC` and `CRUE` on the same canyon/day.

Outputs to generate:

- `non_hydrology_comment_observations.jsonl`
- `suspected_info_only_high_observations.jsonl`
- `candidate_excluded_comments.txt`
- `candidate_excluded_comments.jsonl`
- `skipped_canyon_days.json`
- `metadata.json`

The `candidate_excluded_comments.txt` file is intended for manual validation. Each entry starts with the selected debit level so questionable high-flow labels can be reviewed quickly.

### Canyon-Day Experiment Result

The `canyon-day` target was tested as a candidate replacement for the current observation-level model. Two variants were evaluated against the current embedded model.

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Recent HIGH F1 | Recent HIGH recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| Current observation-level model | `0.6803` | `0.7248` | `0.3696` | `0.5714` | `0.6537` |
| Canyon-day with sample weights | `0.6399` | `0.6928` | `0.3965` | `0.5298` | `0.6226` |
| Canyon-day without sample weights | `0.6758` | `0.7261` | `0.3786` | `0.5649` | `0.6693` |

Conclusion: do not replace `modele_statistique` with a canyon-day model for now. The no-sample-weight canyon-day variant slightly improves HIGH recall and severe `CRUE` errors, but it degrades ordinal MAE, accuracy and HIGH F1. The canyon-day dataset remains useful for label-quality analysis, not as the current production model target.

### Next Label Audit

The next highest-value label-quality work is to review high-flow observations that are already excluded by the raw observation pipeline:

- `invalid` rows with `GROS`, `TRES_GROS` or `CRUE`.
- `uncertain` rows with `GROS`, `TRES_GROS` or `CRUE`.

Manual review convention: add a leading space before the debit level in the generated text file to mark an excluded observation that should be re-integrated. Validated re-integrations should be centralized in `scripts/debits/observation_overrides.json` with `action: "valid"`; validated exclusions stay in the raw-pipeline exclusion set.

Review result from `build/debit-pipeline/excluded-high-flow-review/excluded_high_flow_review.txt`:

- Reviewed high-flow excluded rows: `1030`.
- Reintegrated with `action: "valid"`: `795`.
- Confirmed invalid with `action: "invalid"`: `140`.
- Left in their current raw-pipeline category: `95`.

After applying these overrides to the local scrape:

| Category | Before | After | High-flow before | High-flow after |
| --- | ---: | ---: | ---: | ---: |
| `valid` | `163841` | `164632` | `20637` | `21428` |
| `invalid` | `268` | `401` | `81` | `214` |
| `uncertain` | `2231` | `1307` | `949` | `25` |

This confirms that most high-flow rows previously excluded as `uncertain` were useful terrain-flow observations and should be part of the next model training dataset.

### Text Nuance Extraction

Comment text often contains finer-grained debit information than the selected six-level label, for example `DC-`, `DC+`, `DC/GD`, `GD-`, `TGD+`, or explicit phrases such as `debit correct +`, `gros debit -`, `trop d'eau`.

An extraction pass was added in `scripts/debits/enrich_text_target_signals.py`. It writes an enriched feature dataset and audit files under `build/debit-pipeline/training-features-through-2026-05-28-reviewed-text-signals`.

Extraction result on reviewed training features:

- Source rows: `164632`.
- Rows with any text target signal: `28889`.
- Rows with strict `PD/DC/GD/TGD` abbreviation signal: `6706`.
- Rows with phrase signal: `22183`.
- Strong agreement with selected level: `18828`.
- Near boundary: `2807`.
- Soft conflict: `5366`.
- Strong conflict: `1888`.

Top useful abbreviation signals:

| Signal | Count | Text rank | Interpretation |
| --- | ---: | ---: | --- |
| `DC--` | `172` | `1.65` | low `CORRECT`, close to `FILET` |
| `DC-` | `2274` | `1.82` | low `CORRECT` |
| `DC` | `1447` | `2.00` | `CORRECT` |
| `DC+` | `1997` | `2.22` | high `CORRECT` |
| `DC++` | `225` | `2.38` | close to `CORRECT/GROS` |
| `GD-` | `61` | `2.82` | low `GROS` |
| `GD` | `218` | `3.00` | `GROS` |
| `GD+` | `72` | `3.22` | high `GROS` |
| `TGD-` | `10` | `3.82` | low `TRES_GROS` |
| `TGD` | `66` | `4.00` | `TRES_GROS` |
| `TGD+` | `5` | `4.22` | high `TRES_GROS` |

Recommended use:

- Do not use comment text as a runtime feature, because future predictions do not have a user comment.
- Use extracted text only to improve training targets: `textTargetRank`, `textTargetLevel`, `textSelectedLevelDelta` and `textTargetAgreement`.
- Start by auditing `strong_conflict` rows before trusting automatic target correction.
- For modelling, test a conservative `softTargetRank` strategy only on high-confidence abbreviation signals first, not on broad phrase signals.

First app-compatible training experiments with high-confidence abbreviation adjustments:

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Current embedded model | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` |
| Current labels + abbreviation-adjusted rounded labels | `0.6817` | `0.7155` | `0.3745` | `0.0109` | `0.5482` | `0.6134` | `0.5789` |
| Reviewed labels + abbreviation-adjusted rounded labels | `0.3352` | `0.5638` | `1.0036` | `0.0457` | `0.1837` | `1.0000` | `0.3104` |

Conclusion: do not replace the embedded model with either abbreviation-adjusted classifier candidate. The conservative abbreviation adjustment slightly improves ordinal MAE on the current-label dataset but lowers HIGH recall and does not improve HIGH F1. Applying it on the reviewed high-flow dataset inherits the reviewed-label over-prediction problem. The next useful step is not rounded-label replacement, but a true ordinal/soft-rank training path or calibration path that can use `textTargetRank` without forcing class labels.

### Reviewed Labels With Causal History And Reduced Weight

The initial reviewed-label candidate over-predicted `HIGH` because the reintegrated high-flow rows were used at full strength and their generated feature rows used app-like current historical lookups. A cleaner candidate was built by recomputing causal history/prior features over the reviewed dataset while preserving existing weather/static features, then down-weighting the manually reintegrated high-flow rows.

Weather note: a full official reviewed weather rebuild was attempted via `plan_weather_windows.py` and `fetch_open_meteo_archive.py`, but Open-Meteo rejected long batched requests with many `400/429` errors. The causal-history rebuild fixes the main leakage issue for reintegrated rows without requiring a full weather refetch. A full weather rebuild should use one target per request and VPN/IP rotation if needed.

Recent reviewed evaluation (`2014` rows, `2026-03-26` to `2026-05-28`):

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Current embedded model | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` | `340` |
| Reviewed causal, reintegrated weight `0.50` | `0.6847` | `0.7307` | `0.3736` | `0.0094` | `0.5203` | `0.6654` | `0.5840` | `344` |
| Reviewed causal, reintegrated weight `0.25` | `0.6887` | `0.7332` | `0.3737` | `0.0104` | `0.5249` | `0.6654` | `0.5869` | `341` |
| Reviewed causal, reintegrated weight `0.10` | `0.6867` | `0.7289` | `0.3732` | `0.0099` | `0.5317` | `0.6543` | `0.5867` | `331` |

Conclusion: `Reviewed causal, reintegrated weight 0.25` is the first candidate that improves all main recent metrics without exploding `HIGH` predictions. It should be considered the current best replacement candidate, pending one final app export/build check or a full weather rebuild if VPN-based fetching is available.

### ERA5-Land Grid Weather V2 Result

ERA5-Land was implemented as an area-weighted weather grid over each watershed, with point fallback for canyons without watershed geometry. The weather fetch was changed to monthly grid-cell windows and completed with VPN/IP rotation.

Implementation notes:

- `build_weather_grid_cells.py` builds ERA5-Land cells intersecting watersheds.
- `plan_weather_grid_windows.py` plans monthly grid-cell windows.
- `aggregate_grid_weather_by_canyon.py` aggregates grid-cell weather back to canyon daily weather.
- Canyons without watershed geometry use point fallback from their best available geo point.
- New spatial weather features were added: max/p90 cell precipitation, spatial precipitation amplification, grid cell counts and coverage/availability fields.

Weather/data coverage:

- Reviewed ERA5-Land v2 feature rows: `164632`.
- Skipped observations: `0`.
- Watershed-grid rows: `162205`.
- Point-fallback rows: `2427`.
- ERA5-Land monthly grid-cell windows fetched: `134118/134118`.
- Fetch failures: `0`.

Recent evaluation (`2026-03-26` to `2026-05-28`):

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Current embedded model | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` | `340` |
| Reviewed causal, reintegrated weight `0.25` | `0.6887` | `0.7332` | `0.3737` | `0.0104` | `0.5249` | `0.6654` | `0.5869` | `341` |
| ERA5-Land grid v2 | `0.7185` | `0.7058` | `0.4037` | `0.0129` | `0.6017` | `0.5167` | `0.5560` | `231` |
| ERA5-Land grid v2, recalibrated 3-class thresholds | `0.7284` | `0.7327` | `0.4037` | `0.0129` | `0.5108` | `0.6171` | `0.5589` | `325` |

Calibration notes:

- Recalibrating `LOW/MEDIUM/HIGH` improves ERA5-Land v2 balanced accuracy and recall, but `HIGH F1` still stays below the current model and below the reviewed-causal candidate.
- Learned six-level cutpoints performed poorly in the first simple calibration pass: exact six-class accuracy dropped and ordinal MAE degraded. Do not use those cutpoints as-is.

Conclusion: do not replace `modele_statistique` with ERA5-Land grid v2 yet. ERA5-Land improves raw 3-class accuracy and HIGH precision before recalibration, but it still under-detects high-flow cases and has worse ordinal error. The best candidate remains `reviewed causal, reintegrated weight 0.25`.

### Current Production Candidate Adopted

The embedded `modele_statistique` artifact was first updated to reviewed labels with causal history priors and manually reintegrated high-flow rows weighted at `0.25`. It was then updated again to include runtime-supported seasonal and recent-history features, which became the new production baseline. Three-class thresholds were recalibrated; experimental six-level cutpoints were not adopted because they degraded ordinal metrics.

Final recent evaluation (`2014` reviewed rows, `2026-03-26` to `2026-05-28`):

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Previous embedded model | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` | `340` |
| Reviewed causal weight `0.25` | `0.7507` | `0.7542` | `0.3737` | `0.0104` | `0.5249` | `0.6654` | `0.5869` | `341` |
| Final embedded temporal-history model | `0.8148` | `0.7945` | `0.2876` | `0.0074` | `0.7467` | `0.6357` | `0.6867` | `229` |

This is the current production baseline to beat. Future work should compare against the final embedded temporal-history model, not the previous model.

### Seasonal And Recent History Candidate

The strongest experimental signal after the production baseline came from adding causal seasonal and recent-history features on top of `reviewed causal, reintegrated weight 0.25`.

New feature families tested:

- Canyon/month and canyon/season priors.
- Massif/month, massif/season, region/month, region/season priors.
- Recent high-flow priors over `30/90/365` days for canyon, massif and region.
- Canyon last observed rank and days since last observation.

Recent evaluation (`2014` reviewed rows, `2026-03-26` to `2026-05-28`):

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 | Predicted HIGH |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Final embedded model | `0.7507` | `0.7542` | `0.3737` | `0.0104` | `0.5249` | `0.6654` | `0.5869` | `341` |
| Seasonal/recent candidate | `0.7160` | `0.7542` | `0.3437` | `0.0109` | `0.5358` | `0.6952` | `0.6052` | `349` |
| Seasonal/recent candidate, recalibrated | `0.7522` | `0.7606` | `0.3437` | `0.0109` | `0.5888` | `0.6654` | `0.6248` | `304` |

Top drivers in the seasonal/recent candidate include `canyonLastObservedRank`, `canyonSeasonMeanRank`, `canyonSeasonPriorLow`, `canyonDaysSinceLastObs`, `canyonMonthMeanRank` and `canyonRecent365dMeanRank`.

Conclusion: runtime support was implemented through enriched runtime lookups and `DebitFeatureBuilder` selection by target month/season. The temporal-history model was copied into `modele_statistique` and is now the embedded production baseline. Its runtime lookup artifact is larger (`~18.7 MB`) because it stores month/season and recent-history snapshots.

### Reviewed-Labels Candidate Result

A candidate model was trained on `build/debit-pipeline/training-features-through-2026-05-28-reviewed/training_features.jsonl`, which includes the manually reviewed high-flow reintegrations. It was exported to `build/debit-pipeline/mobile-ordinal-reviewed-labels-candidate` and evaluated on the reviewed recent observations from `2026-03-26` to `2026-05-28`.

| Model | Recent accuracy | Recent balanced accuracy | Recent ordinal MAE | Severe ordinal errors | HIGH precision | HIGH recall | HIGH F1 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Current embedded model on reviewed rows | `0.6807` | `0.7262` | `0.3765` | `0.0109` | `0.5206` | `0.6580` | `0.5813` |
| Reviewed-labels candidate | `0.3381` | `0.5661` | `0.9720` | `0.0402` | `0.1858` | `1.0000` | `0.3133` |

High-flow detail on reviewed recent rows:

| Model | GROS predicted HIGH | TRES_GROS predicted HIGH | CRUE predicted HIGH | Overall predicted HIGH |
| --- | ---: | ---: | ---: | ---: |
| Current embedded model | `116/192` | `26/34` | `35/43` | `340/2014` |
| Reviewed-labels candidate | `192/192` | `34/34` | `43/43` | `1448/2014` |

Conclusion: do not replace `modele_statistique` with the reviewed-labels candidate as exported. The candidate removes recent high-flow false negatives, but it over-predicts `HIGH` massively and degrades all global ordinal metrics. The reintegrated labels are still valuable, but they require safer training/calibration treatment before being used in production.

## 3. Smarter Canyon History

- Add seasonal canyon priors.
- Add recency-weighted canyon priors.
- Smooth canyon history through massif and region priors.
- Track confidence based on observation volume and recency.

## 4. More Realistic Weather Features

- Keep training features aligned with app-side availability.
- Add forecast-specific features for tomorrow and after tomorrow.
- Strengthen snowmelt, rain-on-snow and extreme rainfall indicators.

## 5. Robust Ordinal Modelling

- Keep the six ordinal levels as the primary output.
- Optimize and compare using ordinal metrics, not only exact accuracy.
- Compare CatBoost, HGB ordinal, ExtraTrees/RandomForest and ensembles.
- Penalize dangerous under-estimation in model selection.

## 6. Product-Side Uncertainty

- Display the six-level prediction as the primary result.
- Show uncertainty and risk of `GROS/TRES_GROS/CRUE` as secondary safety signals.
- For uncertain `CORRECT/GROS` boundaries, communicate the range rather than over-stating precision.

## 7. External Data

- Explore nearby hydrometric stations when available.
- Add finer rainfall/radar or reanalysis data.
- Add snow water equivalent / snow height data where accessible.
- Improve regulation, dam, intake and hydropower descriptors.

## 8. Production Monitoring

- Version every model with data date, metrics and training configuration.
- Compare new user observations against the model version that would have predicted them.
- Identify canyons with repeated severe under-estimation.
- Retrain regularly during the active season.
