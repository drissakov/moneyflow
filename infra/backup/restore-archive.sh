#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tmpfs
archive=${1:?Archive directory is required}
[[ "$archive" == /work/* && -d "$archive" && -s "$archive/moneyflow.dump" && -s "$archive/manifest.jsonl" \
    && -f "$archive/format-version" && "$(cat "$archive/format-version")" == 1 ]] || die 'Invalid in-memory database archive.'
: "${RESTORE_PGHOST:?RESTORE_PGHOST is required}" "${RESTORE_PGUSER:?RESTORE_PGUSER is required}"
[[ "$RESTORE_PGHOST" != "${PGHOST:-db}" && "$RESTORE_PGUSER" != "${PGUSER:-moneyflow}" ]] \
    || die 'Restore verification must use an isolated host and database role.'
export PGHOST=$RESTORE_PGHOST PGPORT=${RESTORE_PGPORT:-5432} PGUSER=$RESTORE_PGUSER
export PGDATABASE=postgres PGCONNECT_TIMEOUT=10 PGTZ=UTC PGPASSWORD PGSSLMODE=disable
unset PGSSLROOTCERT
PGPASSWORD=$(secret "${RESTORE_PGPASSWORD_FILE:?RESTORE_PGPASSWORD_FILE is required}")
export PGOPTIONS='-c timezone=UTC -c extra_float_digits=3 -c statement_timeout=300000'
verify_db="moneyflow_verify_$(date +%s)_$$"
created=false
cleanup_verify() {
    if [[ "$created" == true && "$verify_db" =~ ^moneyflow_verify_[0-9]+_[0-9]+$ ]]; then
        dropdb --if-exists --force "$verify_db" >/dev/null 2>&1 || true
    fi
    rm -f -- /work/restored-manifest.jsonl
}
trap cleanup_verify EXIT
createdb --template=template0 "$verify_db"
created=true
pg_restore --exit-on-error --clean --if-exists --no-owner --no-privileges \
    --dbname="$verify_db" "$archive/moneyflow.dump" >/dev/null
PGDATABASE=$verify_db psql -X -qAt -v ON_ERROR_STOP=1 -f "$SCRIPT_DIR/manifest.sql" >/work/restored-manifest.jsonl
cmp -s -- "$archive/manifest.jsonl" /work/restored-manifest.jsonl \
    || die 'Restored database fingerprints differ from the original consistent snapshot.'
log 'Isolated PostgreSQL restore completed; all table fingerprints match.'
