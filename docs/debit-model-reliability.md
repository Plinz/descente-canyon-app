# Debit Model Reliability

> **Historique — remplacé par le modèle v3** (`docs/debit-prediction-model.md`, `scripts/debit_model/`). Les métriques « récentes » ci-dessous étaient mesurées sur des observations incluses dans l'entraînement et ne sont pas comparables.

This note tracks how reliability is measured after the physical descriptor work.

## Current State

- The improved feature pipeline uses the full weather archive, world watershed descriptors, basin morphology and response-proxy features.
- The model now also uses derived hydrology and prior-confidence features: precipitation ratios against climatology, runoff/flash-flood/snowmelt interaction proxies, basin precipitation volume, history confidence and prior lift/spread/entropy.
- The mobile export keeps only active numeric features after the coverage audit.
- Historical canyon priors remain the strongest drivers, so temporal validation alone can overstate reliability on canyons already seen many times.
- The target is still a qualitative user label, not a measured physical discharge. This limits the maximum fidelity reachable with feature engineering alone.

## Reliability Gates

Run the reliability report before deciding whether a newly exported model is better:

```bash
PYTHONPATH="/home/plinz/descente-canyon-app/scripts" ./.venv/bin/python scripts/debits/evaluate_model_reliability.py --features-path build/debit-pipeline/training-features-improved/training_features.jsonl --output-dir build/debit-pipeline/model-reliability
```

The report writes:

- `reliability_report.json` for detailed metrics, calibration bins, ablations and errors.
- `reliability_report.md` for the human-readable comparison table.
- The `full_canyon_history_dropout` variant keeps all features but randomly hides canyon-specific history during training, to test robustness when a canyon has little or no prior history.

To export the currently preferred mobile model after a positive reliability run:

```bash
PYTHONPATH="/home/plinz/descente-canyon-app/scripts" ./.venv/bin/python scripts/debits/export_mobile_embedded_model.py --features-path build/debit-pipeline/training-features-improved/training_features.jsonl --output-dir modele_statistique --default-policy balanced --canyon-history-dropout-rate 0.15
```

## Primary Decision Rule

- Use the temporal split to estimate behavior on known, already observed canyons.
- Use the cold-canyon split as the primary fidelity signal for generalization to canyons without their own history; the evaluator neutralizes canyon-specific history in cold-canyon calibration/test rows by default.
- Prefer a model only if it improves the balanced HIGH F1 without a large drop in balanced accuracy or calibration.

## Follow-up Improvements

- If `full` wins temporal but loses cold-canyon, consider a no-canyon-history or hybrid policy for canyons with few observations.
- If `full_canyon_history_dropout` closes the cold-canyon gap without hurting temporal metrics, export the mobile model with `--canyon-history-dropout-rate`.
- Keep `0.15` as the first dropout candidate: the quick reliability pass showed a small cold-canyon gain with limited temporal loss, while stronger dropout hurt known-canyon behavior.

## Ordinal Candidate

The most promising non-mobile candidate is now `scripts/debits/train_ordinal_model.py` with `--model hist_gradient_boosting` on the raw improved features.

Quick comparison against the current mobile random forest export:

| Model | Split | HIGH F1 balanced | HIGH precision | HIGH recall | Balanced accuracy |
| --- | --- | ---: | ---: | ---: | ---: |
| mobile RF export | temporal | 0.4670 | 0.3972 | 0.5667 | 0.6656 |
| CatBoost multiclass 3-class | temporal | 0.4872 | 0.3976 | 0.6289 | 0.6620 |
| ordinal CatBoost classifier expected-rank | temporal | 0.4803 | 0.4030 | 0.5943 | 0.6846 |
| ordinal CatBoost classifier long | temporal | 0.4893 | 0.4034 | 0.6217 | 0.6919 |
| ordinal HGB raw | temporal | 0.4844 | 0.4191 | 0.5738 | 0.6792 |
| ordinal HGB expressive raw | temporal | 0.4888 | 0.4113 | 0.6023 | 0.6866 |
| ordinal HGB expressive raw | cold-canyon | 0.4257 | 0.3421 | 0.5633 | 0.6077 |

The canyon-day target improves ordinal rank error but did not improve cold-canyon HIGH F1 in the quick run. Keep it as a label-cleaning tool, not yet as the default training target.

Current interpretation: ordinal scoring is useful because water level is naturally ordered. CatBoost is competitive. The best candidates now depend on the objective:

- Highest temporal accuracy among tested candidates: CatBoost multiclass 3-class (`accuracy=0.7164`, `HIGH F1=0.4872`).
- Best balanced/HIGH tradeoff among ordinal candidates: long CatBoost expected-rank (`balancedAccuracy=0.6919`, `HIGH F1=0.4893`) and expressive HGB ordinal (`macroF1=0.6490`, `HIGH F1=0.4888`).
- Best cold-canyon quick candidate among tested candidates: expressive HGB ordinal (`HIGH F1=0.4257`).

The strongest ordinal HGB command:

```bash
PYTHONPATH="/home/plinz/descente-canyon-app/scripts" ./.venv/bin/python scripts/debits/train_ordinal_model.py --features-path build/debit-pipeline/training-features-improved/training_features.jsonl --output-dir build/debit-pipeline/model-ordinal-hgb-expressive --split-mode temporal --model hist_gradient_boosting --n-estimators 420 --learning-rate 0.035 --max-depth 10 --max-leaf-nodes 63 --min-samples-leaf 20 --canyon-history-dropout-rate 0.15 --no-class-balanced-weights
```

Mobile export still needs a separate implementation or distillation step.

## Mobile Ordinal Candidate

Direct HGB ONNX export currently fails in this environment (`TreeEnsembleRegressor` conversion issue in `skl2onnx`). CatBoost exports cleanly to ONNX, so the first mobile ordinal candidate is CatBoost classifier expected-rank.

Candidate export command:

```bash
PYTHONPATH="/home/plinz/descente-canyon-app/scripts" ./.venv/bin/python scripts/debits/export_mobile_ordinal_model.py --features-path build/debit-pipeline/training-features-improved/training_features.jsonl --output-dir build/debit-pipeline/mobile-ordinal-catboost-candidate --iterations 900 --depth 8 --learning-rate 0.035 --l2-leaf-reg 8 --canyon-history-dropout-rate 0.15 --default-policy balanced
```

Candidate metrics:

| Candidate | Accuracy | Balanced accuracy | HIGH F1 | HIGH precision | HIGH recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| mobile RF current | 0.7090 | 0.6656 | 0.4670 | 0.3972 | 0.5667 |
| mobile ordinal CatBoost candidate | 0.6787 | 0.6919 | 0.4893 | 0.4034 | 0.6217 |

The Android runtime has been made backward-compatible with both existing 3-class probability artifacts and future 6-label ordinal artifacts. For six-label artifacts it aggregates probabilities into LOW/MEDIUM/HIGH and uses expected ordinal score thresholds from `thresholds.json`.
- If `history_only` is close to `full`, prioritize label quality and cold-start data before adding more static descriptors.
- If weather/physical ablations improve cold-canyon HIGH recall, keep those features in the mobile export and investigate cases where priors conflict with recent weather.
