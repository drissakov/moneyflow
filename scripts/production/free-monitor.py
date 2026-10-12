#!/usr/bin/env python3
"""Independent HTTPS and encrypted-backup checks using only Python's stdlib."""
from datetime import datetime, timezone
import ipaddress
import json
import os
import re
import socket
import ssl
import sys
import time
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener


class CheckFailure(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        raise CheckFailure("Unexpected HTTP redirect; verify the production URL and deployment protection.")


def api_url(value):
    try:
        parsed = urlparse(value)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                or parsed.port not in (None, 443) or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment):
            raise ValueError()
        if not re.fullmatch(r"(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}", parsed.hostname):
            raise ValueError()
        if any(parsed.hostname == name or parsed.hostname.endswith("." + name) for name in ("invalid", "localhost", "test", "example.com", "example.net", "example.org")):
            raise ValueError()
        return "https://" + parsed.hostname + "/", parsed.hostname
    except ValueError:
        raise CheckFailure("Set MONEYFLOW_API_URL to the public HTTPS API root, without credentials or query parameters.") from None


def public_dns(hostname):
    try:
        results = socket.getaddrinfo(hostname, 443, type=socket.SOCK_STREAM)
        if not results or any(not ipaddress.ip_address(item[4][0]).is_global for item in results):
            raise ValueError()
    except (ValueError, OSError):
        raise CheckFailure("The API hostname must resolve to public addresses.") from None


def json_request(url, headers=None):
    try:
        with build_opener(NoRedirect()).open(Request(url, headers=headers or {}), timeout=20) as response:
            if response.status != 200:
                raise CheckFailure("The HTTP check did not return status 200.")
            payload = response.read(1024 * 1024 + 1)
            if len(payload) > 1024 * 1024:
                raise CheckFailure("The HTTP check returned an oversized response.")
            return json.loads(payload)
    except CheckFailure:
        raise
    except Exception:
        # Do not log response bodies, authentication tokens, URLs with credentials or HTTP error details.
        raise CheckFailure("An HTTPS request failed or did not return valid JSON.") from None


def api_status(url, expected):
    last_error = None
    for attempt in range(3):
        try:
            start = time.monotonic()
            result = json_request(url)
            if not isinstance(result, dict) or result.get("status") != expected:
                raise CheckFailure("The API returned an unexpected readiness status.")
            return round((time.monotonic() - start) * 1000)
        except CheckFailure as error:
            last_error = error
            if attempt < 2:
                time.sleep(5)
    raise last_error


def certificate_days(hostname):
    try:
        with socket.create_connection((hostname, 443), timeout=10) as connection:
            with ssl.create_default_context().wrap_socket(connection, server_hostname=hostname) as secure:
                expiry = ssl.cert_time_to_seconds(secure.getpeercert()["notAfter"])
        days = (expiry - time.time()) / 86400
        if days < 7:
            raise CheckFailure("The API TLS certificate expires within seven days.")
        return int(days)
    except CheckFailure:
        raise
    except Exception:
        raise CheckFailure("The API TLS certificate or hostname validation failed.") from None


def backup_age_hours(repository, token):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or not token:
        raise CheckFailure("GitHub repository metadata or workflow authentication is unavailable.")
    headers = {"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    prefix = "https://api.github.com/repos/" + repository
    releases = []
    for page in range(1, 51):
        items = json_request(prefix + f"/releases?per_page=100&page={page}", headers)
        if not isinstance(items, list):
            raise CheckFailure("GitHub did not return valid release metadata.")
        releases.extend(item for item in items if item.get("tag_name") == "moneyflow-encrypted-backups")
        if len(items) < 100:
            break
    else:
        raise CheckFailure("Release pagination exceeded the monitor's safety limit.")
    if len(releases) != 1:
        raise CheckFailure("The private backup draft release is missing or ambiguous; verify workflow token access and backup setup.")
    release = releases[0]
    if release.get("draft") is not True or release.get("published_at") is not None:
        raise CheckFailure("The backup release must remain an unpublished draft. Inspect release access immediately.")
    identifier = release.get("id")
    if not isinstance(identifier, int):
        raise CheckFailure("The backup draft release has invalid metadata.")
    assets = {}
    for page in range(1, 11):
        items = json_request(prefix + f"/releases/{identifier}/assets?per_page=100&page={page}", headers)
        if not isinstance(items, list):
            raise CheckFailure("GitHub did not return valid backup asset metadata.")
        for item in items:
            name = item.get("name", "")
            if name in assets:
                raise CheckFailure("Duplicate backup asset names were found.")
            assets[name] = item
        if len(items) < 100:
            break
    else:
        raise CheckFailure("Backup asset pagination exceeded the monitor's safety limit.")
    # Match actual successful workflow attempts; a partial upload or skipped run
    # cannot make a backup appear fresh. The receipt is uploaded last.
    runs = json_request(prefix + "/actions/workflows/free-backup.yml/runs?branch=main&status=success&per_page=100", headers)
    for run in runs.get("workflow_runs", []):
        if run.get("conclusion") != "success" or run.get("head_branch") != "main":
            continue
        identifier = run.get("id")
        attempt = run.get("run_attempt")
        if not isinstance(identifier, int) or not isinstance(attempt, int) or attempt < 1:
            continue
        base = f"moneyflow-backup-{identifier}-{attempt}.tar.age"
        group = [assets.get(base + suffix) for suffix in ("", ".sha256", ".verification.json")]
        if any(not item or item.get("state") != "uploaded" or item.get("size", 0) <= 0 for item in group):
            continue
        try:
            times = [datetime.fromisoformat(item["created_at"].replace("Z", "+00:00")) for item in group]
            if any(stamp.tzinfo is None for stamp in times):
                continue
            ages = [(datetime.now(timezone.utc) - stamp).total_seconds() / 3600 for stamp in times]
        except (ValueError, KeyError):
            continue
        if all(0 <= age <= 30 for age in ages):
            return round(ages[-1], 1)
    raise CheckFailure("No complete encrypted backup from a successful workflow attempt exists from the last 30 hours. Inspect Free encrypted database backup.")


def main():
    root, hostname = api_url(os.environ.get("MONEYFLOW_API_URL", ""))
    public_dns(hostname)
    days = certificate_days(hostname)
    health_ms = api_status(root + "healthz", "ok")
    ready_ms = api_status(root + "readyz", "ready")
    report = ["HTTPS certificate and hostname: valid", f"TLS certificate lifetime: {days} days", f"API health: OK ({health_ms} ms)", f"Database readiness: OK ({ready_ms} ms)"]
    if os.environ.get("MONEYFLOW_FREE_BACKUPS_ENABLED") == "true":
        age = backup_age_hours(os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_TOKEN", ""))
        report.append(f"Latest verified encrypted backup: {age} hours old")
    else:
        report.append("Backup freshness: disabled; enable MONEYFLOW_FREE_BACKUPS_ENABLED after setup")
    text = "\n".join("- " + line for line in report) + "\n"
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as file:
            file.write(text)


if __name__ == "__main__":
    try:
        main()
    except CheckFailure as error:
        print("MoneyFlow monitoring failed: " + str(error), file=sys.stderr)
        sys.exit(1)
    except Exception:
        print("MoneyFlow monitoring failed: unexpected check error; inspect service availability and workflow settings.", file=sys.stderr)
        sys.exit(1)
