from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import re
import sys
import time
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup


BASE_URL = "https://www.descente-canyon.com"
MAP_INDEX_URL = f"{BASE_URL}/canyoning/carte.json"
USER_AGENT = "DescenteCanyonAppSampleScraper/0.2"
REQUEST_DELAY_SECONDS = 0.0

FETCH_CACHE: dict[str, str] = {}
REGULATION_CACHE: dict[int, dict[str, Any]] = {}

MAP_POINT_REGEX = re.compile(
    r"var point = \{position: new google\.maps\.LatLng\((?P<lat>-?[\d.]+),(?P<lng>-?[\d.]+)\),"
    r"type: '(?P<raw_type>[a-z_]+)',remarque: '(?P<remark>(?:\\'|[^'])*)'"
    r".*?\};",
    re.S,
)

REGULATION_ID_REGEX = re.compile(r"/reglemtexte/(\d+)/")
CANYON_LINK_REGEX = re.compile(r"/canyoning/canyon/(\d+)/")
LOCATION_LINK_REGEX = re.compile(
    r"(?P<href>/canyoning/(?:"
    r"lieux/\d+/liste-des-lieux\.html|"
    r"lieu/\d+/[^\"'?#]+\.html|"
    r"lieu-existe-aussi/\d+/seconde-zone\.html"
    r"))"
)

GEO_TYPE_MAP = {
    "parking": "PARKING_AVAL",
    "parking_aval": "PARKING_AVAL",
    "parking_amont": "PARKING_AMONT",
    "depart": "ENTREE",
    "arrivee": "SORTIE",
    "point_externe": "POINT_REMARQUABLE",
    "point_interne": "POINT_REMARQUABLE",
}

REPRESENTATIVE_POINT_ORDER = {
    "PARKING_AMONT": 0,
    "PARKING_AVAL": 1,
    "ENTREE": 2,
    "SORTIE": 3,
    "POINT_REMARQUABLE": 4,
    "ECHAPPATOIRE": 5,
    "UNKNOWN": 6,
}


def absolute_url(value: str | None) -> str | None:
    if not value:
        return None
    return urljoin(BASE_URL, value)


def fetch_text(url: str) -> str:
    cached = FETCH_CACHE.get(url)
    if cached is not None:
        return cached

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            if REQUEST_DELAY_SECONDS > 0:
                time.sleep(REQUEST_DELAY_SECONDS)
            request = Request(url, headers={"User-Agent": USER_AGENT})
            with urlopen(request, timeout=30) as response:
                body = response.read().decode("utf-8", errors="replace")
            FETCH_CACHE[url] = body
            return body
        except HTTPError:
            raise
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            if attempt == 2:
                raise
            time.sleep(1.5 * (attempt + 1))

    assert last_error is not None
    raise last_error


def fetch_optional_text(url: str) -> str | None:
    try:
        return fetch_text(url)
    except HTTPError as exc:
        if exc.code == 404:
            return None
        raise
    except URLError:
        return None


def clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def clean_multiline_text(value: str | None) -> str | None:
    if value is None:
        return None
    lines = [re.sub(r"\s+", " ", line).strip() for line in value.splitlines()]
    paragraphs = [line for line in lines if line]
    return "\n\n".join(paragraphs) or None


def clean_line_list(lines: list[str]) -> str | None:
    normalized = [clean_text(line) for line in lines]
    kept = [line for line in normalized if line]
    return "\n".join(kept) if kept else None


def clean_inline_punctuation(value: str | None) -> str | None:
    value = clean_text(value)
    if value is None:
        return None
    value = re.sub(r"\s+,", ",", value)
    value = re.sub(r",\s*,+", ", ", value)
    value = re.sub(r"\s{2,}", " ", value)
    return value.strip(" ,") or None


def normalize_label(value: str) -> str:
    value = unicodedata.normalize("NFKD", value)
    value = "".join(char for char in value if not unicodedata.combining(char))
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


def split_lines(value: str | None) -> list[str]:
    if not value:
        return []
    return [line for line in (clean_text(part) for part in value.splitlines()) if line]


def element_lines(element: Any) -> list[str]:
    if element is None:
        return []
    soup = BeautifulSoup(str(element), "html.parser")
    for br in soup.select("br"):
        br.replace_with("\n")
    text = soup.get_text("", strip=False)
    return split_lines(text)


def extract_int(value: str | None) -> int | None:
    if not value:
        return None
    digits = re.sub(r"[^\d]", "", value)
    return int(digits) if digits else None


def extract_float(value: str | None) -> float | None:
    if not value:
        return None
    normalized = value.replace(",", ".")
    match = re.search(r"\d+(?:\.\d+)?", normalized)
    return float(match.group(0)) if match else None


def normalize_interest(value: float | None) -> float | None:
    if value is None:
        return None
    return value if 0.0 <= value <= 4.0 else None


def is_active_regulation_status(value: str | None) -> bool:
    return normalize_label(value or "") in {"", "actif", "indefini"}


def rule_text(rule: dict[str, Any]) -> str:
    parts = [rule.get("summary"), rule.get("remark"), rule.get("details")]
    return normalize_label(" ".join(part for part in parts if part))


def infer_canyon_forbidden(canyon: dict[str, Any]) -> bool:
    regulation = canyon["reglementation"]
    rating = canyon["rating"]
    timings = canyon["timings"]
    topo = canyon["topo"]

    explicit_forbidden = any(
        is_active_regulation_status(rule.get("status")) and (
            normalize_label(rule.get("action") or "") == "interdit" or
            any(
                phrase in rule_text(rule)
                for phrase in (
                    "pratique du canyon y est donc interdite",
                    "pratique du canyoning est interdite",
                    "descente du canyon interdite",
                    "descente interdite du canyon",
                    "canyon interdit",
                )
            )
        )
        for rule in regulation.get("rules", [])
    )

    has_practical_info = any(
        clean_text(value)
        for value in (
            rating.get("cotation"),
            timings.get("approche"),
            timings.get("descente"),
            timings.get("retour"),
            topo.get("accesAval"),
            topo.get("accesAmont"),
            topo.get("approche"),
            topo.get("descente"),
            topo.get("retour"),
            topo.get("engagement"),
            topo.get("periode"),
        )
    )
    missing_practical_info = (
        regulation.get("hasSpecificRegulation") and
        not has_practical_info and
        normalize_interest(rating.get("interet")) is None and
        int(rating.get("nbVotes") or 0) == 0
    )
    return explicit_forbidden or missing_practical_info


