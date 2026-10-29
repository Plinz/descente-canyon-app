# Calcul des bassins versants d'entree

Ce dossier contient le point de depart du pipeline pour calculer une surface amont au point `ENTREE` de chaque canyon.

## Ce que fait le script

- lit `offline-data/full/room-import/canyons.json`
- lit `offline-data/full/room-import/geo_points.json`
- evalue chaque point `ENTREE`
- snappe le point sur la cellule ayant la plus forte `UPA` dans un petit rayon
- choisit un seul point d'entree par canyon
- prefere le point le plus amont par connectivite d'ecoulement quand le raster `dir` est fourni
- sinon prend le point avec la plus petite `UPA`
- journalise les cas suspects pour revue manuelle

## Rasters attendus

Le script consomme des rasters deja prepares localement.

- `UPA` obligatoire: raster de surface amont en km2
- `DIR` optionnel mais recommande: raster de direction d'ecoulement D8
- `ELV` optionnel: raster d'altitude pour verifier l'ordre amont/aval

Pour une phase 1 non commerciale, l'option la plus simple est `MERIT Hydro`:

- `UPA` = bande `upa`
- `DIR` = bande `dir`
- `ELV` = bande `elv`

Le script attend un fichier unique par raster, typiquement un `GeoTIFF` ou un `VRT` mosaque.

## Installation

```bash
python -m pip install -r scripts/watersheds/requirements.txt
```

Preparation des rasters MERIT: voir `scripts/watersheds/MERIT_PREP.md`.

Strategie hybride France IGN / Europe Copernicus: voir `scripts/watersheds/HYBRID_FR_IGN_EU_COPERNICUS.md`.

## Execution type

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/upa.vrt \
  --flowdir-raster D:/gis/merit/dir.vrt \
  --elevation-raster D:/gis/merit/elv.vrt \
  --output-dir build/watersheds/merit-phase-1
```

Pour cibler quelques canyons pendant le debug:

```bash
python scripts/watersheds/compute_entry_watersheds.py \
  --upa-raster D:/gis/merit/upa.vrt \
  --flowdir-raster D:/gis/merit/dir.vrt \
  --elevation-raster D:/gis/merit/elv.vrt \
  --only-canyon-id 241 \
  --only-canyon-id 2136
```

Verification interne sans raster reel:

```bash
python scripts/watersheds/compute_entry_watersheds.py --self-check
```

## Sorties

Le script ecrit dans `build/watersheds` par defaut:

- `run_metadata.json`
- `selected_entries.json`
- `suspicious_cases.json`
- `summary.json`
- `all_entry_points.geojson`
- `all_snapped_points.geojson`
- `entry_snap_lines.geojson`
- `selected_entry_points.geojson`

Les sorties `GeoJSON` servent a controler visuellement les snaps dans QGIS ou tout autre SIG.

## Codes suspects actuellement logges

- `ENTRY_OUTSIDE_UPA_RASTER`
- `ENTRY_NO_VALID_UPA_CANDIDATE`
- `SNAP_DISTANCE_LARGE`
- `SNAP_UPA_JUMP_LARGE`
- `ENTRY_SNAPPED_TO_OUTLET_OR_SINK`
- `MULTI_ENTRY_SAME_SNAP_CELL`
- `MULTI_ENTRY_FLOW_DISCONNECTED`
- `MULTI_ENTRY_UPA_ORDER_CONFLICT`
- `MULTI_ENTRY_ELEVATION_ORDER_CONFLICT`
- `MULTI_ENTRY_LABEL_ORDER_CONFLICT`
- `MULTI_ENTRY_SELECTION_FALLBACK`
- `CANYON_NO_VALID_ENTRY_RESULT`

## Limites de cette phase

- on calcule une `surface amont`, pas encore le polygone du bassin versant
- le choix du snap est volontairement simple et doit etre audite sur les cas suspects
- les secteurs karstiques, captes, glaciaires ou fortement amenages resteront a verifier a part
- sans `DIR`, le choix du point amont retombe sur la plus petite `UPA`

## Suite logique

- affiner les heuristiques de snap sur les cas suspects
- exporter ensuite la valeur retenue dans le pipeline d'import offline
- ajouter une phase 2 de recalcul haute resolution sur les cas douteux

## Recalcul cible des exutoires mal places

`audit_outlets.py` verifie que l'exutoire de chaque bassin de l'app est sur le cours d'eau du canyon : point `ENTREE` dans le bassin, exutoire pas au-dessus de l'altitude de depart, bassin qui monte jusqu'au depart, surface superieure a 0.05 km2, exutoire proche des points du canyon.

```bash
.venv/bin/python scripts/watersheds/audit_outlets.py --output build/watersheds/outlet-audit.json
```

Les canyons sans bassin ou suspects (hors revues `good`) se recalculent avec `--entry-guided-outlet` : l'exutoire est pris sur le chemin d'ecoulement D8 du point `ENTREE`, au plus pres du point de sortie (point place en revue, sinon `SORTIE`, sinon parking aval). Sans point aval, il est place la ou le chemin a perdu le denivele du canyon. `--skip-descriptors` ne calcule que la surface et le polygone. En France, la source `ign-wms-*` lit le RGE ALTI 5 m sur le WMS de la Geoplateforme autour du canyon (`sources/prepare_ign_wms_dem.py`), sans telecharger les archives departementales.

```bash
.venv/bin/python scripts/watersheds/run_catchment_batch.py \
  --source-config scripts/watersheds/source_config.hybrid.json \
  --output-dir build/watersheds/batch-run-guided-2026-10-28 \
  --only-canyon-id-file scripts/watersheds/guided_outlet_targets_20261028.txt \
  --entry-guided-outlet --skip-descriptors --jobs 4

