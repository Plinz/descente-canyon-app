"""Server settings, read from the environment (see deploy/env.example)."""
from __future__ import annotations

import base64
import binascii
import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DEFAULT_USER_AGENT = "DescenteCanyonAppServer/1.0 (+https://plinz.github.io/descente-canyon-app/)"


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
    )