def enrich_canyon_flags(payload: dict[str, Any]) -> dict[str, Any]:
    canyon = payload.get("canyon")
    if not canyon:
        return payload

    rating = canyon.setdefault("rating", {})
    rating["interet"] = normalize_interest(rating.get("interet"))
    canyon.setdefault("reglementation", {})["isForbidden"] = infer_canyon_forbidden(canyon)
    return payload


def badge_value(container: BeautifulSoup | Any, icon_class: str) -> str | None:
    if container is None:
        return None
    for item in container.select("li"):
        icon = item.select_one(f"span.{icon_class}")
        if icon is None:
            continue
        badge = item.select_one("span.badge")
        if badge is None:
            continue
        return clean_text(badge.get_text(" ", strip=True))
    return None


def split_access_section(access_text: str | None) -> tuple[str | None, str | None]:
    if not access_text:
        return None, None

    normalized = access_text.replace("\r\n", "\n")
    matches = list(re.finditer(r"(?im)(^|\n+)\s*(aval|amont)\s*:?\s*", normalized))
    if not matches:
        return clean_multiline_text(access_text), None

    sections: dict[str, str] = {}
    for index, match in enumerate(matches):
        label = match.group(2).lower()
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(normalized)
        value = normalized[start:end].strip()
        if value:
            sections[label] = value

    if not sections:
        return clean_multiline_text(access_text), None

    return clean_multiline_text(sections.get("aval")), clean_multiline_text(sections.get("amont"))


def collect_section_text(start_heading: Any) -> str | None:
    parts: list[str] = []
    current = start_heading.find_next_sibling()
    while current is not None and current.name != "h3":
        if current.name == "p":
            parts.append(current.get_text("\n", strip=True))
        current = current.find_next_sibling()
    return clean_multiline_text("\n\n".join(parts))


def stable_id(prefix: str, *values: str | None) -> str:
    key = "|".join(value or "" for value in values)
    digest = hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{digest}"


def uncut_address(text: str, href: str | None) -> str:
    """The site shows long addresses cut in their middle ("http://site/a.....b.jpg"): its link holds the whole one."""
    head, cut, tail = text.partition(".....")
    if cut and href and href.startswith(head) and href.endswith(tail):
        return href
    return text


def parse_map_index_ids(raw_body: str) -> list[int]:
    json_string = raw_body.split("var data=", 1)[-1].strip().removesuffix(";")
    if not json_string.startswith("{"):
        return []
    root = json.loads(json_string)
    items = root.get("c", [])
    canyon_ids: list[int] = []
    for item in items:
        raw_id = str(item.get("a", "")).strip()
        if not raw_id:
            continue
        canyon_id = int(f"2{raw_id}")
        canyon_ids.append(canyon_id)
    return sorted(set(canyon_ids))


def extract_canyon_ids(raw_body: str) -> set[int]:
    return {
        int(match.group(1))
        for match in CANYON_LINK_REGEX.finditer(raw_body)
    }


def normalize_canyoning_path(href: str) -> str | None:
    if not href:
        return None
    parsed = urlparse(urljoin(BASE_URL, href))
    if parsed.netloc and parsed.netloc != urlparse(BASE_URL).netloc:
        return None
    return parsed.path if parsed.path.startswith("/canyoning/") else None


def extract_location_paths(raw_body: str) -> set[str]:
    paths: set[str] = set()
    for match in LOCATION_LINK_REGEX.finditer(raw_body):
        path = normalize_canyoning_path(match.group("href"))
        if path is not None:
            paths.add(path)
    return paths


def location_id_from_path(path: str) -> str | None:
    match = re.search(r"/canyoning/(?:lieu|lieux)/(\d+)/", path)
    return match.group(1) if match else None


def is_canyon_list_path(path: str, location_id: str) -> bool:
    return bool(re.search(rf"/canyoning/lieu/{re.escape(location_id)}/[^/]+\.html$", path))


def is_secondary_canyon_list_path(path: str, location_id: str) -> bool:
    return path == f"/canyoning/lieu-existe-aussi/{location_id}/seconde-zone.html"


def extract_country_canyon_listing_paths(raw_body: str, location_id: str) -> set[str]:
    return {
        path
        for path in extract_location_paths(raw_body)
        if is_canyon_list_path(path, location_id) or is_secondary_canyon_list_path(path, location_id)
    }


def fetch_location_canyon_ids() -> list[int]:
    root_body = fetch_text(f"{BASE_URL}/canyoning")
    canyon_ids = extract_canyon_ids(root_body)
    seed_paths = sorted(extract_location_paths(root_body))
    listing_paths: set[str] = set()
    failed_paths: list[str] = []

    for seed_path in seed_paths:
        location_id = location_id_from_path(seed_path)
        if location_id is None:
            continue
        try:
            body = fetch_text(f"{BASE_URL}{seed_path}")
        except Exception as exc:  # noqa: BLE001
            failed_paths.append(f"{seed_path}: {exc!r}")
            continue

        canyon_ids.update(extract_canyon_ids(body))
        if is_canyon_list_path(seed_path, location_id):
            listing_paths.add(seed_path)
        listing_paths.update(extract_country_canyon_listing_paths(body, location_id))

    for listing_path in sorted(listing_paths):
        try:
            body = fetch_text(f"{BASE_URL}{listing_path}")
        except Exception as exc:  # noqa: BLE001
            failed_paths.append(f"{listing_path}: {exc!r}")
            continue
        canyon_ids.update(extract_canyon_ids(body))

    print(
        f"Location crawl: seeds={len(seed_paths)} listings={len(listing_paths)} canyon_ids={len(canyon_ids)} failures={len(failed_paths)}",
        file=sys.stderr,
    )
    if failed_paths:
        for failure in failed_paths[:10]:
            print(f"Location crawl failure: {failure}", file=sys.stderr)
        if len(failed_paths) > 10:
            print(f"Location crawl failure: ... {len(failed_paths) - 10} more", file=sys.stderr)
    return sorted(canyon_ids)


