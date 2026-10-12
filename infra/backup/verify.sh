#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tmpfs
load_restic
snapshot=${1:?Snapshot ID is required}
[[ "$snapshot" =~ ^[0-9a-f]{64}$ ]] || die 'A full snapshot ID is required for restore verification.'
trap 'rm -rf -- "$RESTORE_DIR"' EXIT
mkdir -p "$RESTORE_DIR"
restic_cmd restore "$snapshot" --verify --target "$RESTORE_DIR" >/dev/null
archive="$RESTORE_DIR/work/backup"
[[ -f "$archive/format-version" && "$(cat "$archive/format-version")" == 1 \
    && -s "$archive/moneyflow.dump" && -s "$archive/manifest.jsonl" ]] || die 'Unsupported or incomplete backup snapshot.'
"$SCRIPT_DIR/restore-archive.sh" "$archive"
log 'Encrypted snapshot downloaded and verified.'
