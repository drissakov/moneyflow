import datetime as dt
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location("draft", pathlib.Path(__file__).resolve().parents[1] / "publish-draft-backup.py")
draft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(draft)
NOW = dt.datetime(2026, 10, 12, 12, tzinfo=dt.timezone.utc)
CURRENT = "moneyflow-backup-100-1.tar.age"


def group(base, day, start=1):
    timestamp = (NOW - dt.timedelta(days=day)).isoformat().replace("+00:00", "Z")
    return [{"id": start + index, "name": base + part, "size": 50,
             "state": "uploaded", "created_at": timestamp}
            for index, part in enumerate(("", ".sha256", ".verification.json"))]


class DraftBackupTests(unittest.TestCase):
    def test_published_release_refused(self):
        with self.assertRaises(ValueError):
            draft.find_draft([{"tag_name": draft.RELEASE_TAG, "draft": False}])

    def test_duplicate_release_refused(self):
        release = {"tag_name": draft.RELEASE_TAG, "draft": True}
        with self.assertRaises(ValueError):
            draft.find_draft([release, release])

    def test_unrelated_releases_ignored(self):
        self.assertIsNone(draft.find_draft([{"tag_name": "v1.0", "draft": False}]))

    def test_new_release_is_created_as_draft_without_generated_notes(self):
        api = draft.GitHub("owner/repo", "fixture-token")
        with mock.patch.object(api, "pages", return_value=[]), mock.patch.object(api, "request") as request:
            api.release()
        path, method, body = request.call_args.args
        self.assertEqual((path, method), ("releases", "POST"))
        payload = json.loads(body)
        self.assertIs(payload["draft"], True)
        self.assertIs(payload["generate_release_notes"], False)

    def test_existing_draft_reused_without_release_write(self):
        api = draft.GitHub("owner/repo", "fixture-token")
        existing = {"id": 42, "tag_name": draft.RELEASE_TAG, "draft": True}
        with mock.patch.object(api, "pages", return_value=[existing]), mock.patch.object(api, "request") as request:
            self.assertEqual(api.release(), existing)
        request.assert_not_called()

    def test_missing_receipt_blocks_all_retention(self):
        assets = group(CURRENT, 0)[:2] + group("moneyflow-backup-50-1.tar.age", 20, 10)
        with self.assertRaises(ValueError):
            draft.retention_ids(assets, CURRENT, NOW)

    def test_incomplete_upload_blocks_retention(self):
        assets = group(CURRENT, 0)
        assets[-1]["state"] = "starter"
        with self.assertRaises(ValueError):
            draft.retention_ids(assets, CURRENT, NOW)

    def test_foreign_names_preserved(self):
        assets = group(CURRENT, 0) + [{"id": 999, "name": "user-backup.tar.age", "size": 20, "state": "uploaded",
                                     "created_at": "2020-01-01T00:00:00Z"}]
        self.assertEqual(draft.retention_ids(assets, CURRENT, NOW), [])

    def test_old_complete_and_incomplete_groups_removed(self):
        old = group("moneyflow-backup-50-1.tar.age", 8, 10)
        orphan = group("moneyflow-backup-49-1.tar.age", 9, 20)[:1]
        self.assertCountEqual(draft.retention_ids(group(CURRENT, 0) + old + orphan, CURRENT, NOW), [10, 11, 12, 20])

    def test_only_seven_newest_complete_groups_retained(self):
        assets = group(CURRENT, 0)
        for index in range(1, 8):
            assets += group(f"moneyflow-backup-{100-index}-1.tar.age", index / 10, index * 10)
        self.assertCountEqual(draft.retention_ids(assets, CURRENT, NOW), [70, 71, 72])

    def fixture_output(self, directory):
        cipher = b"age-encryption.org/v1\nsynthetic-ciphertext"
        output = pathlib.Path(directory)
        (output / "moneyflow-database.tar.age").write_bytes(cipher)
        (output / "verification.json").write_text(json.dumps({"format_version": 1,
            "restore_verified_before_encryption": True, "sha256": hashlib.sha256(cipher).hexdigest(), "encrypted_bytes": len(cipher)}))
        return output

    def test_receipt_uploaded_last_and_no_publication_update(self):
        api = mock.Mock()
        api.release.return_value = {"id": 42, "draft": True, "tag_name": draft.RELEASE_TAG}
        api.pages.side_effect = [[], group(CURRENT, 0)]
        api.request.return_value = {"draft": True, "tag_name": draft.RELEASE_TAG}
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(draft, "GitHub", return_value=api):
            draft.publish(self.fixture_output(directory), "owner/repo", "test-token", "100", "1", NOW)
        uploaded = [call.args[1] for call in api.upload.call_args_list]
        self.assertEqual(uploaded, [CURRENT, CURRENT + ".sha256", CURRENT + ".verification.json"])
        self.assertEqual(api.request.call_args_list, [mock.call("releases/42")])
        receipt = json.loads(api.upload.call_args_list[-1].args[2])
        self.assertEqual((receipt["run_id"], receipt["run_attempt"]), ("100", "1"))

    def test_ciphertext_upload_failure_never_writes_receipt_or_prunes(self):
        api = mock.Mock()
        api.release.return_value = {"id": 42, "draft": True, "tag_name": draft.RELEASE_TAG}
        api.pages.return_value = []
        api.upload.side_effect = OSError("network failure")
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(draft, "GitHub", return_value=api):
            with self.assertRaises(OSError):
                draft.publish(self.fixture_output(directory), "owner/repo", "test-token", "100", "1", NOW)
        self.assertEqual(api.upload.call_count, 1)
        api.request.assert_not_called()

    def test_plaintext_misnamed_archive_rejected(self):
        with tempfile.TemporaryDirectory() as directory, mock.patch.object(draft, "GitHub") as api:
            output = self.fixture_output(directory)
            (output / "moneyflow-database.tar.age").write_bytes(b"plaintext database dump")
            with self.assertRaises(ValueError):
                draft.publish(output, "owner/repo", "test-token", "100", "1", NOW)
            api.assert_not_called()


if __name__ == "__main__":
    unittest.main()
