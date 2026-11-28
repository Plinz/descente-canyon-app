"""Addresses of the shared links: the paths the app opens, the slug that makes them readable, and the
ways from a page to the app (an intent link on Android, the Play Store with the link as referrer, so
the app opens it after its installation)."""
from __future__ import annotations

import re
import unicodedata
from urllib.parse import quote, urlencode, urlsplit

PLAY_STORE = "https://play.google.com/store/apps/details"
MAX_SLUG = 60
_CANYON_REF = re.compile(r"^(\d{1,9})(?:-[a-z0-9-]{0,80})?$")
CANYON_SECTIONS = ("topo", "conditions", "photos", "debits", "carte")
"""The tabs of the canyon page and its map, which a canyon link can open."""


def slug(name: str) -> str:
    """"Gorges du Riolan (aval)" -> "gorges-du-riolan-aval": readable in a message, ignored by the app."""
    folded = name.lower().replace("œ", "oe").replace("æ", "ae")
    ascii_name = unicodedata.normalize("NFKD", folded).encode("ascii", "ignore").decode("ascii")
    words = re.sub(r"[^a-z0-9]+", "-", ascii_name).strip("-")
    return words[:MAX_SLUG].rstrip("-")


def canyon_path(canyon_id: int, name: str = "") -> str:
    words = slug(name)
    return f"/c/{canyon_id}-{words}" if words else f"/c/{canyon_id}"


def parse_canyon_ref(ref: str) -> int | None:
    """The canyon id of "2895" or "2895-riolan", None for anything else."""
    match = _CANYON_REF.match(ref.lower())
    return int(match.group(1)) if match else None


def play_store_url(package: str, link: str | None = None) -> str:
    """The app's page on Google Play; with [link], the app opens it at its first start (install referrer)."""
    params = {"id": package}
    if link:
        params["referrer"] = urlencode({"dc_link": link})
    return f"{PLAY_STORE}?{urlencode(params)}"


def intent_url(link: str, package: str, fallback: str) -> str:
    """Opens [link] in the installed app from a browser page even when the domain is not verified on
    the phone (Chrome on Android), else [fallback] (the Play Store)."""
    parts = urlsplit(link)
    target = parts.netloc + (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    return f"intent://{target}#Intent;scheme=https;package={package};S.browser_fallback_url={quote(fallback, safe='')};end"


def osm_url(latitude: float, longitude: float) -> str:
    return f"https://www.openstreetmap.org/?mlat={latitude:.5f}&mlon={longitude:.5f}#map=15/{latitude:.5f}/{longitude:.5f}"