def fetch_all_canyon_ids() -> list[int]:
    map_ids = set(parse_map_index_ids(fetch_text(MAP_INDEX_URL)))
    location_ids = set(fetch_location_canyon_ids())
    merged_ids = sorted(map_ids | location_ids)
    print(
        f"ID sources: map={len(map_ids)} locations={len(location_ids)} merged={len(merged_ids)}",
        file=sys.stderr,
    )
    return merged_ids


def fetch_map_canyon_ids() -> list[int]:
    return parse_map_index_ids(fetch_text(MAP_INDEX_URL))


def parse_summary_page(canyon_id: int, html: str) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")

    h1 = soup.select_one("h1")
    h2 = soup.select_one("h2.h3")
    main_name = clean_text(h1.select_one("strong").get_text(" ", strip=True) if h1 and h1.select_one("strong") else "") or ""
    title_prefix = h1.get_text(" ", strip=True).split(main_name)[0].strip() if h1 and main_name else ""
    nom_complet = clean_inline_punctuation(h2.get_text(" ", strip=True) if h2 else h1.get_text(" ", strip=True) if h1 else "") or main_name

    breadcrumbs = [clean_text(item.get_text(" ", strip=True)) or "" for item in soup.select("ol.breadcrumb li")]
    pays = breadcrumbs[2] if len(breadcrumbs) > 2 else ""
    departement = breadcrumbs[3] if len(breadcrumbs) > 3 else None

    fiche = soup.select_one("div.fichetechnique") or soup
    summary_block = fiche.select_one("p")
    canonical = soup.select_one("link[rel='canonical']")
    regulation_link = soup.select_one(f"a[href*='/canyoning/canyon-reglementation/{canyon_id}/legislation.html']")

    summary_lines = split_lines(summary_block.get_text("\n", strip=True) if summary_block else None)
    labeled_lines: dict[str, str] = {}
    for line in summary_lines:
        if ":" not in line:
            continue
        label, value = line.split(":", 1)
        labeled_lines[normalize_label(label)] = clean_inline_punctuation(value) or ""

    location_links = summary_block.select("a[href*='/lieu/']") if summary_block is not None else []
    region = None
    massif = None
    bassin = None
    communes: list[str] = []
    for link in location_links:
        href = link.get("href", "")
        text = clean_text(link.get_text(" ", strip=True))
        if not text:
            continue
        if re.search(r"/lieu/\d{5}/", href) and region is None and text != departement:
            region = text
            continue
        if "/lieu/14/" in href and massif is None:
            massif = text
            continue
        if "/lieu/15/" in href and bassin is None:
            bassin = text
            continue
        if re.search(r"/lieu/\d{5}/", href) and text not in {region, departement}:
            communes.append(text)

    interest_block = next(
        (
            link
            for link in soup.select("a[href*='canyon-interet']")
            if re.search(r"\d+\s*vote", link.get_text(" ", strip=True), re.I)
        ),
        None,
    )
    interest_container = interest_block.parent if interest_block is not None else None
    interest_text = clean_text(interest_container.get_text(" ", strip=True)) if interest_container else None
    interest_match = re.search(r"(\d+(?:[.,]\d+)?)\s*/\s*4\b", interest_text or "")
    interet = normalize_interest(extract_float(interest_match.group(1)) if interest_match else None)
    nb_votes = 0
    if interest_block is not None:
        votes_match = re.search(r"(\d+)\s*vote", interest_block.get_text(" ", strip=True), re.I)
        if votes_match:
            nb_votes = int(votes_match.group(1))

    return {
        "id": canyon_id,
        "nom": main_name,
        "nomComplet": nom_complet,
        "prefixeNom": clean_text(title_prefix),
        "pays": pays,
        "region": region,
        "departement": departement,
        "communes": communes,
        "communePrincipale": communes[-1] if communes else None,
        "massif": massif,
        "bassin": bassin,
        "coursEau": labeled_lines.get("cours d eau"),
        "cotation": badge_value(fiche, "picto-huit") or "",
        "interet": interet,
        "nbVotes": nb_votes,
        "altitudeDepartM": extract_int(badge_value(fiche, "picto-altidep")),
        "deniveleM": extract_int(badge_value(fiche, "picto-deniv")),
        "longueurM": extract_int(badge_value(fiche, "picto-long")),
        "cascadeMaxM": extract_int(badge_value(fiche, "picto-cmax")),
        "cordeMiniM": extract_int(badge_value(fiche, "picto-corde")),
        "tempsApproche": badge_value(fiche, "picto-appr"),
        "tempsDescente": badge_value(fiche, "picto-desc"),
        "tempsRetour": badge_value(fiche, "picto-retour"),
        "navette": badge_value(fiche, "picto-navette"),
        "url": canonical.get("href") if canonical is not None else f"{BASE_URL}/canyoning/canyon/{canyon_id}/",
        "hasSpecificRegulation": regulation_link is not None,
        "regulationUrl": absolute_url(regulation_link.get("href")) if regulation_link is not None else None,
    }


def parse_description_page(canyon_id: int, html: str) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")
    sections: dict[str, str] = {}

    for heading in soup.select("h3"):
        key = normalize_label(heading.get_text(" ", strip=True))
        value = collect_section_text(heading)
        if key and value:
            sections[key] = value

    access_key = next((key for key in sections if key.startswith("acces")), None)
    acces_aval, acces_amont = split_access_section(sections.get(access_key))

    def pick(*prefixes: str) -> str | None:
        for key, value in sections.items():
            if any(key.startswith(prefix) for prefix in prefixes):
                return value
        return None

    return {
        "id": canyon_id,
        "accesAval": acces_aval,
        "accesAmont": acces_amont,
        "approche": pick("approche"),
        "descente": pick("descente"),
        "retour": pick("retour"),
        "engagement": pick("engagement"),
        "periode": pick("periode"),
        "geologie": pick("geologie"),
        "historique": pick("historique"),
        "remarques": pick("remarques"),
    }


