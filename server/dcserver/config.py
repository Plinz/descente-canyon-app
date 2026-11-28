"""Server settings, read from the environment (see deploy/env.example)."""
from __future__ import annotations

import base64
import binascii
import os
import re
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DEFAULT_USER_AGENT = "DescenteCanyonAppServer/1.0 (+https://plinz.github.io/descente-canyon-app/)"
DEFAULT_PUBLIC_URL = "https://dc-app.fr"
"""Address of the links the app shares: its default dc.linkHost (app/build.gradle.kts) is this host."""
DEFAULT_ANDROID_PACKAGE = "fr.descentecanyon.app"
RELEASE_CERT_SHA256 = "06:C9:84:D8:94:B5:7B:12:FB:4A:E9:FF:D4:0D:00:A2:3F:FB:1B:68:79:DA:02:30:10:6F:83:AE:CA:04:F0:A5"
"""Certificate of the APKs the Release workflow signs (GitHub releases, upload key of Google Play)."""


class ConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    """SQLite database, job state and reports, scraped canyon files."""
    publish_dir: Path
    """Served as is by Caddy: its `v1/` folder answers https://<domain>/v1/."""
    repo_dir: Path
    """Clone of the app repository: scraper, catalog generator, Room schemas, data overlay."""
    pepper: bytes
    """Secret mixed into the hashes of installation keys and networks (never stored with them)."""
    signing_key_path: Path | None = None
    user_agent: str = DEFAULT_USER_AGENT
    request_delay_s: float = 1.0
    rotation_days: int = 14
    healthchecks_url: str | None = None
    healthchecks_predictions_url: str | None = None
    trusted_proxy: bool = True
    max_upload_bytes: int = 2 * 1024 * 1024
    openmeteo_daily_calls: float = 9000.0
    """Open-Meteo calls a day (weighted as Open-Meteo counts them); the free plan allows 10,000."""
    openmeteo_seconds_per_call: float = 0.75
    """Pause per weighted call: 4,800 an hour at most, under the free plan's 5,000."""
    openmeteo_reserve: float = 2500.0
    """Calls the filling of new cells leaves for the forecasts of the later runs of the day (three
    runs after the first one of the UTC day, about 765 weighted calls each)."""
    openmeteo_api_key: str | None = None
    """Key of a paid Open-Meteo plan (customer endpoints), if any."""
    debit_pages_per_run: int = 200
    forecast_max_age_h: float = 5.0
    admin_token: str | None = None
    """Token of the review tool (`dcserver admin`); without it the tool refuses every request."""
    admin_hosts: tuple[str, ...] = ("localhost", "127.0.0.1", "::1")
    """Host names the review tool answers to (it is reached through an SSH tunnel)."""
    public_url: str = DEFAULT_PUBLIC_URL
    """Address of the links the app shares (https://<domain>, no trailing slash)."""
    android_package: str = DEFAULT_ANDROID_PACKAGE
    android_cert_sha256: tuple[str, ...] = (RELEASE_CERT_SHA256,)
    """Certificates allowed to open the links in the app (assetlinks.json): the Release workflow's
    key and, for the copies installed from Google Play, the Play app signing key."""
    android_debug_cert_sha256: tuple[str, ...] = ()
    """Debug keys allowed for the debug build (package suffixed with .debug), for tests on a phone."""

    @property
    def database_path(self) -> Path:
        return self.data_dir / "dcserver.sqlite3"

    @property
    def manifest_state_path(self) -> Path:
        return self.data_dir / "manifest-state.json"

    @property
    def canyon_reference_path(self) -> Path:
        return self.data_dir / "canyon-reference.json"

    @property
    def scraped_canyons_dir(self) -> Path:
        return self.data_dir / "catalog" / "canyons"

    @property
    def reports_dir(self) -> Path:
        return self.data_dir / "reports"

    @property
    def lock_path(self) -> Path:
        return self.data_dir / "publish.lock"

    @property
    def v1_dir(self) -> Path:
        return self.publish_dir / "v1"


