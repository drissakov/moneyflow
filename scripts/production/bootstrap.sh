#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"
command -v python3 >/dev/null || mf_fail 'Install python3 first.'
umask 077
if [[ ! -f "$MF_ENV" ]]; then
  cp -- "$MF_ROOT/.env.production.example" "$MF_ENV"
  chmod 600 "$MF_ENV"
  mf_log 'Created .env.production. Set the real domain, email and offsite repository, then rerun bootstrap.'
  exit 0
fi
python3 - "$MF_ROOT" <<'PY'
import os
from pathlib import Path
import secrets
import sys
sys.path.insert(0, str(Path(sys.argv[1]) / "scripts/production"))
from validate import env_file, fail

root = Path(sys.argv[1])
try:
    settings = env_file(root / ".env.production")
    import re
    for key in ("POSTGRES_USER", "POSTGRES_DB"):
        if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", settings.get(key, "")):
            fail(f"Set a valid {key} before generating secrets.")
    target = root / "secrets"
    if target.is_symlink():
        fail("The secrets directory must not be a symlink.")
    target.mkdir(mode=0o700, exist_ok=True)
    os.chmod(target, 0o700)
    def create(name, content):
        path = target / name
        if path.is_symlink():
            fail(f"Secret file must not be a symlink: {name}.")
        if path.exists():
            return  # Never rotate secrets or database credentials during a deploy.
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(content + "\n")
        os.chmod(path, 0o444)
    create("postgres_password", secrets.token_hex(32))
    password = (target / "postgres_password").read_text().rstrip("\n")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{32,}", password):
        fail("Existing postgres_password must be URL-safe and at least 32 characters.")
    create("database_url", f"postgres://{settings['POSTGRES_USER']}:{password}@db:5432/{settings['POSTGRES_DB']}?sslmode=disable")
    for name in ("restic_password", "restore_db_password", "grafana_admin_password"):
        create(name, secrets.token_hex(32))
    # Optional alert delivery remains disabled until the owner supplies a destination.
    create("alert_webhook_url", "")
except (ValueError, OSError) as error:
    print("MoneyFlow: " + (str(error) if isinstance(error, ValueError) else "Secret generation failed; inspect local permissions."), file=sys.stderr)
    sys.exit(1)
PY
mf_log 'Existing secrets preserved; missing local passwords generated. Add your S3 access/secret key files with mode 0444.'
mf_log 'Keep secrets/ mode 0700; save the restic password securely outside the VPS before deploying.'
