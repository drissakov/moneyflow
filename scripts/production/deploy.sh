#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

usage() { printf 'Usage: bash scripts/production/deploy.sh <main-commit-SHA> [--first-install|--check]\n'; }
[[ $# -ge 1 && $# -le 2 ]] || { usage >&2; exit 1; }
MF_EXPECTED_SHA="$1"
MF_MODE="${2:-upgrade}"
[[ "$MF_EXPECTED_SHA" =~ ^[a-f0-9]{40}$ ]] || mf_fail 'Supply a full lowercase 40-character main commit SHA.'
[[ "$MF_MODE" == upgrade || "$MF_MODE" == --first-install || "$MF_MODE" == --check ]] || { usage >&2; exit 1; }

mf_preflight
[[ "$(git -C "$MF_ROOT" rev-parse --show-toplevel)" == "$MF_ROOT" ]] || mf_fail 'Deploy only from the repository root.'
[[ -z "$(git -C "$MF_ROOT" status --porcelain)" ]] || mf_fail 'The checkout has local changes; commit them before deployment.'
origin="$(git -C "$MF_ROOT" remote get-url origin)"
[[ "$origin" == https://github.com/drissakov/moneyflow.git || "$origin" == https://github.com/drissakov/moneyflow ]] || mf_fail 'Expected the public MoneyFlow origin repository.'

# One deploy at a time, including its mandatory backup and migration.
exec 9>"$MF_ROOT/.production-deploy.lock"
flock -n 9 || mf_fail 'Another deployment is already running.'
git -C "$MF_ROOT" fetch origin main
[[ "$(git -C "$MF_ROOT" rev-parse FETCH_HEAD)" == "$MF_EXPECTED_SHA" ]] || mf_fail 'The supplied SHA is not the current remote main commit; review the new main revision first.'
if [[ "$MF_MODE" == --check ]]; then
  mf_log 'Preflight passed. No images, database or containers changed.'
  exit 0
fi
git -C "$MF_ROOT" switch main
git -C "$MF_ROOT" merge --ff-only "$MF_EXPECTED_SHA"
[[ "$(git -C "$MF_ROOT" rev-parse HEAD)" == "$MF_EXPECTED_SHA" ]] || mf_fail 'Checkout does not match the requested commit.'
# Config can change with the reviewed commit, so validate once more before build.
mf_preflight
export MONEYFLOW_BACKEND_IMAGE="moneyflow-backend:$MF_EXPECTED_SHA"
mf_log "Building reviewed main commit $MF_EXPECTED_SHA."
mf_compose pull --ignore-buildable
mf_compose build api backup
mf_compose up -d --no-deps --wait --wait-timeout 120 db restore-db

table_count="$(mf_compose exec -T db sh -c 'PGPASSWORD="$(cat /run/secrets/postgres_password)" psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema = '\''public'\'' AND table_type = '\''BASE TABLE'\''"')"
[[ "$table_count" =~ ^[0-9]+$ ]] || mf_fail 'Cannot determine whether the production database is empty.'
if [[ "$MF_MODE" == --first-install ]]; then
  [[ "$table_count" == 0 ]] || mf_fail 'First-install mode requires an empty public schema. Use the normal upgrade command for an existing installation.'
else
  [[ "$table_count" != 0 ]] || mf_fail 'The database is empty. Complete repository initialization and use --first-install explicitly.'
  mf_log 'Saving and restoring an encrypted offsite backup before migrations.'
  mf_compose run --rm --no-deps backup backup
fi

# Do not recreate the API if migration fails; the existing API container stays running.
mf_log 'Applying database migrations.'
mf_compose run --rm --no-deps migrate
if [[ "$MF_MODE" == --first-install ]]; then
  mf_log 'Saving and restoring the first encrypted backup before public startup.'
  mf_compose run --rm --no-deps backup backup
fi

runtime_services=()
while IFS= read -r service; do
  case "$service" in db|migrate|restore-db|backup) continue ;; esac
  runtime_services+=("$service")
done < <(mf_compose config --services)
mf_compose up -d --no-deps --wait --wait-timeout 180 "${runtime_services[@]}"
mf_compose up -d --no-deps backup
mf_ready
umask 077
python3 - "$MF_ENV" "$MONEYFLOW_BACKEND_IMAGE" <<'PY'
from pathlib import Path
import os
import sys
import tempfile
target = Path(sys.argv[1])
lines = [line for line in target.read_text().splitlines() if not line.startswith("MONEYFLOW_BACKEND_IMAGE=")]
lines.append("MONEYFLOW_BACKEND_IMAGE=" + sys.argv[2])
descriptor, temporary = tempfile.mkstemp(prefix=".production-env-", dir=target.parent)
try:
    with os.fdopen(descriptor, "w") as handle:
        handle.write("\n".join(lines) + "\n")
    os.replace(temporary, target)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
printf '%s\n' "$MF_EXPECTED_SHA" > "$MF_ROOT/.production-deployed-commit"
mf_log "Deployment verified: https://$(mf_env_value DOMAIN)/readyz"
