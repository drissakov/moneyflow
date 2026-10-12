import importlib.util
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("free_monitor", Path(__file__).resolve().parents[1] / "free-monitor.py")
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class FreeMonitorTests(unittest.TestCase):
    def test_https_public_root_is_normalized(self):
        self.assertEqual(monitor.api_url("https://moneyflow.vercel.app/"), ("https://moneyflow.vercel.app/", "moneyflow.vercel.app"))

    def test_private_credentials_and_placeholder_urls_are_rejected_without_echo(self):
        for url in ("http://moneyflow.vercel.app/", "https://user:never-print-this@moneyflow.vercel.app/", "https://moneyflow.vercel.app/?token=never-print-this", "https://example.com/", "https://127.0.0.1/", "https://moneyflow.vercel.app/path", "https://moneyflow.vercel.app:8443/"):
            with self.subTest(url=url):
                with self.assertRaises(monitor.CheckFailure) as result:
                    monitor.api_url(url)
                self.assertNotIn("never-print-this", str(result.exception))

    @patch.object(monitor.socket, "getaddrinfo", return_value=[(0, 0, 0, "", ("127.0.0.1", 443))])
    def test_private_dns_is_rejected(self, _):
        with self.assertRaises(monitor.CheckFailure):
            monitor.public_dns("moneyflow.vercel.app")

    @patch.object(monitor.time, "sleep")
    @patch.object(monitor, "json_request", return_value={"status": "not_ready"})
    def test_database_readiness_requires_expected_status(self, request, _):
        with self.assertRaises(monitor.CheckFailure):
            monitor.api_status("https://moneyflow.vercel.app/readyz", "ready")
        self.assertEqual(request.call_count, 3)

    def backup_fixture(self, hours=2, attempt=1):
        created = (datetime.now(timezone.utc) - timedelta(hours=hours)).isoformat()
        release = {"id": 7, "tag_name": "moneyflow-encrypted-backups", "draft": True, "published_at": None}
        base = f"moneyflow-backup-1-{attempt}.tar.age"
        assets = [{"name": base + suffix, "state": "uploaded", "size": 100, "created_at": created} for suffix in ("", ".sha256", ".verification.json")]
        runs = {"workflow_runs": [{"id": 1, "run_attempt": 1, "conclusion": "success", "head_branch": "main"}]}
        return [[release], assets, runs]

    def test_backup_requires_complete_fresh_private_release_group(self):
        replies = self.backup_fixture()
        with patch.object(monitor, "json_request", side_effect=replies):
            self.assertAlmostEqual(monitor.backup_age_hours("owner/repo", "never-print-token"), 2, places=1)

    def test_incomplete_or_failed_workflow_group_does_not_prove_backup_success(self):
        for invalid in ("missing-receipt", "empty-receipt", "skipped-run", "failed-run", "mismatched-attempt"):
            replies = self.backup_fixture(attempt=2 if invalid == "mismatched-attempt" else 1)
            if invalid == "missing-receipt":
                replies[1].pop()
            elif invalid == "empty-receipt":
                replies[1][-1]["size"] = 0
            elif invalid == "skipped-run":
                replies[2]["workflow_runs"] = []
            elif invalid == "failed-run":
                replies[2]["workflow_runs"][0]["conclusion"] = "failure"
            with patch.object(monitor, "json_request", side_effect=replies):
                with self.assertRaises(monitor.CheckFailure):
                    monitor.backup_age_hours("owner/repo", "never-print-token")

    def test_stale_backup_fails(self):
        replies = self.backup_fixture(hours=31)
        with patch.object(monitor, "json_request", side_effect=replies):
            with self.assertRaises(monitor.CheckFailure):
                monitor.backup_age_hours("owner/repo", "never-print-token")

    def test_published_backup_release_fails_immediately(self):
        replies = self.backup_fixture()
        replies[0][0]["draft"] = False
        with patch.object(monitor, "json_request", side_effect=replies) as request:
            with self.assertRaises(monitor.CheckFailure):
                monitor.backup_age_hours("owner/repo", "never-print-token")
            self.assertEqual(request.call_count, 1)

    def test_draft_visibility_and_release_pagination(self):
        fixture = self.backup_fixture()
        replies = [[{"tag_name": "another-release"}] * 100, fixture[0], fixture[1], fixture[2]]
        with patch.object(monitor, "json_request", side_effect=replies) as request:
            self.assertAlmostEqual(monitor.backup_age_hours("owner/repo", "never-print-token"), 2, places=1)
            self.assertIn("page=2", request.call_args_list[1].args[0])
        with patch.object(monitor, "json_request", return_value=[]):
            with self.assertRaises(monitor.CheckFailure) as error:
                monitor.backup_age_hours("owner/repo", "never-print-token")
            self.assertIn("token access", str(error.exception))


if __name__ == "__main__":
    unittest.main()
