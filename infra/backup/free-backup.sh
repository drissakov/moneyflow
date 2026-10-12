#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
require_tmpfs
: "${AGE_RECIPIENT:?A public age recipient is required}"
[[ "$AGE_RECIPIENT" =~ ^age1[0-9a-z]{58}$ ]] || die 'Expected a standard age1 public recipient. Keep the private identity offline.'
output=/output/moneyflow-database.tar.age
completed=false
cleanup_free_backup() {
    rm -rf -- "$BACKUP_DIR"
    if [[ "$completed" != true ]]; then rm -f -- "$output" "$output.sha256" /output/verification.json; fi
}
trap cleanup_free_backup EXIT
trap 'exit 143' TERM
trap 'exit 130' INT
"$SCRIPT_DIR/dump.sh"
"$SCRIPT_DIR/restore-archive.sh" "$BACKUP_DIR"
# Only ciphertext reaches the host-mounted output directory. The private key
# never enters GitHub Actions; daily verification checks the full dump before
# encrypting, while offline recovery drills verify decryption end to end.
tar -C "$BACKUP_DIR" -cf - moneyflow.dump manifest.jsonl format-version \
    | age --encrypt --recipient "$AGE_RECIPIENT" --output "$output"
bytes=$(stat -c %s "$output")
(( bytes > 0 && bytes <= 20971520 )) || die 'Encrypted backup exceeds the 20 MiB free-storage limit; upload refused.'
hash=$(sha256sum "$output" | cut -d ' ' -f 1)
printf '%s  moneyflow-database.tar.age\n' "$hash" >"$output.sha256"
printf '{"format_version":1,"created_at":"%s","restore_verified_before_encryption":true,"encrypted_bytes":%s,"sha256":"%s"}\n' \
    "$(date -u +%FT%TZ)" "$bytes" "$hash" >/output/verification.json
chmod 0644 "$output" "$output.sha256" /output/verification.json
completed=true
log 'Verified database dump encrypted to offline recovery key; ciphertext ready for private draft upload.'
