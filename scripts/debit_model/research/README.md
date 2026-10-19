# Physical indicators tested for the debit model (October 2026)

Data-source scripts behind the "Indicateurs physiques testés" section of
`docs/debit-prediction-model.md`. None of these indicators entered the production model: the gains
were below 1 % of RPS and did not reproduce across the two test periods. The scripts are kept to
re-run the tests or to build a server-side radar feed later. Run them from the repository root
with the project virtualenv.

| Script | Source | Output |
| --- | --- | --- |
| `comephore_extract.py` | COMEPHORE, the Météo-France radar and rain-gauge hourly reanalysis at 1 km (data.gouv.fr, monthly tars of about 230 MB, 1997 to two months ago) | hourly catchment-mean rain and daily catchment maxima per canyon (about 1.3 GB for 2004 to 2026) |
| `osm_regulation_descriptors.py` | OSM extracts in `build/watersheds/osm-regulation/*/regulation_features.gpkg` | dams, weirs, penstocks, pipelines and canals in each catchment |
| `vigilance_fetch.py` | Météo-France weather warnings archive (open bucket, since 28/11/2022) | morning bulletin colours per department: storms, rain-flood, floods, snow |

```bash
.venv/bin/python scripts/debit_model/research/comephore_extract.py build/debit-model/comephore 200401 202608
.venv/bin/python scripts/debit_model/research/osm_regulation_descriptors.py build/debit-model/regulation/osm_regulation.json
python3 scripts/debit_model/research/vigilance_fetch.py build/debit-model/vigilance/vigilance_morning.csv
```

`comephore_extract.py` streams each monthly tar from the open-data bucket (nothing is written to
disk) and needs about 12 s per month, so roughly an hour for 2004 to 2026. Its catchment rain
matches the rain gauges within 3 km with a median daily correlation of 0.96.