.venv/bin/python scripts/watersheds/audit_outlets.py \
  --run build/watersheds/batch-run-guided-2026-10-28 \
  --output build/watersheds/batch-run-guided-2026-10-28/outlet-decisions.json \
  --write-accepted-run build/watersheds/batch-run-guided-2026-10-28-accepted

.venv/bin/python scripts/watersheds/package_results.py \
  --source-dir build/watersheds/batch-run-guided-2026-10-28-accepted \
  --track incremental --scope overlay --label 2026-10-28-guided-outlet-v1

.venv/bin/python scripts/watersheds/export_room_import.py --input watershed-results
```

Un nouveau resultat remplace l'actuel seulement s'il a moins de signaux d'alerte, et un canyon sans bassin prend le sien. Les descripteurs (modele de debit) de ces canyons restent a recalculer : relancer la meme commande sans `--skip-descriptors`.

### Passe complete du 2026-10-29

Tous les canyons sans bassin ou au bassin suspect (exutoire hors du cours d'eau, points GPS mal places) ont ete recalcules avec les descripteurs (`build/watersheds/full-pass-2026-10-29-ids.txt`, puis `rerun-2026-10-29-ids.txt` pour les canyons ajoutes ensuite et les exutoires corriges). Chaque resultat est controle a l'oeil sur des planches (`render_outlet_sheets.py` : ancien bassin en tirets, nouveau en orange, points du canyon, exutoire) et le verdict est note dans `visual-checks.json` du run (`ok ...`, `rejected ...`, ou `fix ...` en attente d'un recalcul). Avec `--visual-checks`, ce sont ces verdicts qui decident a la place des signaux d'alerte : seul un `ok` remplace le bassin actuel.

```bash
.venv/bin/python scripts/watersheds/render_outlet_sheets.py \
  --run build/watersheds/batch-run-guided-2026-10-29-full \
  --decisions build/watersheds/batch-run-guided-2026-10-28/decisions.json \
  --only-canyon-id-file <ids a controler> \
  --extra-points-json build/watersheds/extra-points-2026-10-29-v3.json \
  --output-dir build/watersheds/batch-run-guided-2026-10-29-full/sheets-batchN

.venv/bin/python scripts/watersheds/audit_outlets.py \
  --run build/watersheds/batch-run-guided-2026-10-29-full \
  --extra-points-json build/watersheds/extra-points-2026-10-29-v3.json \
  --visual-checks build/watersheds/batch-run-guided-2026-10-29-full/visual-checks.json \
  --output build/watersheds/batch-run-guided-2026-10-29-full/outlet-decisions.json \
  --write-accepted-run build/watersheds/batch-run-guided-2026-10-29-accepted
```

Les erreurs typiques reperees a l'oeil : exutoire passe sur la riviere receptrice (point de sortie a la confluence, entree hors d'un chenal du MNT), MNT trop grossier pour une fente etroite, points du site incoherents avec le topo. Quand le chemin D8 depuis l'entree rejoint le cours d'eau principal a l'altitude et a la longueur du topo, l'exutoire corrige est place a la fin de ce chemin dans `watershed-review/watershed-review.json` (`adminPlaced`, `manual_map`).

Les points ajoutes aux canyons sans point sur le site (`--extra-points-json`) viennent d'OpenCanyon (CC BY-NC-SA 4.0), de coordonnees ecrites dans le topo, ou sont places sur le cours d'eau OpenStreetMap (ODbL) au nom unique dans le departement, aux altitudes du topo, quand le chemin D8 suit le trace OSM et que la longueur colle. Une fois le bassin accepte, `build_recovered_geo_points.py` les ecrit dans `data-overlay/recovered-geo-points.json` pour l'app, avec leur source.