def parse_map_page(canyon_id: int, html: str) -> dict[str, Any]:
    geo_points: list[dict[str, Any]] = []
    for match in MAP_POINT_REGEX.finditer(html):
        raw_type = match.group("raw_type")
        remark = match.group("remark").replace("\\'", "'").strip()
        geo_points.append(
            {
                "canyonId": canyon_id,
                "rawType": raw_type,
                "type": GEO_TYPE_MAP.get(raw_type, "UNKNOWN"),
                "latitude": float(match.group("lat")),
                "longitude": float(match.group("lng")),
                "label": clean_text(remark),
            }
        )

    representative = min(
        geo_points,
        key=lambda point: REPRESENTATIVE_POINT_ORDER.get(point["type"], 99),
        default=None,
    )

    representative_point = None
    if representative is not None:
        representative_point = {
            "type": representative["type"],
            "latitude": representative["latitude"],
            "longitude": representative["longitude"],
            "label": representative["label"],
        }

    return {
        "id": canyon_id,
        "geoPoints": geo_points,
        "representativePoint": representative_point,
    }


# Section titles of the bibliography page, singular for a single entry ("Livre / Topoguide", "Carte").
BIBLIOGRAPHY_SECTIONS = {
    "livre topoguide": "topoguides",
    "livres topoguides": "topoguides",
    "carte": "maps",
    "cartes": "maps",
    "site internet": "resources",
    "sites internet": "resources",
}


def parse_bibliography_page(html: str | None) -> dict[str, Any]:
    if not html:
        return {"topoguides": [], "maps": [], "resources": []}

    soup = BeautifulSoup(html, "html.parser")
    items = soup.select("div.list-group > div.list-group-item")
    current_section: str | None = None
    topoguides: list[dict[str, Any]] = []
    maps: list[dict[str, Any]] = []
    resources: list[dict[str, Any]] = []

    for item in items:
        section_header = item.select_one("h2")
        if section_header is not None:
            current_section = BIBLIOGRAPHY_SECTIONS.get(normalize_label(section_header.get_text(" ", strip=True)))
            continue

        if current_section == "topoguides":
            title = clean_text(item.select_one("h5").get_text(" ", strip=True) if item.select_one("h5") else None)
            if not title:
                continue
            link = item.select_one("a[href]")
            detail_url = absolute_url(link.get("href")) if link is not None else None
            # Some websites are listed as topoguides, under their address.
            title = uncut_address(title, detail_url)
            lines = split_lines(item.get_text("\n", strip=True))

            authors: list[str] = []
            publication_year = None
            reference = None
            editor = None
            status = None
            in_library_status = False
            library_status_lines: list[str] = []
            for line in lines:
                normalized = normalize_label(line)
                if normalized.startswith("auteur s"):
                    value = clean_inline_punctuation(line.split(":", 1)[1] if ":" in line else None)
                    if value:
                        authors = [author.strip() for author in value.split(",") if author.strip()]
                elif normalized.startswith("parution"):
                    raw = clean_inline_punctuation(line.split(":", 1)[1] if ":" in line else None)
                    parts = [part.strip() for part in raw.split(" - ")] if raw else []
                    if parts:
                        publication_year = extract_int(parts[0])
                    for part in parts[1:]:
                        if normalize_label(part).startswith("ref"):
                            reference = clean_inline_punctuation(part.split(".", 1)[1] if "." in part else part.split(":", 1)[1] if ":" in part else part)
                        if normalize_label(part).startswith("ed"):
                            editor = clean_inline_punctuation(part.split(".", 1)[1] if "." in part else part.split(":", 1)[1] if ":" in part else part)
                elif normalized.startswith("librairie"):
                    in_library_status = True
                    tail = clean_inline_punctuation(line.split(":", 1)[1] if ":" in line else None)
                    if tail:
                        library_status_lines.append(tail)
                    continue
                elif in_library_status and normalized.startswith("en savoir plus"):
                    in_library_status = False
                elif in_library_status and (normalized.startswith("reference dans la") or normalized == "librairie canyon"):
                    continue
                elif in_library_status:
                    library_status_lines.append(line)

            status = clean_multiline_text("\n".join(library_status_lines))

            entry_id = stable_id("biblio", detail_url or title, str(publication_year), reference)
            topoguides.append(
                {
                    "id": entry_id,
                    "kind": "TOPOGUIDE",
                    "title": title,
                    "authors": authors,
                    "publicationYear": publication_year,
                    "reference": reference,
                    "editor": editor,
                    "status": status,
                    "detailUrl": detail_url,
                }
            )

        elif current_section == "maps":
            title_block = item.select_one("h5")
            title_text = clean_text(title_block.get_text(" ", strip=True) if title_block is not None else None)
            if not title_text:
                continue
            title = title_text
            scale = None
            if " - " in title_text:
                title, scale = [part.strip() for part in title_text.rsplit(" - ", 1)]
            entry_id = stable_id("map", title, scale)
            maps.append(
                {
                    "id": entry_id,
                    "kind": "MAP",
                    "title": title,
                    "scale": scale,
                    "detailUrl": None,
                }
            )

        elif current_section == "resources":
            # A website is shown under its address, maybe cut (see uncut_address): its link is the address.
            # A relative link ("page.doc") leads nowhere, on the site as well.
            link = item.select_one("a[href]")
            url = link.get("href", "").strip() if link is not None else ""
            if urlparse(url).scheme not in {"http", "https"}:
                continue
            resources.append(
                {
                    "id": stable_id("resource", url),
                    "kind": "RESOURCE",
                    "resourceType": "WEBSITE",
                    "title": url,
                    "url": url,
                }
            )

    return {
        "topoguides": topoguides,
        "maps": maps,
        "resources": resources,
    }


