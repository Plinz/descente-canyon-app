"""The pages a shared link shows in a browser: what was shared, and the way to the app. Plain HTML
and one stylesheet, every value escaped; no script but the one that reads an outing carried in the
link itself, no tracker, no font or image from elsewhere."""
from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date
from html import escape

from . import links
from .i18n import long_date, short_date, text

APP_NAME = "Descente Canyon"
PRIVACY_URL = "https://plinz.github.io/descente-canyon-app/privacy-policy.html"
ICON_PATH = "/static/icon-512.png"
SECURITY_HEADERS = {
    "Content-Security-Policy": (
        "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'self'; connect-src 'self'; "
        "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    ),
    "X-Robots-Tag": "noindex, nofollow",
}


@dataclass(frozen=True)
class PageContext:
    language: str
    public_url: str
    package: str
    is_android: bool

    def play_url(self, link: str | None) -> str:
        return links.play_store_url(self.package, link)

    def t(self, key: str) -> str:
        return text(key, self.language)


def page(
    ctx: PageContext,
    *,
    title: str,
    description: str,
    canonical: str,
    body: str,
    scripts: tuple[str, ...] = (),
) -> str:
    image = ctx.public_url + ICON_PATH
    script_tags = "".join(f'<script src="{escape(src)}" defer></script>' for src in scripts)
    return f"""<!doctype html>
<html lang="{ctx.language}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="color-scheme" content="light dark">
<title>{escape(title)} · {APP_NAME}</title>
<meta name="description" content="{escape(description)}">
<meta property="og:type" content="website">
<meta property="og:site_name" content="{APP_NAME}">
<meta property="og:title" content="{escape(title)}">
<meta property="og:description" content="{escape(description)}">
<meta property="og:url" content="{escape(canonical)}">
<meta property="og:image" content="{escape(image)}">
<meta name="twitter:card" content="summary">
<link rel="canonical" href="{escape(canonical)}">
<link rel="icon" href="{ICON_PATH}">
<link rel="stylesheet" href="/static/site.css">
{script_tags}
</head>
<body>
<header class="brand"><img src="{ICON_PATH}" alt="" width="36" height="36"><span>{APP_NAME}</span></header>
<main class="card">
{body}
</main>
<footer><p>{escape(ctx.t("tagline"))}</p><p><a href="{PRIVACY_URL}">{escape(ctx.t("privacy"))}</a></p></footer>
</body>
</html>
"""


def app_actions(ctx: PageContext, link: str | None) -> str:
    """Open in the app (Android: an intent link, the Play Store when the app is missing) and install it."""
    play = ctx.play_url(link)
    buttons = []
    if ctx.is_android and link:
        intent = links.intent_url(link, ctx.package, play)
        buttons.append(f'<a class="button primary" id="open-app" href="{escape(intent)}">{escape(ctx.t("open_app"))}</a>')
        buttons.append(f'<a class="button" href="{escape(play)}">{escape(ctx.t("install_app"))}</a>')
    else:
        buttons.append(f'<a class="button primary" href="{escape(play)}">{escape(ctx.t("install_app"))}</a>')
    hint = ctx.t("android_app") + (" " + ctx.t("after_install") if link else "")
    return f'<div class="actions">{"".join(buttons)}</div>\n<p class="hint">{escape(hint)}</p>'


def place_line(entry: dict) -> str:
    place = entry.get("place") or {}
    parts = [entry.get("commune") or "", place.get("departement") or "", place.get("pays") or ""]
    seen: list[str] = []
    for part in parts:
        part = part.strip()
        if part and part not in seen:
            seen.append(part)
    return " · ".join(seen)


def canyon_title(entry: dict) -> str:
    cotation = (entry.get("cotation") or "").strip()
    name = entry.get("name") or ""
    return f"{name} ({cotation})" if cotation else name


def canyon_summary(ctx: PageContext, entry: dict, *, heading: str = "h1") -> str:
    """Name, rating, place and ban of a canyon, as the canyon page and the shared outings show it."""
    rows = [f'<{heading}>{escape(entry.get("name") or "")}</{heading}>']
    cotation = (entry.get("cotation") or "").strip()
    if cotation:
        rows.append(f'<p class="rating">{escape(cotation)}</p>')
    place = place_line(entry)
    if place:
        rows.append(f'<p class="place">{escape(place)}</p>')
    if entry.get("forbidden"):
        rows.append(f'<p class="warning">{escape(ctx.t("forbidden"))}</p>')
    return "\n".join(rows)


def canyon_links(ctx: PageContext, entry: dict, point: tuple[float, float] | None = None) -> str:
    items = []
    url = (entry.get("url") or "").strip()
    if url.startswith("https://"):
        items.append(f'<li><a href="{escape(url)}" rel="noopener noreferrer">{escape(ctx.t("site_sheet"))}</a></li>')
    target = point or first_point(entry)
    if target:
        items.append(f'<li><a href="{escape(links.osm_url(*target))}" rel="noopener noreferrer">{escape(ctx.t("map"))}</a></li>')
    return f'<ul class="links">{"".join(items)}</ul>' if items else ""


def first_point(entry: dict) -> tuple[float, float] | None:
    points = entry.get("points") or []
    if not points:
        return None
    latitude, longitude = points[0]
    return float(latitude), float(longitude)


def canyon_page(ctx: PageContext, canyon_id: int, entry: dict, link: str, point: tuple[float, float] | None = None) -> str:
    title = canyon_title(entry)
    description = " · ".join(part for part in (place_line(entry), ctx.t("open_app")) if part)
    point_line = ""
    if point:
        coordinates = f"{point[0]:.5f}, {point[1]:.5f}"
        point_line = f'<p class="detail">{escape(ctx.t("point").format(coordinates=coordinates))}</p>'
    body = "\n".join(
        part
        for part in (
            f'<p class="kicker">{escape(ctx.t("canyon"))}</p>',
            canyon_summary(ctx, entry),
            point_line,
            app_actions(ctx, link),
            canyon_links(ctx, entry, point),
        )
        if part
    )
    canonical = ctx.public_url + links.canyon_path(canyon_id, entry.get("name") or "")
    return page(ctx, title=title, description=description, canonical=canonical, body=body)


def landing_page(ctx: PageContext) -> str:
    body = f"<h1>{APP_NAME}</h1>\n<p>{escape(ctx.t('tagline'))}</p>\n{app_actions(ctx, None)}"
    return page(ctx, title=APP_NAME, description=ctx.t("tagline"), canonical=ctx.public_url + "/", body=body)


def not_found_page(ctx: PageContext, message_key: str = "not_found_text") -> str:
    body = f"<h1>{escape(ctx.t('not_found_title'))}</h1>\n<p>{escape(ctx.t(message_key))}</p>\n{app_actions(ctx, None)}"
    return page(ctx, title=ctx.t("not_found_title"), description=ctx.t(message_key), canonical=ctx.public_url + "/", body=body)


# Shared links: an outing, a route without date, a stay, a list of canyons. The names come from the
# catalog (the ids are checked at the sharing), the times from the sharing phone's own computation.


def _facts(rows: list[tuple[str, str]]) -> str:
    if not rows:
        return ""
    items = "".join(f"<dt>{escape(term)}</dt><dd>{escape(value)}</dd>" for term, value in rows)
    return f'<dl class="facts">{items}</dl>'


def _group(ctx: PageContext, outing: dict) -> str:
    size = outing.get("groupSize")
    if not isinstance(size, int):
        return ""
    text_ = ctx.t("one_person") if size == 1 else ctx.t("people").format(count=size)
    level = outing.get("groupLevel")
    if level == "WITH_BEGINNERS":
        text_ += ", " + ctx.t("with_beginners")
    elif level == "DISCOVERY":
        text_ += ", " + ctx.t("discovery")
    return text_


def _outing_facts(ctx: PageContext, outing: dict, meet_place: str | None) -> list[tuple[str, str]]:
    summary = outing.get("summary") or {}
    rows: list[tuple[str, str]] = []
    meet = summary.get("meet") or outing.get("anchorTime")
    if meet:
        if outing.get("meetAtPlace") and meet_place:
            rows.append((ctx.t("meet"), ctx.t("meet_at_place").format(time=meet, place=meet_place)))
        else:
            rows.append((ctx.t("meet"), ctx.t("meet_at_parking").format(time=meet)))
    if summary.get("walk"):
        rows.append((ctx.t("walk_start"), summary["walk"]))
    if summary.get("back"):
        back = summary["back"]
        if summary.get("backLatest") and summary["backLatest"] != back:
            back = ctx.t("back_range").format(time=back, latest=summary["backLatest"])
        rows.append((ctx.t("back"), back))
    group = _group(ctx, outing)
    if group:
        rows.append((ctx.t("group"), group))
    if outing.get("withShuttle"):
        rows.append((ctx.t("shuttle"), ctx.t("yes")))
    return rows


def _canyon_ids(outing: dict) -> list[int]:
    ids = [outing.get("canyonId")] + [canyon.get("canyonId") for canyon in outing.get("next") or [] if isinstance(canyon, dict)]
    return [canyon_id for canyon_id in ids if isinstance(canyon_id, int)]


def _canyon_blocks(ctx: PageContext, ids: list[int], entries: dict[int, dict]) -> str:
    blocks = []
    for canyon_id in ids:
        entry = entries.get(canyon_id)
        if entry is None:
            continue
        path = links.canyon_path(canyon_id, entry.get("name") or "")
        summary = canyon_summary(ctx, entry, heading="h2").replace(
            f"<h2>{escape(entry.get('name') or '')}</h2>",
            f'<h2><a href="{escape(path)}">{escape(entry.get("name") or "")}</a></h2>',
            1,
        )
        blocks.append(f'<section class="canyon-row">{summary}{canyon_links(ctx, entry)}</section>')
    return "\n".join(blocks)


def _title_of(ids: list[int], entries: dict[int, dict], fallback: list[str]) -> str:
    names = [entries[canyon_id].get("name") or "" for canyon_id in ids if canyon_id in entries]
    return " + ".join(name for name in names if name) or " + ".join(fallback)


def outing_body(ctx: PageContext, outing: dict, entries: dict[int, dict], link: str, *, extra_hint: str = "") -> tuple[str, str, str]:
    """The page body, title and description of a shared outing."""
    ids = _canyon_ids(outing)
    title = _title_of(ids, entries, (outing.get("summary") or {}).get("names") or [])
    day = outing.get("date")
    kicker = ctx.t("shared_outing") if day else ctx.t("shared_route")
    place = (outing.get("place") or {}).get("name")
    rows = []
    if day:
        rows.append(f'<p class="rating">{escape(long_date(date.fromisoformat(day), ctx.language))}</p>')
    else:
        rows.append(f'<p class="detail">{escape(ctx.t("no_date"))}</p>')
    note = (outing.get("note") or "").strip()
    body = "\n".join(
        part
        for part in (
            f'<p class="kicker">{escape(kicker)}</p>',
            f"<h1>{escape(title)}</h1>",
            *rows,
            _facts(_outing_facts(ctx, outing, place)),
            f'<p class="note">{escape(note)}</p>' if note else "",
            app_actions(ctx, link),
            f'<p class="hint">{escape(extra_hint)}</p>' if extra_hint else "",
            _canyon_blocks(ctx, ids, entries),
        )
        if part
    )
    description_parts = []
    if day:
        description_parts.append(long_date(date.fromisoformat(day), ctx.language))
    meet = (outing.get("summary") or {}).get("meet") or outing.get("anchorTime")
    if meet:
        description_parts.append(f"{ctx.t('meet')} {meet}")
    return body, f"{kicker} : {title}" if ctx.language == "fr" else f"{kicker}: {title}", " · ".join(description_parts) or kicker


def stay_body(ctx: PageContext, stay: dict, entries: dict[int, dict], link: str) -> tuple[str, str, str]:
    place = stay.get("place") or {}
    start = date.fromisoformat(stay["start"])
    end = date.fromisoformat(stay["end"])
    days = ctx.t("stay_days").format(start=short_date(start, ctx.language), end=short_date(end, ctx.language) + f" {end.year}")
    sections = []
    for outing in sorted(stay.get("outings") or [], key=lambda o: (o.get("date") or "", o.get("anchorTime") or "")):
        ids = _canyon_ids(outing)
        title = _title_of(ids, entries, (outing.get("summary") or {}).get("names") or [])
        day = long_date(date.fromisoformat(outing["date"]), ctx.language)
        sections.append(
            f'<section class="canyon-row"><h2>{escape(day)}</h2><p class="rating">{escape(title)}</p>'
            f"{_facts(_outing_facts(ctx, outing, place.get('name')))}</section>"
        )
    body = "\n".join(
        part
        for part in (
            f'<p class="kicker">{escape(ctx.t("shared_stay"))}</p>',
            f"<h1>{escape(place.get('name') or '')}</h1>",
            f'<p class="rating">{escape(days)}</p>',
            f'<ul class="links"><li><a href="{escape(links.osm_url(float(place["lat"]), float(place["lon"])))}" rel="noopener noreferrer">'
            f"{escape(ctx.t('map'))}</a></li></ul>"
            if place.get("lat") is not None
            else "",
            app_actions(ctx, link),
            *sections,
        )
        if part
    )
    return body, f"{ctx.t('shared_stay')} · {place.get('name') or ''}", days


def list_body(ctx: PageContext, shared: dict, entries: dict[int, dict], link: str) -> tuple[str, str, str]:
    ids = [canyon_id for canyon_id in shared.get("canyonIds") or [] if isinstance(canyon_id, int)]
    title = (shared.get("title") or "").strip() or ctx.t("shared_list")
    count = ctx.t("list_count").format(count=len(ids))
    items = []
    for canyon_id in ids:
        entry = entries.get(canyon_id)
        if entry is None:
            continue
        path = links.canyon_path(canyon_id, entry.get("name") or "")
        place = place_line(entry)
        label = canyon_title(entry)
        items.append(
            f'<li><a href="{escape(path)}">{escape(label)}</a>' + (f' <span class="detail">· {escape(place)}</span>' if place else "") + "</li>"
        )
    body = "\n".join(
        (
            f'<p class="kicker">{escape(ctx.t("shared_list"))}</p>',
            f"<h1>{escape(title)}</h1>",
            f'<p class="detail">{escape(count)}</p>',
            app_actions(ctx, link),
            f'<ul class="items">{"".join(items)}</ul>',
        )
    )
    return body, title, count


def share_page(ctx: PageContext, share: dict, entries: dict[int, dict], link: str) -> str:
    kind = share.get("kind")
    payload = share.get("payload") or {}
    if kind == "OUTING":
        body, title, description = outing_body(ctx, payload, entries, link)
    elif kind == "STAY":
        body, title, description = stay_body(ctx, payload, entries, link)
    else:
        body, title, description = list_body(ctx, payload, entries, link)
    return page(ctx, title=title, description=description, canonical=link, body=body)


def outing_in_link_page(ctx: PageContext, canyon_id: int, entry: dict, link: str) -> str:
    """An outing carried whole by the link (after the #, never sent here): the page shows the canyon,
    and its script reads the outing from the address to show the day, the meeting and the times."""
    labels = {
        "meet": ctx.t("meet"),
        "meetParking": ctx.t("meet_at_parking"),
        "meetPlace": ctx.t("meet_at_place"),
        "walk": ctx.t("walk_start"),
        "back": ctx.t("back"),
        "backRange": ctx.t("back_range"),
        "group": ctx.t("group"),
        "people": ctx.t("people"),
        "onePerson": ctx.t("one_person"),
        "beginners": ctx.t("with_beginners"),
        "discovery": ctx.t("discovery"),
        "shuttle": ctx.t("shuttle"),
        "yes": ctx.t("yes"),
        "noDate": ctx.t("no_date"),
        "lang": ctx.language,
    }
    body = "\n".join(
        (
            f'<p class="kicker">{escape(ctx.t("shared_outing"))}</p>',
            f'<div id="outing" data-labels="{escape(json.dumps(labels, ensure_ascii=False))}" data-package="{escape(ctx.package)}"'
            f' data-base="{escape(link)}" hidden><p class="rating" id="outing-day"></p><dl class="facts" id="outing-facts"></dl>'
            '<p class="note" id="outing-note" hidden></p></div>',
            canyon_summary(ctx, entry),
            app_actions(ctx, link),
            f'<p class="hint">{escape(ctx.t("outing_in_link"))}</p>',
            canyon_links(ctx, entry),
        )
    )
    title = f"{ctx.t('shared_outing')} · {canyon_title(entry)}"
    return page(ctx, title=title, description=place_line(entry), canonical=link, body=body, scripts=("/static/outing.js",))
