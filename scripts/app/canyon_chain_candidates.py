#!/usr/bin/env python3
"""Lists the pairs of canyons that may follow each other in the stream, for a review by hand.

A pair is a candidate when one of these signals links two canyons of the scraped catalog:
- the exit of one is near the entry of the other (GPS points of the sheets);
- they are parts of one canyon by their names ("Pissarde (partie amont)", "Balcez [Oscuros del]");
- the topo of one names the other in a sentence about chaining, or a point of one is named after the other.

The reviewed decisions live in data-overlay/canyon-chains.json (pairs kept, and the dismissed ones);
the candidates already decided there are left out unless --all is given. The output is a JSON list of review cards (both sheets' times,
points and the sentences of their topos that matter) to read before deciding.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CANYONS_DIR = REPO_ROOT / "offline-data" / "full" / "canyons"
DEFAULT_CHAINS_FILE = REPO_ROOT / "data-overlay" / "canyon-chains.json"

EXIT_ENTRY_MAX_M = 1000.0
WEAK_POINT_MAX_M = 500.0
NAME_MAX_KM = 12.0
TEXT_MAX_KM = 15.0

PART_WORDS = {
    "amont", "aval", "superieur", "superieure", "inferieur", "inferieure", "sup", "inf", "median", "mediane", "med",
    "intermediaire", "intermedaire", "haut", "haute", "bas", "basse", "partie", "integrale", "integral", "final",
    "finale", "moyen", "superior", "inferior", "superiore", "inferiore", "medio", "alto", "basso", "obere", "untere",
    "undere", "i", "ii", "iii", "iv", "1", "2", "3", "4", "1ere", "2eme", "3eme", "pre", "troncon", "et", "del",
    "de", "la", "le", "les", "des", "du", "d", "l", "dels", "los", "las", "el",
}
GENERIC_WORDS = {
    "canyon", "canyons", "barranco", "barranc", "ravin", "ravine", "torrent", "riu", "rio", "rivo", "gorges", "gorge",
    "ruisseau", "riou", "vallon", "vallone", "cascade", "cascades", "klamm", "bach", "rau", "riviere", "valat", "ru",
    "fiume", "torrente", "forra", "orrido", "rii", "ri", "grand", "petit", "gros", "font", "fontaine", "source",
    "saint", "st", "sainte", "notre", "dame", "san", "santa", "val", "mont", "pont", "lac", "col", "pic",
}
CHAIN_WORDS = re.compile(
    r"enchain|enchaîn|integral|intégral|suite|suivre|precede|précéd|partie|continu|poursui|interdit|obligatoire|"
    r"rejoindre|rejoint|confluen|affluent|sortie|sortir|départ|depart",
    re.IGNORECASE,
)
TEXT_FIELDS = ("approche", "descente", "retour", "remarques", "accesAmont", "accesAval", "engagement")
# Said of a canyon named in the same sentence: done with this one, before or after it, as one stream.
CHAINING = re.compile(
    r"enchain|integral|a la suite|faire suivre|suivie? (de|par)|preced|confluen|"
    r"(continu\w*|poursui\w*) (dans|par|jusqu|vers)|"
    r"partie (amont|aval|haute|basse|inferieure|superieure|suivante|precedente|mediane)"
)
# Beyond this gap between the end of one canyon (start altitude less drop) and the start of the next, they do not meet.
ALTITUDE_GAP_MAX_M = 80


def normalize(value: str | None) -> str:
    decomposed = unicodedata.normalize("NFD", value or "")
    plain = "".join(char for char in decomposed if unicodedata.category(char) != "Mn").lower()
    return re.sub(r"[^a-z0-9]+", " ", plain).strip()


def stem(name: str) -> str:
    """The canyon of which a sheet may be a part: qualifiers and part words left out."""
    base = re.sub(r"[\(\[].*?[\)\]]", " ", name)
    words = [word for word in normalize(base).split() if word not in PART_WORDS]
    return " ".join(words)


def distinctive(stem_value: str) -> str | None:
    """A name worth looking for in another topo: not only generic words, at least five letters."""
    words = [word for word in stem_value.split() if word not in GENERIC_WORDS]
    if not words or len(" ".join(words)) < 5:
        return None
    return stem_value


def distance_m(a: dict | None, b: dict | None) -> float | None:
    if not a or not b:
        return None
    lat1, lon1, lat2, lon2 = map(math.radians, (a["latitude"], a["longitude"], b["latitude"], b["longitude"]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def first_point(canyon: dict, point_type: str) -> dict | None:
    return next((point for point in canyon["geoPoints"] if point["type"] == point_type), None)


def reference_point(canyon: dict) -> dict | None:
    for point_type in ("ENTREE", "PARKING_AMONT", "SORTIE", "PARKING_AVAL"):
        point = first_point(canyon, point_type)
        if point:
            return point
    return canyon.get("representativePoint")


def sentences(text: str | None, needles: list[str]) -> list[str]:
    """The sentences of a topo that name the other canyon or speak of chaining, exits and bans."""
    if not text:
        return []
    found = []
    for sentence in re.split(r"(?<=[.!?])\s+|\n+", text):
        plain = normalize(sentence)
        if any(needle and needle in plain for needle in needles) or CHAIN_WORDS.search(sentence):
            cleaned = sentence.strip()
            if cleaned and cleaned not in found:
                found.append(cleaned[:400])
    return found


def chaining_text(canyon: dict) -> list[str]:
    """The normalized sentences of a topo about chaining, and the names of the points of its map."""
    found = []
    for field in TEXT_FIELDS:
        for sentence in re.split(r"(?<=[.!?])\s+|\n+", canyon["topo"].get(field) or ""):
            plain = normalize(sentence)
            if CHAINING.search(plain):
                found.append(plain)
    found.extend(normalize(point.get("label")) for point in canyon["geoPoints"] if point.get("label"))
    return found


def load_canyons(directory: Path) -> dict[int, dict]:
    canyons = {}
    for path in directory.glob("*.json"):
        canyon = json.loads(path.read_text(encoding="utf-8"))["canyon"]
        canyons[canyon["id"]] = canyon
    return canyons


def load_reviewed(path: Path) -> set[tuple[int, int]]:
    if not path.exists():
        return set()
    payload = json.loads(path.read_text(encoding="utf-8"))
    pairs = [(row["from"], row["to"]) for row in payload.get("pairs", [])] + [tuple(pair) for pair in payload.get("dismissed", [])]
    return {pair for a, b in pairs for pair in ((a, b), (b, a))}


def candidate_pairs(canyons: dict[int, dict]) -> dict[tuple[int, int], set[str]]:
    signals: dict[tuple[int, int], set[str]] = {}

    def add(a: int, b: int, signal: str) -> None:
        if a != b:
            signals.setdefault((a, b), set()).add(signal)

    ids = sorted(canyons)
    references = {cid: reference_point(canyons[cid]) for cid in ids}
    stems = {cid: stem(canyons[cid]["identity"]["nom"]) for cid in ids}
    chaining_sentences = {cid: chaining_text(canyons[cid]) for cid in ids}

    # A coarse grid keeps the comparisons to the canyons around.
    cells: dict[tuple[int, int], list[int]] = {}
    for cid, point in references.items():
        if point:
            cells.setdefault((int(point["latitude"] * 5), int(point["longitude"] * 5)), []).append(cid)

    for a in ids:
        point = references[a]
        if not point:
            continue
        cell = (int(point["latitude"] * 5), int(point["longitude"] * 5))
        around = [cid for dx in (-1, 0, 1) for dy in (-1, 0, 1) for cid in cells.get((cell[0] + dx, cell[1] + dy), [])]
        canyon_a = canyons[a]
        exit_a = first_point(canyon_a, "SORTIE")
        end_a = exit_a or first_point(canyon_a, "PARKING_AVAL")
        for b in around:
            if b == a:
                continue
            canyon_b = canyons[b]
            entry_b = first_point(canyon_b, "ENTREE")
            start_b = entry_b or first_point(canyon_b, "PARKING_AMONT")
            exit_entry = distance_m(exit_a, entry_b)
            if exit_entry is not None and exit_entry <= EXIT_ENTRY_MAX_M:
                add(a, b, "gps")
            elif exit_entry is None:
                weak = distance_m(end_a, start_b)
                if weak is not None and weak <= WEAK_POINT_MAX_M:
                    add(a, b, "gps-weak")
            apart_km = (distance_m(references[a], references[b]) or math.inf) / 1000
            integral = "integral" in normalize(canyon_a["identity"]["nom"] + " " + canyon_b["identity"]["nom"])
            if stems[a] and stems[a] == stems[b] and apart_km <= NAME_MAX_KM and not integral:
                add(a, b, "name")
            name_b = distinctive(stems[b])
            if name_b and apart_km <= TEXT_MAX_KM and any(
                re.search(rf"\b{re.escape(name_b)}\b", sentence) for sentence in chaining_sentences[a]
            ):
                add(a, b, "topo")
                add(b, a, "topo")
    return signals


def altitude_gap(upstream: dict, downstream: dict) -> int | None:
    """How far the start of [downstream] is from the end of [upstream] (its start less its drop), in metres."""
    start = upstream["metrics"].get("altitudeDepartM")
    drop = upstream["metrics"].get("deniveleM")
    next_start = downstream["metrics"].get("altitudeDepartM")
    if start is None or drop is None or next_start is None:
        return None
    return next_start - (start - drop)


def card(canyons: dict[int, dict], a: int, b: int, signals: set[str]) -> dict:
    canyon_a, canyon_b = canyons[a], canyons[b]
    names = [stem(canyon_a["identity"]["nom"]), stem(canyon_b["identity"]["nom"])]

    def sheet(canyon: dict, other: str) -> dict:
        return {
            "id": canyon["id"],
            "nom": canyon["identity"]["nom"],
            "altitude": canyon["metrics"].get("altitudeDepartM"),
            "denivele": canyon["metrics"].get("deniveleM"),
            "temps": [canyon["timings"].get(key) for key in ("approche", "descente", "retour")],
            "navette": canyon["metrics"].get("navette"),
            "points": [
                [point["type"], round(point["latitude"], 6), round(point["longitude"], 6), point.get("label")]
                for point in canyon["geoPoints"]
                if point["type"] in ("ENTREE", "SORTIE", "PARKING_AMONT", "PARKING_AVAL")
            ],
            "topo": {
                field: found
                for field in TEXT_FIELDS
                if (found := sentences(canyon["topo"].get(field), [other]))
            },
        }

    exit_entry = distance_m(first_point(canyon_a, "SORTIE"), first_point(canyon_b, "ENTREE"))
    return {
        "from": a,
        "to": b,
        "signals": sorted(signals),
        "exitToEntryM": None if exit_entry is None else round(exit_entry),
        "a": sheet(canyon_a, names[1]),
        "b": sheet(canyon_b, names[0]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--canyons-dir", type=Path, default=DEFAULT_CANYONS_DIR)
    parser.add_argument("--chains", type=Path, default=DEFAULT_CHAINS_FILE)
    parser.add_argument("--all", action="store_true", help="also list the pairs already reviewed")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    canyons = load_canyons(args.canyons_dir)
    reviewed = set() if args.all else load_reviewed(args.chains)
    signals = candidate_pairs(canyons)

    # One card per pair of canyons, the upstream one first when the points or the altitudes tell it.
    cards = []
    seen = set()
    for (a, b), found in sorted(signals.items()):
        key = (min(a, b), max(a, b))
        if key in seen or (a, b) in reviewed:
            continue
        seen.add(key)
        back = signals.get((b, a), set())
        forward = bool(found & {"gps", "gps-weak"})
        backward = bool(back & {"gps", "gps-weak"})
        alt_a = canyons[a]["metrics"].get("altitudeDepartM")
        alt_b = canyons[b]["metrics"].get("altitudeDepartM")
        if (backward and not forward) or (forward == backward and alt_a and alt_b and alt_b > alt_a):
            a, b = b, a
        both = found | back
        item = card(canyons, a, b, both)
        gap = altitude_gap(canyons[a], canyons[b])
        item["altitudeGapM"] = gap
        uphill = (
            canyons[a]["metrics"].get("altitudeDepartM") is not None
            and canyons[b]["metrics"].get("altitudeDepartM") is not None
            and canyons[b]["metrics"]["altitudeDepartM"] >= canyons[a]["metrics"]["altitudeDepartM"]
        )
        textual = bool(both & {"name", "topo"})
        close = item["exitToEntryM"] is not None and item["exitToEntryM"] <= 300
        # The next canyon cannot start above the previous one, nor far below its end, unless a name or a topo says so.
        if not textual and (uphill or (not close and gap is not None and abs(gap) > ALTITUDE_GAP_MAX_M)):
            continue
        item["tier"] = "A" if close or textual else "B"
        cards.append(item)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(cards, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    counts: dict[str, int] = {}
    for item in cards:
        key = item["tier"] + " " + "+".join(item["signals"])
        counts[key] = counts.get(key, 0) + 1
    print(f"{len(cards)} pairs to review -> {args.output}")
    for key, value in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"- {key}: {value}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
