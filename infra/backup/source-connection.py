"""Parse a secret provider URI into NUL-separated libpq environment values."""

import ipaddress
import pathlib
import sys
import urllib.parse


def connection_values(path: str) -> list[str]:
    raw = pathlib.Path(path).read_text(encoding="utf-8").strip()
    parsed = urllib.parse.urlsplit(raw)
    if parsed.scheme not in ("postgres", "postgresql"):
        raise ValueError("Unsupported scheme")
    host = parsed.hostname or ""
    if not host or not parsed.username or not parsed.password:
        raise ValueError("Connection is missing required fields")
    if host in ("localhost", "restore-db", "db") or host.endswith(".local"):
        raise ValueError("A public TLS database host is required")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address and not address.is_global:
        raise ValueError("A public database host is required")
    database = urllib.parse.unquote(parsed.path.removeprefix("/"))
    if not database or "/" in database:
        raise ValueError("Invalid database name")
    values = [host, str(parsed.port or 5432), database,
              urllib.parse.unquote(parsed.username), urllib.parse.unquote(parsed.password)]
    if any(not value or any(c in value for c in "\x00\r\n") for value in values):
        raise ValueError("Invalid connection fields")
    return values


if __name__ == "__main__":
    try:
        values = connection_values(sys.argv[1])
    except (ValueError, OSError, IndexError):
        print("Invalid source connection secret; details suppressed.", file=sys.stderr)
        sys.exit(1)
    sys.stdout.buffer.write("\0".join(values).encode("utf-8") + b"\0")
