import importlib.util
import pathlib
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


connection = module("connection", "source-connection.py")


class SourceConnectionTests(unittest.TestCase):
    def parse(self, url):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "secret"
            path.write_text(url, encoding="utf-8")
            return connection.connection_values(str(path))

    def test_password_decoding_without_query_tls_downgrade(self):
        self.assertEqual(self.parse("postgresql://app:p%40ss%3Aword@ep-test.neon.tech/appdb?sslmode=disable"),
                         ["ep-test.neon.tech", "5432", "appdb", "app", "p@ss:word"])

    def test_custom_port(self):
        self.assertEqual(self.parse("postgres://app:password@db.example.com:6432/moneyflow")[1], "6432")

    def test_private_host_and_missing_credentials_rejected(self):
        for url in ("postgres://app:password@127.0.0.1/db", "postgres://app:password@restore-db/db",
                    "postgres://app@db.example.com/db", "https://app:password@db.example.com/db"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.parse(url)

    def test_control_character_in_password_rejected(self):
        with self.assertRaises(ValueError):
            self.parse("postgres://app:pass%0Aword@db.example.com/db")


if __name__ == "__main__":
    unittest.main()