def parse_regulation_sections(container: Any) -> dict[str, str | None]:
    sections: dict[str, list[str]] = {"resume": [], "remarque": [], "details": []}
    if container is not None:
        for heading in container.select("h3"):
            key = normalize_label(heading.get_text(" ", strip=True))
            if key not in sections:
                continue
            collected_lines: list[str] = []
            sibling = heading.next_sibling
            while sibling is not None:
                if getattr(sibling, "name", None) == "h3":
                    break
                if hasattr(sibling, "select_one") and sibling.select_one("h3") is not None:
                    break
                if hasattr(sibling, "name"):
                    collected_lines.extend(element_lines(sibling))
                else:
                    collected_lines.extend(split_lines(str(sibling)))
                sibling = sibling.next_sibling
            sections[key].extend(collected_lines)

    effective_date = None
    detail_lines: list[str] = []
    for line in sections["details"]:
        normalized = normalize_label(line)
        if normalized.startswith("date de mise en place") or normalized.startswith("date"):
            effective_date = clean_inline_punctuation(line.split(":", 1)[1] if ":" in line else line)
            continue
        if normalized.startswith("telechargez le fichier pdf"):
            continue
        detail_lines.append(line)

    attachments = []
    if container is not None:
        for link in container.select("a[href]"):
            href = link.get("href", "")
            if ".pdf" not in href.lower():
                continue
            attachments.append(
                {
                    "label": clean_text(link.get("title")) or clean_text(link.get_text(" ", strip=True)),
                    "url": absolute_url(href),
                }
            )

    return {
        "summary": clean_multiline_text("\n".join(sections["resume"])),
        "remark": clean_multiline_text("\n".join(sections["remarque"])),
        "details": clean_multiline_text("\n".join(detail_lines)),
        "effectiveDate": effective_date,
        "attachments": attachments,
    }


def parse_regulation_text_page(regulation_id: int, html: str, text_url: str) -> dict[str, Any]:
    soup = BeautifulSoup(html, "html.parser")
    table = soup.select_one("table#listedebit")
    if table is None:
        raise ValueError(f"Regulation table missing for {text_url}")

    main_row = None
    for row in table.select("tbody > tr"):
        link = row.select_one(f"a[href*='/reglemtexte/{regulation_id}/']")
        if link is None and row.get("id"):
            continue
        cells = row.select("td")
        if len(cells) >= 4:
            main_row = row
            break

    detail_row = table.select_one(f"tr#tr{regulation_id}")
    if detail_row is None:
        rows = table.select("tbody > tr")
        detail_row = rows[1] if len(rows) > 1 else None

    title = clean_text(soup.select_one("h1").get_text(" ", strip=True) if soup.select_one("h1") else None)
    status = None
    action = None
    if main_row is not None:
        cells = main_row.select("td")
        status = clean_text(cells[0].get_text(" ", strip=True)) if len(cells) > 0 else None
        action = clean_text(cells[1].get_text(" ", strip=True)) if len(cells) > 1 else None
        if not title and len(cells) > 3:
            title = clean_text(cells[3].get_text(" ", strip=True))

    parsed_sections = parse_regulation_sections(detail_row.select_one("td") if detail_row is not None else None)
    return {
        "id": regulation_id,
        "status": status,
        "action": action,
        "title": title,
        "summary": parsed_sections["summary"],
        "remark": parsed_sections["remark"],
        "details": parsed_sections["details"],
        "effectiveDate": parsed_sections["effectiveDate"],
        "textUrl": text_url,
        "attachments": parsed_sections["attachments"],
    }


def parse_canyon_regulation_page(canyon_id: int, html: str | None) -> dict[str, Any]:
    if not html:
        return {"hasSpecificRegulation": False, "rules": []}

    soup = BeautifulSoup(html, "html.parser")
    table = soup.select_one("table#listedebit")
    if table is None:
        return {"hasSpecificRegulation": False, "rules": []}

    rules: list[dict[str, Any]] = []
    seen_ids: set[int] = set()
    for row in table.select("tbody > tr"):
        link = row.select_one("td a[href*='/canyoning/reglementation/reglemtexte/']")
        if link is None:
            continue
        match = REGULATION_ID_REGEX.search(link.get("href", ""))
        if match is None:
            continue
        regulation_id = int(match.group(1))
        if regulation_id in seen_ids:
            continue
        seen_ids.add(regulation_id)

        text_url = absolute_url(link.get("href"))
        detail_row = table.select_one(f"tr#tr{regulation_id}")
        local_sections = parse_regulation_sections(detail_row.select_one("td") if detail_row is not None else None)
        rule = REGULATION_CACHE.get(regulation_id)
        if rule is None and text_url is not None:
            rule = parse_regulation_text_page(regulation_id, fetch_text(text_url), text_url)
            REGULATION_CACHE[regulation_id] = rule
        if rule is not None:
            merged_rule = dict(rule)
            if merged_rule.get("summary") is None:
                merged_rule["summary"] = local_sections["summary"]
            if merged_rule.get("remark") is None:
                merged_rule["remark"] = local_sections["remark"]
            if merged_rule.get("details") is None:
                merged_rule["details"] = local_sections["details"]
            if merged_rule.get("effectiveDate") is None:
                merged_rule["effectiveDate"] = local_sections["effectiveDate"]
            if not merged_rule.get("attachments"):
                merged_rule["attachments"] = local_sections["attachments"]
            rules.append(merged_rule)

    return {
        "hasSpecificRegulation": bool(rules),
        "isForbidden": False,
        "rules": rules,
    }


