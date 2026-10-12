#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tmpfs
load_source
mkdir -p "$BACKUP_DIR"

snapshot_in= snapshot_out= snapshot_pid=
cleanup_snapshot() {
    if [[ -n "$snapshot_in" ]]; then
        printf 'ROLLBACK;\n\\q\n' >&"$snapshot_in" 2>/dev/null || true
        exec {snapshot_in}>&-
    fi
    if [[ -n "$snapshot_out" ]]; then exec {snapshot_out}<&-; fi
    if [[ -n "$snapshot_pid" ]]; then wait "$snapshot_pid" 2>/dev/null || true; fi
}
trap cleanup_snapshot EXIT

# Keep one exported repeatable-read snapshot alive until both dump and manifest
# are finished. Concurrent writes cannot make the verification manifest disagree.
coproc SNAPSHOT { psql -X -qAt -v ON_ERROR_STOP=1; }
snapshot_pid=$SNAPSHOT_PID
exec {snapshot_in}>&"${SNAPSHOT[1]}"
exec {snapshot_out}<&"${SNAPSHOT[0]}"
printf 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n' >&"$snapshot_in"
read -r -t 30 snapshot_id <&"$snapshot_out" || die 'Could not export a consistent database snapshot.'
[[ "$snapshot_id" =~ ^[0-9a-fA-F]+-[0-9a-fA-F]+-[0-9]+$ ]] || die 'Invalid database snapshot identifier.'

pg_dump --format=custom --no-owner --no-privileges --schema=public \
    --snapshot="$snapshot_id" --file="$BACKUP_DIR/moneyflow.dump"
{
    printf "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET TRANSACTION SNAPSHOT :'snapshot_id';\n"
    cat "$SCRIPT_DIR/manifest.sql"
    printf '\nCOMMIT;\n'
} | psql -X -qAt -v ON_ERROR_STOP=1 -v "snapshot_id=$snapshot_id" >"$BACKUP_DIR/manifest.jsonl"
[[ -s "$BACKUP_DIR/moneyflow.dump" && -s "$BACKUP_DIR/manifest.jsonl" ]] || die 'Empty database dump or manifest; backup refused.'
printf '1\n' >"$BACKUP_DIR/format-version"
log 'Consistent PostgreSQL dump prepared in memory.'
