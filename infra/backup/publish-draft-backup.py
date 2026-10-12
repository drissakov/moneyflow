"""Store only encrypted backup assets in one private draft GitHub release."""

import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request

RELEASE_TAG = "moneyflow-encrypted-backups"
MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
KEEP_GROUPS = 7
KEEP_DAYS = 7
ASSET_NAME = re.compile(r"^(moneyflow-backup-[0-9]+-[0-9]+\.tar\.age)(\.sha256|\.verification\.json)?$")
PARTS = {"", ".sha256", ".verification.json"}


def find_draft(releases):
    matches = [release for release in releases if release.get("tag_name") == RELEASE_TAG]
    if len(matches) > 1:
        raise ValueError("Duplicate backup release tag")
    if matches and not matches[0].get("draft"):
        raise ValueError("Backup release is published; refusing public storage")
    return matches[0] if matches else None


def asset_groups(assets):
    groups = {}
    for asset in assets:
        match = ASSET_NAME.fullmatch(asset.get("name", ""))
        if match:
            group = groups.setdefault(match[1], {})
            part = match[2] or ""
            if part in group:
                raise ValueError("Duplicate backup asset name")
            group[part] = asset
    return groups


def complete_group(group):
    return set(group) == PARTS and all(asset.get("size", 0) > 0 and asset.get("state") == "uploaded" for asset in group.values())


def created_at(asset):
    value = dt.datetime.fromisoformat(asset["created_at"].replace("Z", "+00:00"))
    if value.tzinfo is None:
        raise ValueError("Missing asset timestamp timezone")
    return value


def retention_ids(assets, current_base, now):
    groups = asset_groups(assets)
    if current_base not in groups or not complete_group(groups[current_base]):
        raise ValueError("Current backup lacks its completion receipt")
    completed = [(base, group) for base, group in groups.items() if complete_group(group)]
    completed.sort(key=lambda item: created_at(item[1][".verification.json"]), reverse=True)
    keep = {base for base, _ in completed[:KEEP_GROUPS]}
    keep.add(current_base)
    cutoff = now - dt.timedelta(days=KEEP_DAYS)
    result = []
    for base, group in groups.items():
        if base == current_base:
            continue
        newest = max(created_at(asset) for asset in group.values())
        if newest < cutoff or (complete_group(group) and base not in keep):
            result.extend(asset["id"] for asset in group.values())
    return result


class GitHub:
    def __init__(self, repo, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
            raise ValueError("Invalid repository identity")
        self.repo, self.token = repo, token

    def request(self, path, method="GET", body=None, content_type="application/json", upload=False):
        host = "uploads.github.com" if upload else "api.github.com"
        request = urllib.request.Request(
            f"https://{host}/repos/{self.repo}/{path}", data=body, method=method,
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28", "Content-Type": content_type},
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response) if response.status != 204 else None

    def pages(self, path):
        output = []
        for page in range(1, 1001):
            items = self.request(f"{path}?per_page=100&page={page}")
            output.extend(items)
            if len(items) < 100:
                return output
        raise ValueError("GitHub listing exceeded safe pagination bound")

    def release(self):
        existing = find_draft(self.pages("releases"))
        if existing:
            return existing
        return self.request("releases", "POST", json.dumps({
            "tag_name": RELEASE_TAG, "name": "MoneyFlow encrypted backups",
            "body": "Private encrypted database recovery assets. Keep this release as a draft.",
            "draft": True, "prerelease": False, "generate_release_notes": False,
        }).encode())

    def upload(self, release_id, name, data, content_type):
        return self.request(f"releases/{release_id}/assets?name={urllib.parse.quote(name, safe='')}",
                            "POST", data, content_type, upload=True)


def publish(output, repo, token, run_id, run_attempt, now=None):
    if not re.fullmatch(r"[0-9]+", run_id) or not re.fullmatch(r"[0-9]+", run_attempt):
        raise ValueError("Invalid workflow run identity")
    cipher = (output / "moneyflow-database.tar.age").read_bytes()
    receipt = json.loads((output / "verification.json").read_text())
    digest = hashlib.sha256(cipher).hexdigest()
    if (not 0 < len(cipher) <= MAX_ARCHIVE_BYTES or not cipher.startswith(b"age-encryption.org/v1\n")
            or receipt.get("format_version") != 1
            or receipt.get("restore_verified_before_encryption") is not True
            or receipt.get("sha256") != digest or receipt.get("encrypted_bytes") != len(cipher)):
        raise ValueError("Invalid encrypted backup or verification receipt")
    now = now or dt.datetime.now(dt.timezone.utc)
    base = f"moneyflow-backup-{run_id}-{run_attempt}.tar.age"
    api = GitHub(repo, token)
    release = api.release()
    if not release.get("draft") or release.get("tag_name") != RELEASE_TAG:
        raise ValueError("Expected the private draft backup release")
    release_id = release["id"]
    assets = api.pages(f"releases/{release_id}/assets")
    if any(asset.get("name") in {base, base + ".sha256", base + ".verification.json"} for asset in assets):
        raise ValueError("This workflow attempt already has backup assets; refusing overwrite")
    receipt.update({"run_id": run_id, "run_attempt": run_attempt, "asset_name": base})
    # The receipt is uploaded last, after both ciphertext and checksum succeed.
    api.upload(release_id, base, cipher, "application/octet-stream")
    api.upload(release_id, base + ".sha256", f"{digest}  {base}\n".encode(), "text/plain")
    api.upload(release_id, base + ".verification.json", json.dumps(receipt).encode(), "application/json")
    assets = api.pages(f"releases/{release_id}/assets")
    delete_ids = retention_ids(assets, base, now)
    # Recheck draft status immediately before retention so a manually published
    # release is never modified by this automation.
    current = api.request(f"releases/{release_id}")
    if not current.get("draft") or current.get("tag_name") != RELEASE_TAG:
        raise ValueError("Backup release is no longer a draft; retention refused")
    for asset_id in delete_ids:
        api.request(f"releases/assets/{asset_id}", "DELETE")
    print("Encrypted backup stored in private draft release; completion receipt and retention verified.")


if __name__ == "__main__":
    try:
        publish(pathlib.Path(sys.argv[1]), os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"],
                os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"])
    except Exception:
        print("Draft backup upload failed; no release was published. Details suppressed to protect credentials.", file=sys.stderr)
        sys.exit(1)