def scrape_canyon(canyon_id: int) -> dict[str, Any]:
    summary_url = f"{BASE_URL}/canyoning/canyon/{canyon_id}/"
    description_url = f"{BASE_URL}/canyoning/canyon-description/{canyon_id}/topo.html"
    map_url = f"{BASE_URL}/canyoning/canyon-carte/{canyon_id}/carte.html"
    bibliography_url = f"{BASE_URL}/canyoning/canyon-bibliographie/{canyon_id}/guides-et-cartes.html"

    summary_html = fetch_text(summary_url)
    summary = parse_summary_page(canyon_id, summary_html)
    description = parse_description_page(canyon_id, fetch_text(description_url))
    map_data = parse_map_page(canyon_id, fetch_text(map_url))
    bibliography = parse_bibliography_page(fetch_optional_text(bibliography_url))
    regulation = parse_canyon_regulation_page(canyon_id, fetch_optional_text(summary["regulationUrl"]) if summary["regulationUrl"] else None)

    return enrich_canyon_flags({
        "schemaVersion": 2,
        "scrapedAt": datetime.now(timezone.utc).isoformat(),
        "source": {
            "site": "descente-canyon.com",
            "canyonId": canyon_id,
            "urls": {
                "summary": summary_url,
                "description": description_url,
                "map": map_url,
                "bibliography": bibliography_url,
                "regulation": summary["regulationUrl"],
            },
        },
        "canyon": {
            "id": canyon_id,
            "identity": {
                "nom": summary["nom"],
                "nomComplet": summary["nomComplet"],
                "prefixeNom": summary["prefixeNom"],
                "url": summary["url"],
            },
            "location": {
                "pays": summary["pays"],
                "region": summary["region"],
                "departement": summary["departement"],
                "communePrincipale": summary["communePrincipale"],
                "communes": summary["communes"],
                "massif": summary["massif"],
                "bassin": summary["bassin"],
                "coursEau": summary["coursEau"],
            },
            "rating": {
                "cotation": summary["cotation"],
                "interet": summary["interet"],
                "nbVotes": summary["nbVotes"],
            },
            "metrics": {
                "altitudeDepartM": summary["altitudeDepartM"],
                "deniveleM": summary["deniveleM"],
                "longueurM": summary["longueurM"],
                "cascadeMaxM": summary["cascadeMaxM"],
                "cordeMiniM": summary["cordeMiniM"],
                "navette": summary["navette"],
            },
            "timings": {
                "approche": summary["tempsApproche"],
                "descente": summary["tempsDescente"],
                "retour": summary["tempsRetour"],
            },
            "topo": {
                "accesAval": description["accesAval"],
                "accesAmont": description["accesAmont"],
                "approche": description["approche"],
                "descente": description["descente"],
                "retour": description["retour"],
                "engagement": description["engagement"],
                "periode": description["periode"],
                "geologie": description["geologie"],
                "historique": description["historique"],
                "remarques": description["remarques"],
            },
            "geoPoints": map_data["geoPoints"],
            "representativePoint": map_data["representativePoint"],
            "bibliography": bibliography,
            "reglementation": regulation,
        },
    })


def build_index(canyons: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "canyons": [
            {
                "id": item["canyon"]["id"],
                "nom": item["canyon"]["identity"]["nom"],
                "pays": item["canyon"]["location"]["pays"],
                "departement": item["canyon"]["location"]["departement"],
                "commune": item["canyon"]["location"]["communePrincipale"],
                "massif": item["canyon"]["location"]["massif"],
                "bassin": item["canyon"]["location"]["bassin"],
                "coursEau": item["canyon"]["location"]["coursEau"],
                "cotation": item["canyon"]["rating"]["cotation"],
                "interet": item["canyon"]["rating"]["interet"],
                "url": item["canyon"]["identity"]["url"],
                "hasSpecificRegulation": item["canyon"]["reglementation"]["hasSpecificRegulation"],
                "isForbidden": item["canyon"]["reglementation"].get("isForbidden", False),
                "representativePoint": item["canyon"]["representativePoint"],
            }
            for item in canyons
        ],
    }


def build_optimized_records(canyons: list[dict[str, Any]]) -> dict[str, Any]:
    canyon_records: list[dict[str, Any]] = []
    geo_points: list[dict[str, Any]] = []
    bibliography_entries: dict[str, dict[str, Any]] = {}
    canyon_bibliography_links: list[dict[str, Any]] = []
    regulation_entries: dict[int, dict[str, Any]] = {}
    canyon_regulation_links: list[dict[str, Any]] = []

    for item in canyons:
        canyon = item["canyon"]
        canyon_id = canyon["id"]
        canyon_records.append(
            {
                "id": canyon_id,
                "identity": canyon["identity"],
                "location": canyon["location"],
                "rating": canyon["rating"],
                "metrics": canyon["metrics"],
                "timings": canyon["timings"],
                "topo": canyon["topo"],
                "representativePoint": canyon["representativePoint"],
                "hasSpecificRegulation": canyon["reglementation"]["hasSpecificRegulation"],
                "isForbidden": canyon["reglementation"].get("isForbidden", False),
            }
        )

        for point in canyon["geoPoints"]:
            geo_points.append(
                {
                    "canyonId": canyon_id,
                    "type": point["type"],
                    "rawType": point["rawType"],
                    "latitude": point["latitude"],
                    "longitude": point["longitude"],
                    "label": point["label"],
                }
            )

        bibliography = canyon["bibliography"]
        for entry in bibliography["topoguides"] + bibliography["maps"] + bibliography["resources"]:
            bibliography_entries[entry["id"]] = entry
            canyon_bibliography_links.append({"canyonId": canyon_id, "bibliographyId": entry["id"]})

        for rule in canyon["reglementation"]["rules"]:
            regulation_entries[rule["id"]] = rule
            canyon_regulation_links.append({"canyonId": canyon_id, "regulationId": rule["id"]})

    return {
        "searchIndex": build_index(canyons),
        "canyons": canyon_records,
        "geoPoints": geo_points,
        "bibliographyEntries": sorted(bibliography_entries.values(), key=lambda entry: entry["id"]),
        "canyonBibliographyLinks": sorted(canyon_bibliography_links, key=lambda link: (link["canyonId"], link["bibliographyId"])),
        "regulationTexts": sorted(regulation_entries.values(), key=lambda rule: rule["id"]),
        "canyonRegulations": sorted(canyon_regulation_links, key=lambda link: (link["canyonId"], link["regulationId"])),
    }


