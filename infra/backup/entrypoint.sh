#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

case "${1:-schedule}" in
    init)
        require_tmpfs
        acquire_lock
        load_restic
        repository_init
        ;;
    backup) exec "$SCRIPT_DIR/run.sh" ;;
    verify)
        require_tmpfs
        acquire_lock
        load_restic
        snapshot=${2:-}
        if [[ -z "$snapshot" ]]; then
            snapshot=$(restic_cmd snapshots --host "$BACKUP_HOST" --tag "$BACKUP_TAG" --latest 1 --json | jq -er '.[0].id')
        fi
        success=false
        trap 'if [[ "$success" != true ]]; then save_number restore_last_run_success 0; fi; write_metrics' EXIT
        "$SCRIPT_DIR/verify.sh" "$snapshot"
        save_number restore_last_success_timestamp_seconds "$(date +%s)"
        save_number restore_last_run_success 1
        success=true
        ;;
    health)
        last=$(state_number last_success_timestamp_seconds)
        [[ "$(state_number last_run_success)" == 1 \
            && "$last" -gt 0 && "$(( $(date +%s) - last ))" -lt "${BACKUP_MAX_AGE_SECONDS:-90000}" ]]
        ;;
    schedule)
        require_tmpfs
        utc_time=${BACKUP_UTC_TIME:-03:00}
        [[ "$utc_time" =~ ^([01][0-9]|2[0-3]):[0-5][0-9]$ ]] || die 'BACKUP_UTC_TIME must be HH:MM in UTC.'
        [[ "${BACKUP_RUN_ON_START:-true}" =~ ^(true|false)$ ]] || die 'BACKUP_RUN_ON_START must be true or false.'
        mkdir -p "$STATE_DIR" "$METRICS_DIR"
        if [[ "${BACKUP_RUN_ON_START:-true}" == true ]]; then
            last=$(state_number last_success_timestamp_seconds)
            if (( $(date +%s) - last >= ${BACKUP_START_MAX_AGE_SECONDS:-21600} )); then
                until "$SCRIPT_DIR/run.sh"; do
                    log 'Startup backup failed; retrying in five minutes.'
                    sleep 300 & wait $!
                done
            fi
        fi
        log "Daily backups scheduled for $utc_time UTC."
        trap 'exit 0' TERM INT
        while true; do
            now=$(date +%s)
            next=$(date -u -d "$(date -u +%F) $utc_time" +%s)
            (( next > now )) || next=$((next + 86400))
            while (( $(date +%s) < next )); do sleep 30 & wait $!; done
            until "$SCRIPT_DIR/run.sh"; do
                log 'Scheduled backup failed; retrying in five minutes.'
                sleep 300 & wait $!
            done
        done
        ;;
    *) die 'Usage: entrypoint.sh init|backup|verify [snapshot-id]|schedule|health' ;;
esac
