#!/usr/bin/env bash
# Brings the server up to date with the main branch of the app repository, in one command.
#
#   From your PC:  ssh -t dc@<domain> /srv/dc/repo/server/deploy/update.sh
#   On the VPS:    /srv/dc/repo/server/deploy/update.sh            (as dc)
#
# It pulls the repository, rebuilds the image and restarts the API when the server code changed,
# reloads Caddy when its configuration changed, installs new or changed systemd units, enables the
# timers, starts the jobs that never ran, then checks /v1/health. Running it again changes nothing.
# sudo (password of dc) is asked only when a unit or a timer must change, or a job must start.
#
# Options:
#   --rebuild   rebuild the image even when server/ did not change
#   --no-jobs   do not start the jobs that never ran (they wait for their timer)
set -euo pipefail

# Paths of the VPS (overridable only to test this script elsewhere).
SRV="${DC_UPDATE_SRV:-/srv/dc}"
REPO="$SRV/repo"
DEPLOY="$REPO/server/deploy"
DATA="$SRV/data"
UNITS_DIR="${DC_UPDATE_UNITS_DIR:-/etc/systemd/system}"

# 1. Pull first, then run the version of this script that was just pulled.
if [[ "${DC_UPDATE_PULLED:-}" != 1 ]]; then
    [[ "$(id -un)" == "${DC_UPDATE_USER:-dc}" ]] || { echo "Run as dc (ssh dc@<domain>)." >&2; exit 1; }
    before=$(git -C "$REPO" rev-parse HEAD)
    git -C "$REPO" pull --ff-only --quiet
    after=$(git -C "$REPO" rev-parse HEAD)
    if [[ "$before" == "$after" ]]; then
        echo "Code: already at ${after:0:8}"
    else
        echo "Code: ${before:0:8} -> ${after:0:8}"
        git -C "$REPO" log --oneline --no-decorate "$before..$after" | sed 's/^/  /' | head -n 20
    fi
    DC_UPDATE_PULLED=1 DC_UPDATE_BEFORE="$before" exec bash "$DEPLOY/update.sh" "$@"
fi

REBUILD=0
START_JOBS=1
for arg in "$@"; do
    case "$arg" in
        --rebuild) REBUILD=1 ;;
        --no-jobs) START_JOBS=0 ;;
        *) echo "Unknown option: $arg" >&2; exit 2 ;;
    esac
done

before="${DC_UPDATE_BEFORE:?}"
changed() { ! git -C "$REPO" diff --quiet "$before" HEAD -- "$@"; }
cd "$DEPLOY"
[[ -f .env ]] || { echo "server/deploy/.env is missing (see server/README.md, « Mise en service »)." >&2; exit 1; }

# 2. Image and containers.
if [[ $REBUILD == 1 ]] || changed server/Dockerfile server/requirements.lock server/pyproject.toml server/dcserver \
    || ! docker image inspect dcserver:local >/dev/null 2>&1; then
    echo "Image: rebuilding"
    docker compose build --pull --quiet api
fi
# Recreates only the containers whose image or compose definition changed.
docker compose up -d --remove-orphans --quiet-pull
if changed server/deploy/Caddyfile; then
    echo "Caddy: reloading its configuration"
    docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
fi
docker image prune -f >/dev/null

# 3. systemd units and timers.
units=()
for unit in systemd/dc-*.service systemd/dc-*.timer; do
    cmp -s "$unit" "$UNITS_DIR/$(basename "$unit")" || units+=("$unit")