def build_room_import_records(canyons: list[dict[str, Any]]) -> dict[str, Any]:
    scraped_at_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    canyon_rows: list[dict[str, Any]] = []
    geo_point_rows: list[dict[str, Any]] = []

    bibliography_entries: dict[str, dict[str, Any]] = {}
    canyon_bibliography_links: list[dict[str, Any]] = []
    regulation_entries: dict[int, dict[str, Any]] = {}
    canyon_regulation_links: list[dict[str, Any]] = []

    for item in canyons:
        canyon = item["canyon"]
        canyon_id = canyon["id"]
        canyon_rows.append(
            {
                "id": canyon_id,
                "nom": canyon["identity"]["nom"],
                "nomComplet": canyon["identity"]["nomComplet"],
                "pays": canyon["location"]["pays"],
                "region": canyon["location"]["region"],
                "departement": canyon["location"]["departement"],
                "commune": canyon["location"]["communePrincipale"] or "",
                "communes": canyon["location"]["communes"],
                "massif": canyon["location"]["massif"],
                "bassin": canyon["location"]["bassin"],
                "coursEau": canyon["location"]["coursEau"],
                "cotation": canyon["rating"]["cotation"],
                "altitudeDepart": canyon["metrics"]["altitudeDepartM"],
                "denivele": canyon["metrics"]["deniveleM"],
                "longueur": canyon["metrics"]["longueurM"],
                "cascadeMax": canyon["metrics"]["cascadeMaxM"],
                "cordeMin": canyon["metrics"]["cordeMiniM"],
                "tempsApproche": canyon["timings"]["approche"],
                "tempsDescente": canyon["timings"]["descente"],
                "tempsRetour": canyon["timings"]["retour"],
                "navette": canyon["metrics"]["navette"],
                "interet": canyon["rating"]["interet"],
                "nbVotes": canyon["rating"]["nbVotes"],
                "url": canyon["identity"]["url"],
                "accesAval": canyon["topo"]["accesAval"],
                "accesAmont": canyon["topo"]["accesAmont"],
                "approche": canyon["topo"]["approche"],
                "descente": canyon["topo"]["descente"],
                "retour": canyon["topo"]["retour"],
                "engagement": canyon["topo"]["engagement"],
                "periode": canyon["topo"]["periode"],
                "geologie": canyon["topo"]["geologie"],
                "historique": canyon["topo"]["historique"],
                "remarques": canyon["topo"]["remarques"],
                "isOffline": True,
                "isFavorite": False,
                "lastUpdated": scraped_at_ms,
                "hasSpecificRegulation": canyon["reglementation"]["hasSpecificRegulation"],
                "isForbidden": canyon["reglementation"].get("isForbidden", False),
            }
        )

        for point in canyon["geoPoints"]:
            geo_point_rows.append(
                {
                    "canyonId": canyon_id,
                    "type": point["type"],
                    "latitude": point["latitude"],
                    "longitude": point["longitude"],
                    "label": point["label"],
                }
            )

        bibliography = canyon["bibliography"]
        for entry in bibliography["topoguides"] + bibliography["maps"] + bibliography["resources"]:
            bibliography_entries[entry["id"]] = entry
            canyon_bibliography_links.append({"canyonId": canyon_id, "bibliographyId": entry["id"]})

        for rule in canyon["reglementation"]["rules"]:
            regulation_entries[rule["id"]] = rule
            canyon_regulation_links.append({"canyonId": canyon_id, "regulationId": rule["id"]})

    return {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "tables": {
            "canyons": canyon_rows,
            "geo_points": geo_point_rows,
            "bibliography_entries": sorted(bibliography_entries.values(), key=lambda entry: entry["id"]),
            "canyon_bibliography": sorted(canyon_bibliography_links, key=lambda link: (link["canyonId"], link["bibliographyId"])),
            "regulation_texts": sorted(regulation_entries.values(), key=lambda rule: rule["id"]),
            "canyon_regulations": sorted(canyon_regulation_links, key=lambda link: (link["canyonId"], link["regulationId"])),
        },
    }


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def write_shards(base_dir: Path, prefix: str, items: list[dict[str, Any]], shard_size: int) -> list[dict[str, Any]]:
    shard_paths: list[dict[str, Any]] = []
    if not items:
        write_json(base_dir / f"{prefix}-0001.json", [])
        return [{"path": f"{prefix}-0001.json", "count": 0}]

    for index in range(0, len(items), shard_size):
        shard_index = (index // shard_size) + 1
        shard_items = items[index:index + shard_size]
        file_name = f"{prefix}-{shard_index:04d}.json"
        write_json(base_dir / file_name, shard_items)
        shard_paths.append({"path": file_name, "count": len(shard_items)})
    return shard_paths


def write_optimized_dataset(output_dir: Path, optimized: dict[str, Any], shard_size: int) -> None:
    optimized_dir = output_dir / "optimized"
    shards_dir = optimized_dir / "shards"

    write_json(optimized_dir / "search-index.json", optimized["searchIndex"])
    write_json(optimized_dir / "bibliography-entries.json", optimized["bibliographyEntries"])
    write_json(optimized_dir / "canyon-bibliography-links.json", optimized["canyonBibliographyLinks"])
    write_json(optimized_dir / "regulation-texts.json", optimized["regulationTexts"])
    write_json(optimized_dir / "canyon-regulations.json", optimized["canyonRegulations"])

    canyon_shards = write_shards(shards_dir, "canyon-details", optimized["canyons"], shard_size)
    geo_point_shards = write_shards(shards_dir, "geo-points", optimized["geoPoints"], shard_size)

    manifest = {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "counts": {
            "canyons": len(optimized["canyons"]),
            "geoPoints": len(optimized["geoPoints"]),
            "bibliographyEntries": len(optimized["bibliographyEntries"]),
            "canyonBibliographyLinks": len(optimized["canyonBibliographyLinks"]),
            "regulationTexts": len(optimized["regulationTexts"]),
            "canyonRegulations": len(optimized["canyonRegulations"]),
        },
        "files": {
            "searchIndex": "search-index.json",
            "bibliographyEntries": "bibliography-entries.json",
            "canyonBibliographyLinks": "canyon-bibliography-links.json",
            "regulationTexts": "regulation-texts.json",
            "canyonRegulations": "canyon-regulations.json",
            "canyonDetailShards": canyon_shards,
            "geoPointShards": geo_point_shards,
        },
    }
    write_json(optimized_dir / "manifest.json", manifest)


def write_room_import_dataset(output_dir: Path, room_import: dict[str, Any]) -> None:
    room_dir = output_dir / "room-import"
    manifest_path = room_dir / "manifest.json"
    existing_manifest: dict[str, Any] = {}
    if manifest_path.exists():
        try:
            existing_manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            existing_manifest = {}

    core_tables = {
        table_name: f"{table_name}.json"
        for table_name in room_import["tables"].keys()
    }
    core_counts = {
        table_name: len(rows)
        for table_name, rows in room_import["tables"].items()
    }
    tables = dict(existing_manifest.get("tables", {}))
    tables.update(core_tables)
    counts = dict(existing_manifest.get("counts", {}))
    counts.update(core_counts)
    versions = dict(existing_manifest.get("versions", {}))
    versions["overlays"] = room_import["generatedAt"]

    write_json(room_dir / "manifest.json", {
        "schemaVersion": max(int(existing_manifest.get("schemaVersion", 0)), int(room_import["schemaVersion"])),
        "generatedAt": room_import["generatedAt"],
        "tables": tables,
        "counts": counts,
        "versions": versions,
    })
    for table_name, rows in room_import["tables"].items():
        write_json(room_dir / f"{table_name}.json", rows)


def load_canyons_from_output(output_dir: Path) -> list[dict[str, Any]]:
    canyon_dir = output_dir / "canyons"
    if not canyon_dir.exists():
        return []
    canyons: list[dict[str, Any]] = []
    for path in sorted(canyon_dir.glob("*.json"), key=lambda item: int(item.stem)):
        canyons.append(enrich_canyon_flags(json.loads(path.read_text(encoding="utf-8"))))
    return canyons


def scrape_canyon_to_disk(canyon_id: int, output_dir: Path, resume: bool = True) -> dict[str, Any]:
    canyon_path = output_dir / "canyons" / f"{canyon_id}.json"
    if resume and canyon_path.exists():
        return {"id": canyon_id, "status": "skipped", "path": str(canyon_path)}

    canyon = scrape_canyon(canyon_id)
    write_json(canyon_path, canyon)
    return {"id": canyon_id, "status": "scraped", "path": str(canyon_path)}


def scrape_many(
    canyon_ids: list[int],
    output_dir: Path,
    shard_size: int,
    workers: int,
    resume: bool,
) -> dict[str, Any]:
    output_dir.mkdir(parents=True, exist_ok=True)
    results: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []

    print(f"Scraping {len(canyon_ids)} canyons...", file=sys.stderr)
    if workers <= 1:
        for index, canyon_id in enumerate(canyon_ids, start=1):
            try:
                result = scrape_canyon_to_disk(canyon_id, output_dir, resume=resume)
                results.append(result)
            except Exception as exc:  # noqa: BLE001
                failures.append({"id": canyon_id, "error": repr(exc)})
            if index % 25 == 0 or index == len(canyon_ids):
                print(f"Progress {index}/{len(canyon_ids)} | failures={len(failures)}", file=sys.stderr)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            future_map = {
                executor.submit(scrape_canyon_to_disk, canyon_id, output_dir, resume): canyon_id
                for canyon_id in canyon_ids
            }
            completed = 0
            for future in concurrent.futures.as_completed(future_map):
                canyon_id = future_map[future]
                completed += 1
                try:
                    results.append(future.result())
                except Exception as exc:  # noqa: BLE001
                    failures.append({"id": canyon_id, "error": repr(exc)})
                if completed % 25 == 0 or completed == len(canyon_ids):
                    print(f"Progress {completed}/{len(canyon_ids)} | failures={len(failures)}", file=sys.stderr)

    canyons = load_canyons_from_output(output_dir)
    write_json(output_dir / "index.json", build_index(canyons))
    optimized = build_optimized_records(canyons)
    write_optimized_dataset(output_dir, optimized, shard_size)
    write_room_import_dataset(output_dir, build_room_import_records(canyons))

    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "requestedCount": len(canyon_ids),
        "availableCount": len(canyons),
        "scrapedCount": sum(1 for result in results if result["status"] == "scraped"),
        "skippedCount": sum(1 for result in results if result["status"] == "skipped"),
        "failureCount": len(failures),
        "failures": sorted(failures, key=lambda item: item["id"]),
    }
    write_json(output_dir / "scrape-report.json", report)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description="Scrape structured descente-canyon sample data")
    parser.add_argument("canyon_ids", nargs="*", type=int)
    parser.add_argument("--all", action="store_true", dest="scrape_all")
    parser.add_argument("--id-source", choices=("all", "map", "locations"), default="all")
    parser.add_argument("--output-dir", default="offline-data/samples")
    parser.add_argument("--shard-size", type=int, default=250)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--request-delay-ms", type=int, default=0)
    parser.add_argument("--no-resume", action="store_true")
    args = parser.parse_args()

    global REQUEST_DELAY_SECONDS
    REQUEST_DELAY_SECONDS = max(args.request_delay_ms, 0) / 1000.0

    output_dir = Path(args.output_dir)
    if args.scrape_all:
        if args.id_source == "map":
            canyon_ids = fetch_map_canyon_ids()
        elif args.id_source == "locations":
            canyon_ids = fetch_location_canyon_ids()
        else:
            canyon_ids = fetch_all_canyon_ids()
    else:
        canyon_ids = args.canyon_ids

    if not canyon_ids:
        parser.error("Provide canyon ids or use --all")

    report = scrape_many(
        canyon_ids=canyon_ids,
        output_dir=output_dir,
        shard_size=args.shard_size,
        workers=max(args.workers, 1),
        resume=not args.no_resume,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2), file=sys.stderr)


if __name__ == "__main__":
    main()