def _decode_pepper(raw: str) -> bytes:
    raw = raw.strip()
    try:
        value = bytes.fromhex(raw)
    except ValueError:
        try:
            value = base64.b64decode(raw, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ConfigError("DC_PEPPER must be hex or base64") from exc
    if len(value) < 32:
        raise ConfigError("DC_PEPPER must hold at least 32 bytes (generate it with: openssl rand -hex 32)")
    return value


def _flag(raw: str | None, default: bool) -> bool:
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _admin_token(raw: str | None) -> str | None:
    token = (raw or "").strip()
    if not token:
        return None
    if len(token) < 24:
        raise ConfigError("DC_ADMIN_TOKEN must hold at least 24 characters (generate it with: openssl rand -hex 24)")
    return token


_FINGERPRINT = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){31}$")


def _fingerprints(raw: str | None, default: tuple[str, ...]) -> tuple[str, ...]:
    """SHA-256 certificate fingerprints, comma-separated, as keytool or the Play Console print them."""
    if raw is None or not raw.strip():
        return default
    values = []
    for part in raw.split(","):
        value = part.strip().upper()
        if not value:
            continue
        if ":" not in value and len(value) == 64:
            value = ":".join(value[i : i + 2] for i in range(0, 64, 2))
        if not _FINGERPRINT.match(value):
            raise ConfigError(f"Not a SHA-256 certificate fingerprint: {part.strip()}")
        values.append(value)
    return tuple(values)


def _public_url(env: Mapping[str, str]) -> str:
    url = (env.get("DC_PUBLIC_URL") or "").strip().rstrip("/")
    if not url and (env.get("DC_DOMAIN") or "").strip():
        url = "https://" + env["DC_DOMAIN"].strip().rstrip("/")
    url = url or DEFAULT_PUBLIC_URL
    if not url.startswith("https://"):
        raise ConfigError("DC_PUBLIC_URL must be an https:// address")
    return url


def load_settings(env: Mapping[str, str] = os.environ) -> Settings:
    """Reads the settings; DC_PEPPER is required, everything else has a default."""
    pepper_raw = env.get("DC_PEPPER")
    if not pepper_raw:
        raise ConfigError("DC_PEPPER is not set (generate it with: openssl rand -hex 32)")
    signing_key = env.get("DC_SIGNING_KEY")
    rotation_days = int(env.get("DC_ROTATION_DAYS", "14"))
    if rotation_days < 1:
        raise ConfigError("DC_ROTATION_DAYS must be at least 1")
    return Settings(
        data_dir=Path(env.get("DC_DATA_DIR", "/srv/dc/data")),
        publish_dir=Path(env.get("DC_PUBLISH_DIR", "/srv/dc/publish")),
        repo_dir=Path(env.get("DC_REPO_DIR", "/srv/dc/repo")),
        pepper=_decode_pepper(pepper_raw),
        signing_key_path=Path(signing_key) if signing_key else None,
        user_agent=env.get("DC_USER_AGENT", DEFAULT_USER_AGENT),
        request_delay_s=float(env.get("DC_REQUEST_DELAY_S", "1.0")),
        rotation_days=rotation_days,
        healthchecks_url=env.get("DC_HEALTHCHECKS_URL") or None,
        healthchecks_predictions_url=env.get("DC_HEALTHCHECKS_PREDICTIONS_URL") or None,
        trusted_proxy=_flag(env.get("DC_TRUSTED_PROXY"), default=True),
        openmeteo_daily_calls=float(env.get("DC_OPENMETEO_DAILY_CALLS", "9000")),
        openmeteo_seconds_per_call=float(env.get("DC_OPENMETEO_SECONDS_PER_CALL", "0.75")),
        openmeteo_reserve=float(env.get("DC_OPENMETEO_RESERVE", "2500")),
        openmeteo_api_key=env.get("DC_OPENMETEO_API_KEY") or None,
        debit_pages_per_run=int(env.get("DC_DEBIT_PAGES_PER_RUN", "200")),
        forecast_max_age_h=float(env.get("DC_FORECAST_MAX_AGE_H", "5")),
        admin_token=_admin_token(env.get("DC_ADMIN_TOKEN")),
        admin_hosts=tuple(h.strip().lower() for h in env.get("DC_ADMIN_HOSTS", "localhost,127.0.0.1,::1").split(",") if h.strip()),
        public_url=_public_url(env),
        android_package=(env.get("DC_ANDROID_PACKAGE") or DEFAULT_ANDROID_PACKAGE).strip(),
        android_cert_sha256=_fingerprints(env.get("DC_ANDROID_CERT_SHA256"), (RELEASE_CERT_SHA256,)),
        android_debug_cert_sha256=_fingerprints(env.get("DC_ANDROID_DEBUG_CERT_SHA256"), ()),
    )
