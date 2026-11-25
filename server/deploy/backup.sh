#!/usr/bin/env bash
# Daily encrypted backup with restic (run by dc-backup.timer as the dc user).
# /srv/dc/backup.env must define RESTIC_REPOSITORY, RESTIC_PASSWORD_FILE and optionally
# HEALTHCHECKS_BACKUP_URL (see backup.env.example).
set -euo pipefail

# shellcheck source=/dev/null
source /srv/dc/backup.env
export RESTIC_REPOSITORY RESTIC_PASSWORD_FILE

staging=$(mktemp -d /srv/dc/.backup-XXXXXX)
trap 'rm -rf "$staging"' EXIT

# A consistent copy of the database even while the API writes to it.
if [[ -f /srv/dc/data/dcserver.sqlite3 ]]; then
    sqlite3 /srv/dc/data/dcserver.sqlite3 ".backup '$staging/dcserver.sqlite3'"
fi
for item in /srv/dc/data/manifest-state.json /srv/dc/data/catalog /srv/dc/data/canyon-reference.json /srv/dc/env /srv/dc/secrets; do
    [[ -e "$item" ]] && cp -a "$item" "$staging/"
done

restic backup --quiet --tag dc "$staging"
restic forget --quiet --tag dc --keep-daily 7 --keep-weekly 8 --keep-monthly 12 --prune

if [[ -n "${HEALTHCHECKS_BACKUP_URL:-}" ]]; then
    curl -fsS -m 10 --retry 3 "$HEALTHCHECKS_BACKUP_URL" > /dev/null || true
fi
