#!/usr/bin/env python3
"""Strict deployment preflight. Diagnostics never include configuration values."""
import ipaddress
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import sys
from urllib.parse import urlparse


def fail(message):
    raise ValueError(message)


def env_file(path):
    result = {}
    for number, raw in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"Invalid environment assignment on line {number}.")
        key, value = line.split("=", 1)
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key) or key in result:
            fail(f"Invalid or duplicate environment key on line {number}.")
        if value.startswith(("'", '"')):
            if len(value) < 2 or value[-1] != value[0]:
                fail(f"Unclosed quoted value on line {number}.")
            value = value[1:-1]
        # All non-secret deployment values use literal, single-line syntax.
        if any(char in value for char in ("\r", "\n", "\x00", "$", "`", "\\")):
            fail(f"Unsupported environment syntax on line {number}.")
        result[key] = value
    if any(key in result for key in ("POSTGRES_PASSWORD", "DATABASE_URL", "RESTIC_PASSWORD", "AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID")):
        fail("Move legacy inline credentials to secrets/ files; do not overwrite an existing database password.")
    return result


def checked_file(path, mode):
    if path.is_symlink() or not path.is_file():
        fail(f"Required regular file is missing: {path.name}.")
    if os.name != "nt":
        info = path.stat()
        if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != mode:
            fail(f"Incorrect owner or permissions for {path.name}; see docs/PRODUCTION.md.")


def secret(path):
    checked_file(path, 0o444)
    value = path.read_text(encoding="utf-8").rstrip("\n")
    if not value or "\n" in value or "\r" in value or "\x00" in value or "REPLACE" in value.upper():
        fail(f"A valid single-line secret is required: {path.name}.")
    return value


def check(root):
    checked_file(root / ".env.production", 0o600)
    settings = env_file(root / ".env.production")
    domain = settings.get("DOMAIN", "")
    if not re.fullmatch(r"(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}", domain):
        fail("DOMAIN must be a public lowercase DNS hostname, without a URL scheme or path.")
    if any(domain == suffix or domain.endswith("." + suffix) for suffix in ("example.com", "example.net", "example.org", "invalid", "localhost", "test")):
        fail("Replace the DOMAIN placeholder with a real domain.")
    email = settings.get("ACME_EMAIL", "")
    if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email) or email.lower().endswith(("@example.com", "@example.org", "@example.net")):
        fail("Set a real ACME_EMAIL certificate contact.")
    for key in ("POSTGRES_USER", "POSTGRES_DB"):
        if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", settings.get(key, "")):
            fail(f"{key} must be a lowercase PostgreSQL identifier.")
    session = settings.get("SESSION_TTL", "")
    if not re.fullmatch(r"[1-9][0-9]*(?:m|h)", session):
        fail("SESSION_TTL must be an explicit duration in minutes or hours.")
    minutes = int(session[:-1]) * (60 if session.endswith("h") else 1)
    if minutes > 90 * 24 * 60:
        fail("SESSION_TTL must not exceed 90 days.")
    repository = settings.get("RESTIC_REPOSITORY", "")
    if not repository.startswith("s3:https://") or any(marker in repository.upper() for marker in ("REPLACE", "YOUR_", "ACCOUNT_ID", "EXAMPLE")):
        fail("RESTIC_REPOSITORY must identify a real offsite S3 HTTPS bucket/prefix.")
    parsed_repository = urlparse(repository[3:])
    if not parsed_repository.hostname or parsed_repository.username or not parsed_repository.path.strip("/"):
        fail("RESTIC_REPOSITORY must contain an HTTPS endpoint and bucket, without credentials.")
    try:
        address = ipaddress.ip_address(parsed_repository.hostname)
    except ValueError:
        address = None
        if parsed_repository.hostname in ("localhost", "host.docker.internal", "db", "minio"):
            fail("The backup endpoint must be offsite, not a local service.")
    if address is not None and not address.is_global:
        fail("The backup endpoint must be offsite, not a local address.")
    secrets = root / "secrets"
    if secrets.is_symlink() or not secrets.is_dir():
        fail("Run scripts/production/bootstrap.sh to create the secrets directory.")
    if os.name != "nt":
        info = secrets.stat()
        if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
            fail("The secrets directory must be owned by the deploy user with mode 0700.")
    password = secret(secrets / "postgres_password")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{32,}", password):
        fail("postgres_password must contain at least 32 URL-safe random characters.")
    connection = urlparse(secret(secrets / "database_url"))
    if (connection.scheme != "postgres" or connection.hostname != "db" or connection.port != 5432
            or connection.username != settings["POSTGRES_USER"] or connection.password != password
            or connection.path != "/" + settings["POSTGRES_DB"] or connection.query != "sslmode=disable"):
        fail("database_url must match postgres_password and the private db service.")
    for name in ("restic_password", "restore_db_password", "grafana_admin_password"):
        if len(secret(secrets / name)) < 32:
            fail(f"{name} must contain at least 32 random characters.")
    for name in ("backup_s3_access_key", "backup_s3_secret_key"):
        secret(secrets / name)


def host(root):
    if sys.platform != "linux":
        fail("Production deployment scripts run on a Linux VPS; use deploy-via-ssh.ps1 from Windows.")
    if shutil.disk_usage(root).free < 6 * 1024 ** 3:
        fail("At least 6 GiB free disk space is required for images and a safe deployment.")
    memory = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
    if int(memory["MemTotal"].split()[0]) < 1800 * 1024:
        fail("At least 2 GiB RAM is required; 4 GiB is recommended for builds and monitoring.")
    domain = env_file(root / ".env.production")["DOMAIN"]
    try:
        addresses = socket.getaddrinfo(domain, 443, type=socket.SOCK_STREAM)
    except OSError:
        fail("DOMAIN must resolve in public DNS before deploying.")
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        fail("DOMAIN must resolve only to public server addresses.")


def versions(engine, compose):
    def version(raw):
        match = re.match(r"v?(\d+)\.(\d+)(?:\.(\d+))?", raw)
        if not match:
            fail("Cannot determine Docker/Compose version.")
        return tuple(int(value or "0") for value in match.groups())
    if version(engine) < (24, 0, 0) or version(compose) < (2, 24, 0):
        fail("Docker Engine 24+ and Compose plugin 2.24+ are required.")


def main():
    action = sys.argv[1]
    if action == "value":
        print(env_file(Path(sys.argv[2])).get(sys.argv[3], ""))
    elif action == "check":
        check(Path(sys.argv[2]))
    elif action == "host":
        host(Path(sys.argv[2]))
    elif action == "versions":
        versions(sys.argv[2], sys.argv[3])
    else:
        fail("Unknown validation command.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, IndexError):
        # ValueError messages are ours, except urllib parsing; suppress those too.
        error = sys.exc_info()[1]
        message = str(error) if isinstance(error, ValueError) and not str(error).startswith(("Port ", "Invalid IPv")) else "Configuration validation failed; inspect required files and documented settings."
        print("MoneyFlow: " + message, file=sys.stderr)
        sys.exit(1)