done
if ((${#units[@]})); then
    echo "systemd: installing ${units[*]##*/}"
    sudo install -m 644 "${units[@]}" "$UNITS_DIR/"
    sudo systemctl daemon-reload
    # A timer already running keeps its old schedule until restarted.
    changed_timers=()
    for unit in "${units[@]}"; do
        name=$(basename "$unit")
        [[ "$name" == *.timer ]] && systemctl is-active --quiet "$name" && changed_timers+=("$name")
    done
    if ((${#changed_timers[@]})); then
        echo "systemd: restarting ${changed_timers[*]}"
        sudo systemctl restart "${changed_timers[@]}"
    fi
fi
wanted=(dc-catalog.timer dc-predictions.timer dc-app-canyons.timer)
# The backup runs once restic is configured (server/README.md, step 7).
[[ -f "$SRV/backup.env" ]] && wanted+=(dc-backup.timer)
to_enable=()
for timer in "${wanted[@]}"; do
    systemctl is-enabled --quiet "$timer" 2>/dev/null || to_enable+=("$timer")
done
if ((${#to_enable[@]})); then
    echo "systemd: enabling ${to_enable[*]}"
    sudo systemctl enable --now "${to_enable[@]}"
fi

# 4. The GPS reference of the canyons carries their place (country, region...) since the proposed
#    canyons take theirs from it: rebuilt once from the current catalog, not at the next catalog job.
if [[ -f "$DATA/catalog/current.db" && -f "$DATA/canyon-reference.json" ]] && ! grep -q '"place"' "$DATA/canyon-reference.json"; then
    echo "Reference: adding the place of each canyon"
    docker compose run -T --rm --no-deps jobs canyon-reference --catalog-db "$DATA/catalog/current.db"
fi

#    Jobs that never ran: the estimates need the catalog copy the catalog job writes. Started in
#    the background (dc-predictions.service waits for dc-catalog.service when both are queued).
if [[ $START_JOBS == 1 ]]; then
    candidates=()
    if [[ ! -f "$DATA/catalog/current.db" ]]; then
        candidates+=(dc-catalog.service dc-predictions.service)
    elif ! grep -q '"predictions"' "$DATA/manifest-state.json" 2>/dev/null; then
        candidates+=(dc-predictions.service)
    fi
    jobs_to_start=()
    for job in "${candidates[@]}"; do
        systemctl is-active --quiet "$job" || jobs_to_start+=("$job")
    done
    if ((${#jobs_to_start[@]})); then
        echo "Jobs: starting ${jobs_to_start[*]} in the background (journalctl -fu ${jobs_to_start[0]})"
        sudo systemctl start --no-block "${jobs_to_start[@]}"
    fi
fi

# 5. Checks.
domain=$(sed -n 's/^DC_DOMAIN=//p' .env | tr -d '"' | tail -n 1)
healthy=0
for _ in $(seq 1 15); do
    if curl -fsS --max-time 5 "https://$domain/v1/health" >/dev/null 2>&1; then
        healthy=1
        break
    fi
    sleep 2
done
if [[ $healthy == 1 ]]; then
    echo "API: https://$domain/v1/health answers"
else
    echo "API: https://$domain/v1/health does not answer (docker compose logs --tail 50 api caddy)" >&2
fi
if ! grep -q '^DC_ADMIN_TOKEN=.\+' "$SRV/env" 2>/dev/null; then
    echo "Note: DC_ADMIN_TOKEN is empty in /srv/dc/env: the review tool of the proposed canyons refuses every request (openssl rand -hex 24, then docker compose up -d admin)."
fi
if ! grep -q '^DC_HEALTHCHECKS_PREDICTIONS_URL=.\+' "$SRV/env" 2>/dev/null; then
    echo "Note: DC_HEALTHCHECKS_PREDICTIONS_URL is empty in /srv/dc/env (optional alert when the estimates job fails)."
fi
echo
systemctl list-timers 'dc-*' --no-pager | head -n -3
for job in catalog predictions; do
    last=$(ls -1t "$DATA/reports/$job-"*.json 2>/dev/null | head -n 1 || true)
    if [[ -n "$last" ]]; then
        if grep -q '"aborted"' "$last"; then status="aborted ($(sed -n 's/.*"aborted": "\([^"]*\)".*/\1/p' "$last"))"; else status="ok"; fi
        echo "Last $job job: $(basename "$last" .json | sed "s/^$job-//") — $status"
    fi
done
[[ $healthy == 1 ]]
