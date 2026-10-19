#!/usr/bin/env python3
"""
Builds forum_users.json: the members of descente-canyon.com (a site account is also the forum
account) found in the debit reports and on the forum.

A debit report signed by a name typed without an account is not a member: the anonymous form asks
for a "groupe", so these names are free text ("Pierre, Paul et Jacques") that no one can be
followed by, and splitting them would invent people. The remark under a report tells the two
apart: a member shows the site counters, an anonymous name shows "Utilisateur non enregistré"
(see parse_report_author_block in scripts/debits/pipeline_lib.py). A name is a member once it has
been seen with an account in one remark, or on the forum.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
import unicodedata
import warnings
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlencode, urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen

from bs4 import BeautifulSoup, FeatureNotFound, XMLParsedAsHTMLWarning


BASE_URL = "https://www.descente-canyon.com"
FORUM_URL = f"{BASE_URL}/forums/"
DEFAULT_FEED_URLS = [
    f"{BASE_URL}/forums/feed",
    f"{BASE_URL}/forums/feed/topics_active",
]
DEFAULT_USER_AGENT = "DescenteCanyonForumUserExtractor/0.2"
SKIPPED_USERNAMES = {
    "",
    "utilisateur non enregistre",
    "utilisateur non inscrit",
    "anonyme",
}
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}")
SEARCH_RESULT_COUNT = re.compile(r"(\d[\d\s .,]*)\s*(?:r[ée]sultats?|results?)", re.IGNORECASE)


@dataclass
class MemberRecord:
    username: str
    forum_user_id: int | None = None
    # Counters the site shows under the member's name (debitReports, forumPosts, photos, ...).
    site_stats: dict[str, int] | None = None
    # Report lines signed with the name, for members whose counters were never seen.
    report_count: int = 0
    last_report_date: str | None = None
    last_report_canyon_id: int | None = None
    last_report_canyon_name: str | None = None
    forum_post_urls: set[str] = field(default_factory=set)
    scanned_forum_post_count: int | None = None
    last_forum_post_date: str | None = None
    last_forum_post_url: str | None = None
    sources: set[str] = field(default_factory=set)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Extract forum_users.json (site members) from debit observations and public forum pages.")
    parser.add_argument("--room-import-dir", type=Path, default=Path("offline-data/full/room-import"))
    parser.add_argument("--output", type=Path)
    parser.add_argument(
        "--debit-observations-path",
        type=Path,
        default=Path("build/debit-pipeline/observations/all_debit_observations.jsonl"),
        help="Output of scripts/debits/build_observation_dataset.py (needs the authorProfiles field)",
    )
    parser.add_argument("--skip-debits", action="store_true")
    parser.add_argument("--skip-forum", action="store_true")
    parser.add_argument("--forum-active-topic-pages", type=int, default=20)
    parser.add_argument("--forum-author-id-min", type=int)
    parser.add_argument("--forum-author-id-max", type=int)
    parser.add_argument("--forum-feed-url", action="append", dest="forum_feed_urls")
    parser.add_argument("--cache-dir", type=Path, default=Path("build/forum-user-extractor/cache"))
    parser.add_argument("--refresh-cache", action="store_true")
    parser.add_argument("--request-delay-ms", type=int, default=250)
    parser.add_argument("--user-agent", default=DEFAULT_USER_AGENT)
    return parser.parse_args()


def clean_text(value: str | None) -> str | None:
    if value is None:
        return None
    value = re.sub(r"\s+", " ", value).strip()
    return value or None


def normalize_username(value: str | None) -> str:
    value = unicodedata.normalize("NFKD", value or "")
    value = "".join(char for char in value if not unicodedata.combining(char))
    value = value.lower()
    value = "".join(char if char.isalnum() else " " for char in value)
    return re.sub(r"\s+", " ", value).strip()


def is_valid_username(username: str | None) -> bool:
    normalized = normalize_username(username)
    return bool(normalized) and normalized not in SKIPPED_USERNAMES


def sanitize_url(value: str | None) -> str | None:
    if not value:
        return None
    # Forum links are relative to /forums/ (./viewtopic.php, memberlist.php).
    absolute = urljoin(FORUM_URL, value)
    parsed = urlparse(absolute)
    query = parse_qs(parsed.query, keep_blank_values=True)
    query.pop("sid", None)
    return urlunparse(parsed._replace(query=urlencode(query, doseq=True)))


def extract_forum_user_id(url: str | None) -> int | None:
    if not url:
        return None
    values = parse_qs(urlparse(url).query).get("u")
    if not values:
        return None
    try:
        return int(values[0])
    except ValueError:
        return None


def iso_date(value: str | None) -> str | None:
    """The day of an ISO date or timestamp; anything else (French text dates) is dropped."""
    if not value or not ISO_DATE.match(value):
        return None
    return value[:10]


def is_newer(candidate: str | None, current: str | None) -> bool:
    if candidate is None:
        return False
    if current is None:
        return True
    return candidate > current


def get_or_create(members: dict[str, MemberRecord], username: str | None) -> MemberRecord | None:
    username = clean_text(username) or ""
    if not is_valid_username(username):
        return None
    # The exact name is the identity: "lolo" and "Lolo_" are two accounts.
    record = members.get(username)
    if record is None:
        record = MemberRecord(username=username)
        members[username] = record
    return record


def cache_path_for_url(cache_dir: Path, url: str) -> Path:
    digest = hashlib.sha256(url.encode("utf-8")).hexdigest()
    return cache_dir / f"{digest}.html"


def fetch_text(
    url: str,
    *,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> str:
    cache_path = cache_path_for_url(cache_dir, url)
    if cache_path.exists() and not refresh_cache:
        return cache_path.read_text(encoding="utf-8")

    if delay_seconds > 0:
        time.sleep(delay_seconds)
    request = Request(url, headers={"User-Agent": user_agent})
    with urlopen(request, timeout=30) as response:
        body = response.read().decode("utf-8", errors="replace")
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(body, encoding="utf-8")
    return body


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            stripped = line.strip()
            if stripped:
                rows.append(json.loads(stripped))
    return rows


def collect_debit_members(members: dict[str, MemberRecord], observations: list[dict[str, Any]]) -> dict[str, int]:
    """Registers the authors seen with an account in a report remark, with the site counters."""
    anonymous_names: set[str] = set()
    profile_count = 0
    for observation in observations:
        for profile in observation.get("authorProfiles") or []:
            profile_count += 1
            if profile.get("registered") is False:
                anonymous_names.add(profile.get("author") or "")
            if profile.get("registered") is not True:
                continue
            record = get_or_create(members, profile.get("author"))
            if record is None:
                continue
            record.sources.add("DEBIT")
            if profile.get("forumUserId") is not None:
                record.forum_user_id = int(profile["forumUserId"])
            stats = profile.get("siteStats")
            # Pages are read over hours: the highest debit count is the most recent snapshot.
            if stats and (record.site_stats is None or stats.get("debitReports", 0) >= record.site_stats.get("debitReports", 0)):
                record.site_stats = dict(stats)
    return {
        "authorProfileCount": profile_count,
        "anonymousNameCount": len(anonymous_names - set(members)),
    }


def collect_previous_members(members: dict[str, MemberRecord], previous_rows: list[dict[str, Any]]) -> int:
    """
    Keeps the members an earlier run proved by their forum account id: an account does not go away,
    while the forum pages read only show who posted lately.
    """
    count = 0
    for row in previous_rows:
        if row.get("forumUserId") is None:
            continue
        record = get_or_create(members, row.get("username"))
        if record is None:
            continue
        if record.forum_user_id is None:
            record.forum_user_id = int(row["forumUserId"])
        record.sources.add("PREVIOUS")
        count += 1
    return count


def attribute_debit_reports(members: dict[str, MemberRecord], observations: list[dict[str, Any]], today: str) -> int:
    """Counts the report lines signed by a member and keeps the latest one (future typos aside)."""
    attributed = 0
    for observation in observations:
        report_date = iso_date(observation.get("date"))
        for author in observation.get("authors") or []:
            record = members.get(clean_text(str(author)) or "")
            if record is None:
                continue
            attributed += 1
            record.report_count += 1
            if report_date is not None and report_date <= today and is_newer(report_date, record.last_report_date):
                record.last_report_date = report_date
                record.last_report_canyon_id = observation.get("canyonId")
                record.last_report_canyon_name = observation.get("canyonName")
    return attributed


def record_forum_post(
    members: dict[str, MemberRecord],
    *,
    username: str,
    forum_user_id: int | None,
    posted_at: str | None = None,
    post_url: str | None = None,
    post_count: int | None = None,
) -> bool:
    # An author with an account id is a member; without one (the feeds give none) the post only
    # completes a member already known.
    if forum_user_id is None:
        record = members.get(clean_text(username) or "")
    else:
        record = get_or_create(members, username)
    if record is None:
        return False
    record.sources.add("FORUM")
    if forum_user_id is not None:
        record.forum_user_id = forum_user_id
    post_url = sanitize_url(post_url)
    if post_url:
        record.forum_post_urls.add(post_url)
    if post_count is not None:
        record.scanned_forum_post_count = post_count
    posted_on = iso_date(posted_at)
    if is_newer(posted_on, record.last_forum_post_date):
        record.last_forum_post_date = posted_on
        record.last_forum_post_url = post_url
    return True


def collect_feed_users(
    members: dict[str, MemberRecord],
    *,
    url: str,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    html = fetch_text(
        url,
        cache_dir=cache_dir,
        refresh_cache=refresh_cache,
        user_agent=user_agent,
        delay_seconds=delay_seconds,
    )
    try:
        soup = BeautifulSoup(html, "xml")
    except FeatureNotFound:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", XMLParsedAsHTMLWarning)
            soup = BeautifulSoup(html, "html.parser")
    count = 0
    for entry in soup.find_all("entry"):
        author = entry.find("author")
        name = clean_text(author.find("name").get_text(" ", strip=True) if author and author.find("name") else None)
        uri = clean_text(author.find("uri").get_text(" ", strip=True) if author and author.find("uri") else None)
        link = entry.find("link")
        post_url = link.get("href") if link is not None else None
        date_node = entry.find("published") or entry.find("updated")
        posted_at = clean_text(date_node.get_text(" ", strip=True) if date_node is not None else None)
        if name and record_forum_post(
            members,
            username=name,
            forum_user_id=extract_forum_user_id(uri),
            posted_at=posted_at,
            post_url=post_url,
        ):
            count += 1
    return count


def collect_active_topic_users(
    members: dict[str, MemberRecord],
    *,
    page_count: int,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    count = 0
    for page_index in range(max(page_count, 0)):
        start = page_index * 25
        url = f"{BASE_URL}/forums/search.php?search_id=active_topics&start={start}"
        html = fetch_text(
            url,
            cache_dir=cache_dir,
            refresh_cache=refresh_cache,
            user_agent=user_agent,
            delay_seconds=delay_seconds,
        )
        soup = BeautifulSoup(html, "html.parser")
        # Only who posted: the dates of a topic row do not say which of its authors they belong to.
        for link in soup.select("a[href*='memberlist.php?mode=viewprofile'][href*='u=']"):
            if record_forum_post(
                members,
                username=clean_text(link.get_text(" ", strip=True)) or "",
                forum_user_id=extract_forum_user_id(link.get("href")),
            ):
                count += 1
    return count


def read_author_search(
    url: str,
    *,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> tuple[Any, int] | None:
    """The forum search of an author's posts: its page and the number of posts it found."""
    html = fetch_text(url, cache_dir=cache_dir, refresh_cache=refresh_cache, user_agent=user_agent, delay_seconds=delay_seconds)
    soup = BeautifulSoup(html, "html.parser")
    title = soup.select_one("h2.searchresults-title") or soup.select_one("h2")
    match = SEARCH_RESULT_COUNT.search(title.get_text(" ", strip=True) if title is not None else "")
    if match is not None:
        return soup, int(re.sub(r"\D", "", match.group(1)))
    posts = soup.select("div.search.post")
    return (soup, len(posts)) if posts else None


