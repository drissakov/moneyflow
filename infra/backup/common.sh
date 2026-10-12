#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

BACKUP_DIR=/work/backup
RESTORE_DIR=/work/restore
STATE_DIR=/state
METRICS_DIR=/metrics
BACKUP_HOST=${BACKUP_HOST:-moneyflow-production}
BACKUP_TAG=moneyflow-postgres
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

log() { printf 'moneyflow-backup: %s\n' "$*" >&2; }
die() { log "$*"; exit 1; }

secret() {
    local path=$1
    [[ -f "$path" && -r "$path" && -s "$path" ]] || die 'A required secret file is missing or empty.'
    # Bootstrap generates single-line secrets; reject embedded line breaks.
    local value
    value=$(cat -- "$path")
    [[ -n "$value" && "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die 'Secret files must contain one non-empty line.'
    printf '%s' "$value"
}

load_restic() {
    : "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY is required}"
    : "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE is required}"
    secret "$RESTIC_PASSWORD_FILE" >/dev/null
    # Credentials stay out of command arguments, Compose environment and logs.
    if [[ "$RESTIC_REPOSITORY" == s3:* ]]; then
        [[ "$RESTIC_REPOSITORY" == s3:https://* ]] || die 'Remote S3 repositories must use verified HTTPS.'
        export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
        AWS_ACCESS_KEY_ID=$(secret "${AWS_ACCESS_KEY_ID_FILE:?AWS_ACCESS_KEY_ID_FILE is required}")
        AWS_SECRET_ACCESS_KEY=$(secret "${AWS_SECRET_ACCESS_KEY_FILE:?AWS_SECRET_ACCESS_KEY_FILE is required}")
    fi
    export RESTIC_REPOSITORY RESTIC_PASSWORD_FILE
    export RESTIC_CACHE_DIR=/work/cache
    mkdir -p "$RESTIC_CACHE_DIR"
}

load_source() {
    if [[ -n "${SOURCE_DATABASE_URL_FILE:-}" ]]; then
        # Parse a provider URI without putting its password in process arguments.
        # libpq URI sslmode settings cannot downgrade the forced TLS policy.
        local fields=()
        mapfile -d '' -t fields < <(python3 "$SCRIPT_DIR/source-connection.py" "$SOURCE_DATABASE_URL_FILE")
        [[ ${#fields[@]} == 5 ]] || die 'Invalid source database connection secret.'
        PGHOST=${fields[0]}; PGPORT=${fields[1]}; PGDATABASE=${fields[2]}; PGUSER=${fields[3]}; PGPASSWORD=${fields[4]}
        export PGSSLMODE=verify-full PGSSLROOTCERT=/etc/ssl/cert.pem
    else
        : "${PGHOST:?PGHOST is required}" "${PGDATABASE:?PGDATABASE is required}" "${PGUSER:?PGUSER is required}"
        PGPORT=${PGPORT:-5432}
        PGPASSWORD=$(secret "${PGPASSWORD_FILE:?PGPASSWORD_FILE is required}")
    fi
    export PGHOST PGDATABASE PGUSER PGPASSWORD PGPORT
    export PGCONNECT_TIMEOUT=10 PGTZ=UTC
    export PGOPTIONS='-c timezone=UTC -c extra_float_digits=3 -c statement_timeout=300000'
}

require_tmpfs() {
    [[ "$(findmnt -n -o FSTYPE -T /work)" == tmpfs ]] || die '/work must be a tmpfs mount; refusing to write a plaintext dump to persistent storage.'
}

acquire_lock() {
    mkdir -p "$STATE_DIR" "$METRICS_DIR"
    exec 9>"$STATE_DIR/job.lock"
    flock -n 9 || die 'Another backup, restore verification or prune job is running.'
}

state_number() {
    local value=0
    if [[ -f "$STATE_DIR/$1" ]]; then value=$(cat "$STATE_DIR/$1"); fi
    [[ "$value" =~ ^[0-9]+$ ]] || value=0
    printf '%s' "$value"
}

save_number() {
    [[ "$2" =~ ^[0-9]+$ ]] || die 'Invalid numeric job state.'
    printf '%s\n' "$2" >"$STATE_DIR/$1.tmp"
    mv -f -- "$STATE_DIR/$1.tmp" "$STATE_DIR/$1"
}

write_metrics() {
    local metric value
    : >"$METRICS_DIR/moneyflow_backup.prom.tmp"
    for metric in last_success_timestamp_seconds last_run_success duration_seconds \
        check_last_success_timestamp_seconds check_last_run_success \
        restore_last_success_timestamp_seconds restore_last_run_success; do
        value=$(state_number "$metric")
        printf '# TYPE moneyflow_backup_%s gauge\nmoneyflow_backup_%s %s\n' "$metric" "$metric" "$value" \
            >>"$METRICS_DIR/moneyflow_backup.prom.tmp"
    done
    chmod 0644 "$METRICS_DIR/moneyflow_backup.prom.tmp"
    mv -f -- "$METRICS_DIR/moneyflow_backup.prom.tmp" "$METRICS_DIR/moneyflow_backup.prom"
}

restic_cmd() { restic --no-cache --retry-lock 2m "$@"; }

repository_init() {
    local status=0
    restic_cmd cat config >/dev/null 2>/work/restic-init-error || status=$?
    case "$status" in
        0) log 'Existing encrypted repository authenticated; initialization skipped.' ;;
        10) restic_cmd init >/dev/null; log 'Encrypted repository initialized.' ;;
        *) die 'Repository authentication or connection failed; initialization refused.' ;;
    esac
    rm -f -- /work/restic-init-error
}
