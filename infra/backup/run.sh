#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tmpfs
acquire_lock
load_restic
started=$(date +%s)
stage=backup
finished=false
cleanup_job() {
    local status=$?
    if [[ "$finished" != true ]]; then
        save_number last_run_success 0
        if [[ "$stage" == check ]]; then save_number check_last_run_success 0; fi
        if [[ "$stage" == restore ]]; then save_number restore_last_run_success 0; fi
        log "Job failed during $stage; previous successful snapshots retained."
    fi
    save_number duration_seconds "$(( $(date +%s) - started ))"
    write_metrics
    rm -rf -- "$BACKUP_DIR" "$RESTORE_DIR" /work/backup-result.jsonl
    exit "$status"
}
trap cleanup_job EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

write_metrics
# No automatic init: missing repository, invalid credentials and wrong password
# all fail closed. An operator runs the separate init command after bootstrap.
restic_cmd cat config >/dev/null
"$SCRIPT_DIR/dump.sh"
restic_cmd backup --host "$BACKUP_HOST" --tag "$BACKUP_TAG" --json "$BACKUP_DIR" \
    >/work/backup-result.jsonl
snapshot=$(jq -er 'select(.message_type == "summary") | .snapshot_id' /work/backup-result.jsonl)
[[ "$snapshot" =~ ^[0-9a-f]{64}$ ]] || die 'Restic did not report a completed snapshot.'
# The uploaded snapshot is now the source of truth; free the in-memory source
# before downloading it for the restore test to bound peak tmpfs consumption.
rm -rf -- "$BACKUP_DIR"
stage=check
restic_cmd check --read-data >/dev/null
save_number check_last_success_timestamp_seconds "$(date +%s)"
save_number check_last_run_success 1
stage=restore
"$SCRIPT_DIR/verify.sh" "$snapshot"
save_number restore_last_success_timestamp_seconds "$(date +%s)"
save_number restore_last_run_success 1
stage=retention
for setting in KEEP_DAILY KEEP_WEEKLY KEEP_MONTHLY; do
    value=${!setting:-}
    [[ -z "$value" || "$value" =~ ^[1-9][0-9]*$ ]] || die 'Retention values must be positive integers.'
done
# Same process lock protects backup+check+restore+forget. Restic additionally
# locks the remote repository, including other machines. Never unlock blindly.
restic_cmd forget --host "$BACKUP_HOST" --tag "$BACKUP_TAG" --group-by host,paths,tags \
    --keep-daily "${KEEP_DAILY:-7}" --keep-weekly "${KEEP_WEEKLY:-4}" \
    --keep-monthly "${KEEP_MONTHLY:-6}" --prune --max-repack-size "${BACKUP_MAX_REPACK_SIZE:-256M}" >/dev/null
save_number last_success_timestamp_seconds "$(date +%s)"
save_number last_run_success 1
finished=true
log 'Backup, encrypted repository check, restore verification and retention completed.'