def collect_author_id_scan_users(
    members: dict[str, MemberRecord],
    *,
    author_id_min: int,
    author_id_max: int,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    count = 0
    for author_id in range(author_id_min, author_id_max + 1):
        url = f"{BASE_URL}/forums/search.php?author_id={author_id}&sr=posts&sk=t&sd=d"
        try:
            result = read_author_search(url, cache_dir=cache_dir, refresh_cache=refresh_cache, user_agent=user_agent, delay_seconds=delay_seconds)
        except Exception as exc:  # noqa: BLE001
            print(f"author_id={author_id} failed: {exc!r}", file=sys.stderr)
            continue
        if result is None:
            continue
        soup, result_count = result
        profile_link = soup.select_one(f"a[href*='memberlist.php?mode=viewprofile'][href*='u={author_id}']")
        if profile_link is None:
            continue
        first_post = soup.select_one("div.search.post")
        post_link = first_post.select_one("h3 a[href]") if first_post is not None else None
        time_node = first_post.select_one("time[datetime]") if first_post is not None else None
        if record_forum_post(
            members,
            username=clean_text(profile_link.get_text(" ", strip=True)) or "",
            forum_user_id=author_id,
            posted_at=clean_text(time_node.get("datetime") if time_node is not None else None),
            post_url=post_link.get("href") if post_link is not None else None,
            post_count=result_count,
        ):
            count += 1
            if count % 50 == 0:
                print(f"Forum author scan: found={count} latestAuthorId={author_id}", file=sys.stderr)
    return count


def count_forum_posts_without_counters(
    members: dict[str, MemberRecord],
    *,
    cache_dir: Path,
    refresh_cache: bool,
    user_agent: str,
    delay_seconds: float,
) -> int:
    """Members known by the forum only have no site counters: their post count comes from the search."""
    count = 0
    for record in members.values():
        if record.site_stats is not None or record.scanned_forum_post_count is not None or record.forum_user_id is None:
            continue
        url = f"{BASE_URL}/forums/search.php?author_id={record.forum_user_id}&sr=posts&sk=t&sd=d"
        try:
            result = read_author_search(url, cache_dir=cache_dir, refresh_cache=refresh_cache, user_agent=user_agent, delay_seconds=delay_seconds)
        except Exception as exc:  # noqa: BLE001
            print(f"author_id={record.forum_user_id} failed: {exc!r}", file=sys.stderr)
            continue
        if result is not None:
            record.scanned_forum_post_count = result[1]
            count += 1
    return count


def to_output_rows(members: dict[str, MemberRecord], generated_at: str) -> list[dict[str, Any]]:
    rows = []
    for record in members.values():
        stats = record.site_stats or {}
        forum_post_count = max(
            stats.get("forumPosts", 0),
            record.scanned_forum_post_count or 0,
            len(record.forum_post_urls),
        )
        rows.append(
            {
                "username": record.username,
                "normalizedUsername": normalize_username(record.username),
                "forumUserId": record.forum_user_id,
                "debitReportCount": stats["debitReports"] if "debitReports" in stats else record.report_count,
                "forumPostCount": forum_post_count,
                # Unknown, not zero, for a member never seen with the site counters.
                "photoCount": stats.get("photos") if stats else None,
                "canyonSheetContributionCount": stats.get("canyonSheetContributions") if stats else None,
                "lastDebitReportDate": record.last_report_date,
                "lastDebitReportCanyonId": record.last_report_canyon_id,
                "lastDebitReportCanyonName": record.last_report_canyon_name,
                "lastForumPostDate": record.last_forum_post_date,
                "lastForumPostUrl": record.last_forum_post_url,
                "updatedAt": generated_at,
            }
        )
    return sorted(rows, key=lambda item: (item["normalizedUsername"], item["username"]))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def update_manifest(room_import_dir: Path, generated_at: str, user_count: int) -> None:
    manifest_path = room_import_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    tables = dict(manifest.get("tables", {}))
    counts = dict(manifest.get("counts", {}))
    versions = dict(manifest.get("versions", {}))
    tables["forum_users"] = "forum_users.json"
    counts["forum_users"] = user_count
    versions["forum_users"] = generated_at
    manifest.update(
        {
            "schemaVersion": max(int(manifest.get("schemaVersion", 0)), 3),
            "generatedAt": generated_at,
            "tables": tables,
            "counts": counts,
            "versions": versions,
        }
    )
    write_json(manifest_path, manifest)


def main() -> int:
    args = parse_args()
    delay_seconds = max(args.request_delay_ms, 0) / 1000.0
    now = datetime.now(timezone.utc)
    generated_at = now.isoformat()
    output_path = args.output or (args.room_import_dir / "forum_users.json")
    members: dict[str, MemberRecord] = {}
    fetch_options = {
        "cache_dir": args.cache_dir,
        "refresh_cache": args.refresh_cache,
        "user_agent": args.user_agent,
        "delay_seconds": delay_seconds,
    }

    previous_rows = json.loads(output_path.read_text(encoding="utf-8")) if output_path.exists() else []
    previous_member_count = collect_previous_members(members, previous_rows)

    observations: list[dict[str, Any]] = []
    debit_summary: dict[str, int] = {}
    if not args.skip_debits:
        if not args.debit_observations_path.exists():
            print(f"Debit observations missing: {args.debit_observations_path}", file=sys.stderr)
            return 1
        observations = read_jsonl(args.debit_observations_path)
        if not any("authorProfiles" in observation for observation in observations[:1000]):
            print("Debit observations have no authorProfiles: rebuild them with scripts/debits/build_observation_dataset.py", file=sys.stderr)
            return 1
        debit_summary = collect_debit_members(members, observations)
        print(f"Debit observations processed: {len(observations)}", file=sys.stderr)

    forum_event_count = 0
    forum_count_scans = 0
    if not args.skip_forum:
        # The pages giving account ids first: the feeds only complete the members they found.
        forum_event_count += collect_active_topic_users(members, page_count=args.forum_active_topic_pages, **fetch_options)
        if args.forum_author_id_min is not None and args.forum_author_id_max is not None:
            forum_event_count += collect_author_id_scan_users(
                members,
                author_id_min=args.forum_author_id_min,
                author_id_max=args.forum_author_id_max,
                **fetch_options,
            )
        for feed_url in args.forum_feed_urls or DEFAULT_FEED_URLS:
            forum_event_count += collect_feed_users(members, url=feed_url, **fetch_options)
        forum_count_scans = count_forum_posts_without_counters(members, **fetch_options)
        print(f"Forum author events processed: {forum_event_count}", file=sys.stderr)

    attributed_reports = attribute_debit_reports(members, observations, now.date().isoformat())

    rows = to_output_rows(members, generated_at)
    write_json(output_path, rows)
    update_manifest(args.room_import_dir, generated_at, len(rows))

    summary = {
        "generatedAt": generated_at,
        "output": str(output_path),
        "memberCount": len(rows),
        "membersFromDebits": sum(1 for record in members.values() if "DEBIT" in record.sources),
        "membersFromForum": sum(1 for record in members.values() if "FORUM" in record.sources),
        "membersKeptFromPreviousRun": previous_member_count,
        "membersWithForumId": sum(1 for row in rows if row["forumUserId"] is not None),
        "membersWithForumPosts": sum(1 for row in rows if row["forumPostCount"] > 0),
        "debitObservationCount": len(observations),
        "reportLinesAttributedToMembers": attributed_reports,
        "forumEventCount": forum_event_count,
        "forumPostCountScans": forum_count_scans,
        **debit_summary,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
