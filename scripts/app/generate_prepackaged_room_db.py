#!/usr/bin/env python3

import argparse
import json
import os
import re
import sqlite3
import unicodedata
from pathlib import Path


GEO_POINT_PRIORITY = {
    "PARKING_AMONT": 0,
    "PARKING_AVAL": 1,
    "ENTREE": 2,
    "SORTIE": 3,
    "POINT_REMARQUABLE": 4,
    "ECHAPPATOIRE": 5,
    "UNKNOWN": 6,
}

USELESS_NAVETTE_VALUES = {"non", "no", "aucune", "aucun", "0", "-"}

COUNTRY_CODE_MAP = {
    "FR": "France",
    "ES": "Espagne",
    "IT": "Italie",
    "CH": "Suisse",
    "AT": "Autriche",
    "PT": "Portugal",
    "DE": "Allemagne",
    "SI": "Slovenie",
    "GR": "Grece",
    "NZ": "Nouvelle-Zelande",
    "AU": "Australie",
    "JO": "Jordanie",
    "NP": "Nepal",
    "OM": "Oman",
    "MA": "Maroc",
    "CV": "Cap-Vert",
    "CL": "Chili",
    "TR": "Turquie",
    "BG": "Bulgarie",
    "ME": "Montenegro",
    "BO": "Bolivie",
    "MG": "Madagascar",
    "AD": "Andorre",
    "VE": "Venezuela",
    "HR": "Croatie",
    "MK": "Macedoine",
    "DJ": "Djibouti",
    "MU": "Maurice",
    "CN": "Chine",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build a prepackaged Room database from room-import JSON.")
    parser.add_argument("--room-import-dir", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--canyon-chains",
        type=Path,
        help="Reviewed passages from a canyon to another one (data-overlay/canyon-chains.json).",
    )
    parser.add_argument(
        "--recovered-geo-points",
        type=Path,
        help="Reviewed points of canyons the site gives none for (data-overlay/recovered-geo-points.json).",
    )
    return parser.parse_args()


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def compact_json(value):
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def normalize_for_search(value: str) -> str:
    normalized = unicodedata.normalize("NFD", value or "")
    without_marks = "".join(char for char in normalized if unicodedata.category(char) != "Mn")
    lowered = without_marks.lower()
    cleaned = "".join(char if char.isalnum() else " " for char in lowered)
    return re.sub(r"\s+", " ", cleaned, flags=re.UNICODE).strip()


def normalize_country_name(raw_value: str) -> str:
    stripped = (raw_value or "").strip()
    if not stripped:
        return stripped

    normalized = normalize_for_search(stripped)
    if normalized == "france espagne":
        return "France, Espagne"

    parts = administrative_tokens(stripped)
    if len(parts) > 1:
        return ", ".join(parts)

    if len(stripped) in (2, 3):
        return COUNTRY_CODE_MAP.get(stripped.upper(), stripped)

    return stripped


def administrative_tokens(value: str | None) -> list[str]:
    if not value:
        return []
    tokens = []
    seen = set()
    for part in re.split(r"[,;]", value):
        token = part.strip()
        if token and token not in seen:
            seen.add(token)
            tokens.append(token)
    return tokens


def normalized_interest(value):
    if value is None:
        return None
    if value < 0:
        return None
    return min(float(value), 4.0)


def has_useful_navette(value: str | None) -> bool:
    normalized = normalize_for_search(value or "")
    return bool(normalized) and normalized not in USELESS_NAVETTE_VALUES


def parse_cotation(raw_value: str | None) -> tuple[int | None, int | None, int | None]:
    cleaned = (raw_value or "").strip()
    match = re.fullmatch(r"(?i)v\s*(\d+)\s*/?\s*a\s*(\d+)\s*/?\s*([ivx]+)", cleaned)
    if not match:
        return None, None, None

    roman_to_int = {
        "I": 1,
        "II": 2,
        "III": 3,
        "IV": 4,
        "V": 5,
        "VI": 6,
        "VII": 7,
        "VIII": 8,
        "IX": 9,
        "X": 10,
    }
    return (
        int(match.group(1)),
        int(match.group(2)),
        roman_to_int.get(match.group(3).upper()),
    )


def best_marker_point(points: list[dict]) -> dict | None:
    if not points:
        return None
    return min(points, key=lambda point: GEO_POINT_PRIORITY.get(point.get("type", "UNKNOWN"), GEO_POINT_PRIORITY["UNKNOWN"]))


def build_subdivisions_by_country(search_items: list[dict]) -> list[dict]:
    known_country_by_subdivision: dict[str, str] = {}
    grouped: dict[str, set[str]] = {}

    for item in search_items:
        country_tokens = item["countryTokens"]
        if len(country_tokens) != 1:
            continue
        country = country_tokens[0]
        for subdivision in item["departmentTokens"]:
            key = normalize_for_search(subdivision)
            grouped.setdefault(key, set()).add(country)

    for subdivision, countries in grouped.items():
        if len(countries) == 1:
            known_country_by_subdivision[subdivision] = next(iter(countries))

    output = []
    for item in search_items:
        countries = list(dict.fromkeys(item["countryTokens"]))
        if not countries:
            item["subdivisionsByCountry"] = {}
            output.append(item)
            continue

        mapping = {country: [] for country in countries}
        subdivisions = list(dict.fromkeys(item["departmentTokens"]))
        if not subdivisions:
            item["subdivisionsByCountry"] = {country: [] for country in countries}
            output.append(item)
            continue

        if len(countries) == 1:
            mapping[countries[0]].extend(subdivisions)
            item["subdivisionsByCountry"] = {country: dedupe(values) for country, values in mapping.items()}
            output.append(item)
            continue

        unresolved = []
        for subdivision in subdivisions:
            inferred_country = known_country_by_subdivision.get(normalize_for_search(subdivision))
            matched_country = next((country for country in countries if country.lower() == (inferred_country or "").lower()), None)
            if matched_country is None:
                unresolved.append(subdivision)
            else:
                mapping[matched_country].append(subdivision)

        empty_countries = [country for country in countries if not mapping[country]]
        if unresolved and len(empty_countries) == 1:
            mapping[empty_countries[0]].extend(unresolved)
        elif len(unresolved) == len(empty_countries):
            for subdivision, country in zip(unresolved, empty_countries):
                mapping[country].append(subdivision)

        item["subdivisionsByCountry"] = {country: dedupe(values) for country, values in mapping.items()}
        output.append(item)

    return output


def dedupe(values: list[str]) -> list[str]:
    seen = set()
    output = []
    for value in values:
        if value not in seen:
            seen.add(value)
            output.append(value)
    return output


def build_search_items(canyons: list[dict], geo_points: list[dict]) -> list[dict]:
    points_by_canyon: dict[int, list[dict]] = {}
    for point in geo_points:
        points_by_canyon.setdefault(point["canyonId"], []).append(point)

    search_items = []
    for canyon in canyons:
        normalized_country = normalize_country_name(canyon["pays"])
        country_tokens = administrative_tokens(normalized_country)
        department_tokens = administrative_tokens(canyon.get("departement"))
        representative_point = best_marker_point(points_by_canyon.get(canyon["id"], []))
        searchable_parts = [
            canyon["nom"],
            canyon["nomComplet"],
            normalized_country,
            *country_tokens,
        ]
        if canyon.get("departement"):
            searchable_parts.append(canyon["departement"])
        searchable_parts.extend(department_tokens)
        if canyon.get("region"):
            searchable_parts.append(canyon["region"])
        if (canyon.get("commune") or "").strip():
            searchable_parts.append(canyon["commune"])
        for field_name in ("massif", "bassin", "coursEau"):
            if canyon.get(field_name):
                searchable_parts.append(canyon[field_name])

        vertical, aquatic, engagement = parse_cotation(canyon.get("cotation"))
        search_items.append(
            {
                "id": canyon["id"],
                "nom": canyon["nom"],
                "nomComplet": canyon["nomComplet"],
                "pays": normalized_country,
                "countryTokens": country_tokens,
                "region": canyon.get("region"),
                "departement": canyon.get("departement"),
                "departmentTokens": department_tokens,
                "commune": (canyon.get("commune") or "").strip() or None,
                "massif": canyon.get("massif"),
                "bassin": canyon.get("bassin"),
                "coursEau": canyon.get("coursEau"),
                "cotation": canyon.get("cotation") or "",
                "cotationVertical": vertical,
                "cotationAquatic": aquatic,
                "cotationEngagement": engagement,
                "interet": normalized_interest(canyon.get("interet")),
                "nbVotes": canyon.get("nbVotes", 0),
                "altitudeDepart": canyon.get("altitudeDepart"),
                "denivele": canyon.get("denivele"),
                "longueur": canyon.get("longueur"),
                "cascadeMax": canyon.get("cascadeMax"),
                "cordeMin": canyon.get("cordeMin"),
                "hasSpecificRegulation": 1 if canyon.get("hasSpecificRegulation") else 0,
                "isForbidden": 1 if canyon.get("isForbidden") else 0,
                "hasNavette": 1 if has_useful_navette(canyon.get("navette")) else 0,
                "representativeLat": representative_point.get("latitude") if representative_point else None,
                "representativeLng": representative_point.get("longitude") if representative_point else None,
                "url": canyon["url"],
                "searchableText": normalize_for_search(" ".join(searchable_parts)),
                "normalizedNom": normalize_for_search(canyon["nom"]),
                "normalizedNomComplet": normalize_for_search(canyon["nomComplet"]),
            }
        )

    return build_subdivisions_by_country(search_items)


def create_schema(connection: sqlite3.Connection, schema: dict):
    database = schema["database"]
    for entity in database["entities"]:
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))

    for query in database["setupQueries"]:
        connection.execute(query)

    connection.execute(f"PRAGMA user_version = {database['version']}")


