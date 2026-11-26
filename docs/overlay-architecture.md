# Overlay Architecture

## Goal

The app now supports two canyon sources merged into a single runtime catalog:

- `dc:*`: imported from Descente-Canyon
- `app:*`: proposed in the app, approved by the operator on the app's server (see
  `server/README.md`, « Canyons proposés »), copied into `data-overlay/app-canyons.json`

The Android app still consumes a single Room dataset and displays all canyons the same way.

## Source model

### Descente-Canyon base

The existing `offline-data/full/room-import` dataset remains the upstream source of truth for base canyon data.

### App overlay

Repository-managed additions live under `data-overlay/`.

Current additive enrichments allowed for `dc:*` canyons:

- watershed area
- watershed polygon
- one or more GPX tracks

No overrides of Descente-Canyon base fields are allowed yet.

### App canyons

`app:*` canyons are complete sheets with the fields of a site sheet. Their reference copy is the
database of the app's server (proposals, review, revisions); `dcserver canyons export` writes the
published ones to `data-overlay/app-canyons.json`, which the APK build embeds and the server catalog
job merges with its database (the database wins, a canyon it hides stays out).

## Runtime identifiers

- `dc:<id>` keeps the existing runtime integer id from Descente-Canyon
- `app:<id>` ids are allocated by the server from `1000000`, above the highest id of the repository copy

## Repository layout

```text
data-overlay/
  app-canyons.json
  dc/
    1234/
      overlay.json
      tracks/
        main.gpx
      watershed.geojson
  schemas/
    dc-overlay.schema.json
```

## Merge rules

### `dc:*`

- keep upstream base canyon fields unchanged
- append or fill additive overlay data
- allow multiple tracks
- allow one watershed payload per canyon

### `app:*`

- the sheet is the full canyon source (no photo, no flow report, no watershed yet)
- `scripts/app/generate_prepackaged_room_db.py --app-canyons` adds them to the catalog with
  `sourceType = APP`, `sourceKey = app:<id>` and no site URL

## Generated output

The merge tool regenerates `offline-data/full/room-import` with:

- `canyons.json`
- `geo_points.json`
- `bibliography_entries.json`
- `canyon_bibliography.json`
- `regulation_texts.json`
- `canyon_regulations.json`
- `watersheds.json`
- `tracks.json`
- `manifest.json`

The Android importer consumes that merged dataset at app startup.

## Submission flow

The Cloudflare Worker form is gone. Canyons are proposed from the app to its server, reviewed by the
operator in the review tool (`dcserver admin`), and published by the catalog job without an app
release (`server/README.md`).

## Notes

- the app's server publishes catalog updates (per-canyon bundles) between releases
- the overlay format is intentionally additive-first to avoid drifting from Descente-Canyon updates
