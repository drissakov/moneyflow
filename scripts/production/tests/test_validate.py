import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("validate", Path(__file__).resolve().parents[1] / "validate.py")
validation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validation)


class DeployValidationTests(unittest.TestCase):
    def fixture(self, root):
        environment = root / ".env.production"
        environment.write_text("DOMAIN=moneyflow.vercel.app\nACME_EMAIL=owner@moneyflow.dev\nPOSTGRES_USER=moneyflow\nPOSTGRES_DB=moneyflow\nSESSION_TTL=168h\nRESTIC_REPOSITORY=s3:https://account.r2.cloudflarestorage.com/backups/moneyflow\n")
        environment.chmod(0o600)
        secrets = root / "secrets"
        secrets.mkdir(mode=0o700)
        password = "a" * 64
        values = {
            "postgres_password": password,
            "database_url": f"postgres://moneyflow:{password}@db:5432/moneyflow?sslmode=disable",
            "restic_password": "b" * 64,
            "restore_db_password": "c" * 64,
            "grafana_admin_password": "d" * 64,
            "backup_s3_access_key": "access-key",
            "backup_s3_secret_key": "never-echo-this-secret",
        }
        for name, value in values.items():
            path = secrets / name
            path.write_text(value)
            path.chmod(0o444)
        return environment

    def change_env(self, path, before, after):
        path.write_text(path.read_text().replace(before, after))

    def test_complete_private_configuration_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            validation.check(root)

    def test_inline_credentials_are_rejected_without_echo(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.production"
            path.write_text("DATABASE_URL=never-echo-this-secret\n")
            with self.assertRaises(ValueError) as result:
                validation.env_file(path)
            self.assertNotIn("never-echo-this-secret", str(result.exception))

    def test_duplicate_keys_and_shell_expansion_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env.production"
            for content in ("DOMAIN=a\nDOMAIN=b\n", "DOMAIN=$(id)\n", "DOMAIN=`id`\n"):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    validation.env_file(path)

    def test_local_backup_endpoint_and_mismatched_database_are_rejected(self):
        for invalid in ("local-backup", "mismatched-password"):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                environment = self.fixture(root)
                if invalid == "local-backup":
                    self.change_env(environment, "account.r2.cloudflarestorage.com", "127.0.0.1")
                else:
                    path = root / "secrets/database_url"
                    path.chmod(0o644)
                    path.write_text("postgres://moneyflow:never-echo-this-secret@db:5432/moneyflow?sslmode=disable")
                    path.chmod(0o444)
                with self.assertRaises(ValueError) as result:
                    validation.check(root)
                self.assertNotIn("never-echo-this-secret", str(result.exception))

    def test_old_compose_plugin_is_rejected(self):
        with self.assertRaises(ValueError):
            validation.versions("24.0.0", "v2.23.3")
        validation.versions("29.0.0", "v2.40.1")


if __name__ == "__main__":
    unittest.main()