CHAIN_WAYS = {"STRAIGHT_ON", "ON_FOOT", "NOT_IN_STREAM"}
RECOVERED_POINT_SOURCES = {"TOPO", "OPENCANYON", "ESTIMATED"}
GEO_POINT_TYPES = {"PARKING_AVAL", "PARKING_AMONT", "ENTREE", "SORTIE", "POINT_REMARQUABLE", "ECHAPPATOIRE"}


def load_canyon_chains(path: Path | None, canyon_ids: set[int]) -> list[tuple]:
    """The reviewed chains, checked against the catalog: a canyon gone from it must be reviewed again."""
    if path is None:
        return []
    rows = []
    for pair in load_json(path)["pairs"]:
        source, target, way, minutes = pair["from"], pair["to"], pair["way"], pair.get("minutes")
        if way not in CHAIN_WAYS:
            raise RuntimeError(f"Unknown chain way {way!r} for {source} -> {target}")
        missing = [canyon_id for canyon_id in (source, target) if canyon_id not in canyon_ids]
        if missing:
            raise RuntimeError(f"Chain {source} -> {target} names canyons missing from the catalog: {missing}")
        if minutes is not None and (not isinstance(minutes, int) or minutes < 0):
            raise RuntimeError(f"Invalid minutes {minutes!r} for {source} -> {target}")
        rows.append((source, target, way, minutes))
    return rows


