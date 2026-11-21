# LSTM hydrology experiments (November 2026)

Question: can a recurrent network that reads the daily weather replace or improve the hand-made
hydrological state of the debit model (`hydro.py`: snow on 5 elevation bands, GR4J production
store, linear reservoirs of 1 to 90 days, then CatBoost)? In hydrology, LSTMs trained on many
basins are the state of the art for discharge (Kratzert et al., CAMELS), including ungauged basins.
Here the label is not a measured discharge but a practitioner's ordinal report (SEC … CRUE), with
the noise documented in `docs/debit-prediction-model.md`.

Results and conclusions: section « LSTM pour l'état hydrologique » of
`docs/debit-prediction-model.md`.

## Protocol

Same rows, folds and settings as the A/B harness (`experiment.py`): v6 dataset
(`build/debit-model/dataset`), test periods 2024 and 2025 → Oct. 2026, models trained on rows before
the test period, early stopping and decision thresholds on the last training year (validation),
CatBoost with `FAST_PARAMS`. Architecture choices are made on the validation RPS only; test numbers
are reported for every run. Gaps come with a 95 % interval from a paired bootstrap over canyons
(`lstm_compare.py`).

The network: daily inputs of the canyon's weather target (log rain, temperature, valid flag,
day-of-year harmonics; with `--inputs both`, also the daily states of the hand-made model of that
canyon), an embedding of the static descriptors repeated at every step, an LSTM (64 units by
default), and an MLP head that also takes the tabular features of the mode. Training shares the
recurrent pass between the reports of a canyon: chunks of (canyon, 365-day block) with 365 days of
warm-up, so a report sees 1 to 2 years of weather.

Controls: `--mode mlpfull` / `mlpfullhydro` train the same head without the recurrent pass (an MLP
on the tabular features), to tell a gain of the LSTM from a gain of mixing a network with CatBoost.
`lstm_baselines.py --params prod` uses the production CatBoost settings of `train.py`. With
`--horizon`, both scripts train J+1/J+2 models (real-time features lagged by 1 or 2 days, as
`train.py`) and test them at J+1.

## Scripts

Run from the repository root with
`PYTHONPATH=scripts/debit_model:scripts/debit_model/research .venv/bin/python` (PyTorch CPU in
`.venv`). Outputs go to `build/debit-model/lstm/`.

| Script | Role |
| --- | --- |
| `lstm_data.py` | Caches: dense weather series per target, daily hand-made hydro states per canyon, fold frames with history features. |
| `lstm_train.py` | Trains one network (`--mode`, `--inputs`, architecture flags); `--oof K` adds out-of-fold outputs for stacking. |
| `lstm_baselines.py` | CatBoost references and stacked models (`cb_full+<tag>`: production features + LSTM outputs). |
| `lstm_compare.py` | Metrics and paired bootstrap against a reference; `a&b` averages runs (ensembles). |
| `lstm_segments.py` | RPS gap by season, report age, snow, karst, basin size, observed level, recent rain. |

`build/debit-model/lstm/run_queue.sh <queue file>` runs a list of commands two at a time
(4 threads each) and logs to `build/debit-model/lstm/logs/`.