def load_recovered_geo_points(path: Path | None, canyon_ids: set[int], site_points: list[dict]) -> list[dict]:
    """Points found elsewhere for canyons without any on the site; once the site has some, the site wins."""
    if path is None:
        return []
    canyons_with_site_points = {int(point["canyonId"]) for point in site_points}
    rows = []
    for point in load_json(path):
        canyon_id = int(point["canyonId"])
        if canyon_id not in canyon_ids:
            raise RuntimeError(f"Recovered point of canyon {canyon_id}, missing from the catalog: review it again")
        if point["source"] not in RECOVERED_POINT_SOURCES or point["type"] not in GEO_POINT_TYPES:
            raise RuntimeError(f"Invalid recovered point {point!r}")
        if canyon_id in canyons_with_site_points:
            print(f"Canyon {canyon_id} has points on the site now: its recovered points are left out")
            continue
        rows.append({**point, "label": point.get("title")})
    return rows


def insert_rows(
    connection: sqlite3.Connection,
    room_import_dir: Path,
    manifest: dict,
    canyon_chains_path: Path | None = None,
    recovered_geo_points_path: Path | None = None,
):
    canyons = load_json(room_import_dir / "canyons.json")
    site_geo_points = load_json(room_import_dir / "geo_points.json")
    recovered_geo_points = load_recovered_geo_points(
        recovered_geo_points_path, {int(row["id"]) for row in canyons}, site_geo_points
    )
    geo_points = [*site_geo_points, *recovered_geo_points]
    bibliography_entries = load_json(room_import_dir / "bibliography_entries.json")
    canyon_bibliography = load_json(room_import_dir / "canyon_bibliography.json")
    regulation_texts = load_json(room_import_dir / "regulation_texts.json")
    canyon_regulations = load_json(room_import_dir / "canyon_regulations.json")
    watersheds = load_json(room_import_dir / "watersheds.json")

    forum_users_path = room_import_dir / "forum_users.json"
    forum_users = load_json(forum_users_path) if forum_users_path.exists() else []

    tracks_path = room_import_dir / "tracks.json"
    if tracks_path.exists():
        canyon_tracks = load_json(tracks_path)
    else:
        canyon_tracks = []

    expected_tracks = int(manifest.get("counts", {}).get("tracks", 0))
    if expected_tracks > 0 and not canyon_tracks:
        raise RuntimeError("Manifest expects track rows but tracks.json is missing or empty.")

    search_items = build_search_items(canyons, geo_points)
    search_index_rows = [
        (
            item["id"],
            item["nom"],
            item["nomComplet"],
            item["pays"],
            compact_json(item["countryTokens"]) if item["countryTokens"] else None,
            item["region"],
            item["departement"],
            compact_json(item["departmentTokens"]) if item["departmentTokens"] else None,
            compact_json(item["subdivisionsByCountry"]),
            item["commune"],
            item["massif"],
            item["bassin"],
            item["coursEau"],
            item["cotation"],
            item["cotationVertical"],
            item["cotationAquatic"],
            item["cotationEngagement"],
            item["interet"],
            item["nbVotes"],
            item["altitudeDepart"],
            item["denivele"],
            item["longueur"],
            item["cascadeMax"],
            item["cordeMin"],
            item["hasSpecificRegulation"],
            item["isForbidden"],
            item["hasNavette"],
            item["representativeLat"],
            item["representativeLng"],
            item["url"],
            item["searchableText"],
            item["normalizedNom"],
            item["normalizedNomComplet"],
        )
        for item in search_items
    ]

    connection.executemany(
        """
        INSERT INTO canyons (
            id, nom, nomComplet, pays, region, departement, commune, communesJson, massif, bassin, coursEau,
            cotation, altitudeDepart, denivele, longueur, cascadeMax, cordeMin, tempsApproche, tempsDescente,
            tempsRetour, navette, interet, nbVotes, url, accesAval, accesAmont, approche, descente, retour,
            engagement, periode, geologie, historique, remarques, hasSpecificRegulation, isForbidden,
            lastUpdated, sourceType, sourceKey
        ) VALUES (
            :id, :nom, :nomComplet, :pays, :region, :departement, :commune, :communesJson, :massif, :bassin, :coursEau,
            :cotation, :altitudeDepart, :denivele, :longueur, :cascadeMax, :cordeMin, :tempsApproche, :tempsDescente,
            :tempsRetour, :navette, :interet, :nbVotes, :url, :accesAval, :accesAmont, :approche, :descente, :retour,
            :engagement, :periode, :geologie, :historique, :remarques, :hasSpecificRegulation, :isForbidden,
            :lastUpdated, :sourceType, :sourceKey
        )
        """,
        [
            {
                **row,
                "communesJson": compact_json(row.get("communes")) if row.get("communes") else None,
                "interet": normalized_interest(row.get("interet")),
                "hasSpecificRegulation": 1 if row.get("hasSpecificRegulation") else 0,
                "isForbidden": 1 if row.get("isForbidden") else 0,
                "sourceType": row.get("sourceType") or "DESCENTE_CANYON",
                "sourceKey": row.get("sourceKey") or f"dc:{row['id']}",
            }
            for row in canyons
        ],
    )

    connection.executemany(
        "INSERT INTO geo_points (canyonId, type, latitude, longitude, title, remark, source) VALUES (?, ?, ?, ?, ?, NULL, ?)",
        [
            (
                row["canyonId"],
                row["type"],
                row["latitude"],
                row["longitude"],
                row.get("label"),
                row.get("source"),
            )
            for row in geo_points
        ],
    )

    connection.executemany(
        "INSERT INTO bibliography_entries (id, kind, resourceType, title, authorsJson, publicationYear, reference, editor, status, scale, detailUrl, url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                row["id"],
                row["kind"],
                row.get("resourceType"),
                row["title"],
                compact_json(row.get("authors")) if row.get("authors") else None,
                row.get("publicationYear"),
                row.get("reference"),
                row.get("editor"),
                row.get("status"),
                row.get("scale"),
                row.get("detailUrl"),
                row.get("url"),
            )
            for row in bibliography_entries
        ],
    )

    connection.executemany(
        "INSERT OR IGNORE INTO canyon_bibliography (canyonId, bibliographyId) VALUES (?, ?)",
        [(row["canyonId"], row["bibliographyId"]) for row in canyon_bibliography],
    )

    connection.executemany(
        "INSERT INTO regulation_texts (id, status, action, title, summary, remark, details, effectiveDate, textUrl, attachmentsJson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                row["id"],
                row.get("status"),
                row.get("action"),
                row["title"],
                row.get("summary"),
                row.get("remark"),
                row.get("details"),
                row.get("effectiveDate"),
                row["textUrl"],
                compact_json(row.get("attachments")) if row.get("attachments") else None,
            )
            for row in regulation_texts
        ],
    )

    connection.executemany(
        "INSERT OR IGNORE INTO canyon_regulations (canyonId, regulationId) VALUES (?, ?)",
        [(row["canyonId"], row["regulationId"]) for row in canyon_regulations],
    )

    connection.executemany(
        "INSERT OR REPLACE INTO canyon_tracks (canyonId, trackId, name, role, isPrimary, sourceFile, pointCount, geometryJson, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            (
                row["canyonId"],
                row["trackId"],
                row["name"],
                row.get("role"),
                1 if row.get("isPrimary") else 0,
                row.get("sourceFile"),
                row.get("pointCount"),
                compact_json(row.get("geometry")),
                (row.get("bbox") or [None, None, None, None])[0],
                (row.get("bbox") or [None, None, None, None])[1],
                (row.get("bbox") or [None, None, None, None])[2],
                (row.get("bbox") or [None, None, None, None])[3],
            )
            for row in canyon_tracks
        ],
    )

    connection.executemany(
        "INSERT OR REPLACE INTO watersheds (canyonId, areaKm2, geometryJson, bboxMinLongitude, bboxMinLatitude, bboxMaxLongitude, bboxMaxLatitude) VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            (
                row["canyonId"],
                row.get("upstreamCatchmentAreaKm2"),
                compact_json(row.get("geometry")),
                (row.get("bbox") or [None, None, None, None])[0],
                (row.get("bbox") or [None, None, None, None])[1],
                (row.get("bbox") or [None, None, None, None])[2],
                (row.get("bbox") or [None, None, None, None])[3],
            )
            for row in watersheds
            if row.get("canyonId") is not None and (
                row.get("geometry") is not None or
                row.get("upstreamCatchmentAreaKm2") is not None or
                (row.get("bbox") and len(row.get("bbox")) == 4)
            )
        ],
    )

    if forum_users:
        connection.executemany(
            """
            INSERT OR REPLACE INTO forum_users (
                username, normalizedUsername, forumUserId, debitReportCount, forumPostCount, photoCount,
                canyonSheetContributionCount, lastDebitReportDate, lastDebitReportCanyonId,
                lastDebitReportCanyonName, lastForumPostDate, lastForumPostUrl, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                (
                    row["username"],
                    row["normalizedUsername"],
                    row.get("forumUserId"),
                    int(row.get("debitReportCount") or 0),
                    int(row.get("forumPostCount") or 0),
                    row.get("photoCount"),
                    row.get("canyonSheetContributionCount"),
                    row.get("lastDebitReportDate"),
                    row.get("lastDebitReportCanyonId"),
                    row.get("lastDebitReportCanyonName"),
                    row.get("lastForumPostDate"),
                    row.get("lastForumPostUrl"),
                    row["updatedAt"],
                )
                for row in forum_users
            ],
        )

    canyon_chains = load_canyon_chains(canyon_chains_path, {row["id"] for row in canyons})
    connection.executemany(
        "INSERT INTO canyon_chains (fromCanyonId, toCanyonId, way, minutes) VALUES (?, ?, ?, ?)",
        canyon_chains,
    )

    connection.executemany(
        "INSERT INTO search_index (id, nom, nomComplet, pays, countryTokensJson, region, departement, departmentTokensJson, subdivisionsByCountryJson, commune, massif, bassin, coursEau, cotation, cotationVertical, cotationAquatic, cotationEngagement, interet, nbVotes, altitudeDepart, denivele, longueur, cascadeMax, cordeMin, hasSpecificRegulation, isForbidden, hasNavette, representativeLat, representativeLng, url, searchableText, normalizedNom, normalizedNomComplet) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        search_index_rows,
    )

    expected_counts = manifest.get("counts", {})
    actual_counts = {
        "canyons": len(canyons),
        "geo_points": len(site_geo_points),
        "recovered_geo_points": len(recovered_geo_points),
        "bibliography_entries": len(bibliography_entries),
        "canyon_bibliography": len(canyon_bibliography),
        "regulation_texts": len(regulation_texts),
        "canyon_regulations": len(canyon_regulations),
        "watersheds": connection.execute("SELECT COUNT(*) FROM watersheds").fetchone()[0],
        "tracks": len(canyon_tracks),
        "forum_users": len(forum_users),
        "search_index": len(search_index_rows),
        "canyon_chains": len(canyon_chains),
    }

    for table_name in ("canyons", "geo_points", "bibliography_entries", "canyon_bibliography", "regulation_texts", "canyon_regulations", "watersheds", "tracks", "forum_users"):
        expected_value = int(expected_counts.get(table_name, 0))
        actual_value = int(actual_counts[table_name])
        if actual_value != expected_value:
            raise RuntimeError(f"Count mismatch for {table_name}: expected {expected_value}, got {actual_value}")

    if actual_counts["search_index"] != actual_counts["canyons"]:
        raise RuntimeError("Search index row count does not match canyon row count.")

    return actual_counts


def main() -> int:
    args = parse_args()
    room_import_dir = args.room_import_dir.resolve()
    schema_path = args.schema.resolve()
    output_path = args.output.resolve()

    manifest = load_json(room_import_dir / "manifest.json")
    schema = load_json(schema_path)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temp_output_path = output_path.with_suffix(f"{output_path.suffix}.tmp")
    if temp_output_path.exists():
        temp_output_path.unlink()

    connection = sqlite3.connect(temp_output_path)
    try:
        connection.execute("PRAGMA foreign_keys = OFF")
        connection.execute("PRAGMA journal_mode = MEMORY")
        connection.execute("PRAGMA synchronous = OFF")
        connection.execute("PRAGMA temp_store = MEMORY")

        create_schema(connection, schema)
        actual_counts = insert_rows(connection, room_import_dir, manifest, args.canyon_chains, args.recovered_geo_points)
        connection.commit()
        connection.execute("PRAGMA optimize")
        connection.execute("VACUUM")
        connection.commit()
    finally:
        connection.close()

    os.replace(temp_output_path, output_path)

    size_mb = output_path.stat().st_size / (1024 * 1024)
    print(f"Generated {output_path} ({size_mb:.2f} MB)")
    for key, value in actual_counts.items():
        print(f"- {key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
