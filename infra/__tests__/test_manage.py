import importlib.util
import contextlib
import io
import os
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'manage.py'


class LocalConfigurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('leaflet_infra_manage', MODULE)
        cls.manage = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.manage)

    def test_env_values_are_literal_and_host_environment_wins(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sentinel = root / 'should-not-exist'
            (root / '.env').write_text(
                "DB_PASSWORD='literal $value # with spaces'\n"
                'DB_PORT=5432\n'
                f"PAYLOAD='$(touch {sentinel})'\n", encoding='utf-8')
            env = self.manage.load_env(root, {'DB_PORT': '6543'})
            self.assertEqual(env['DB_PASSWORD'], 'literal $value # with spaces')
            self.assertEqual(env['DB_PORT'], '6543')
            self.assertEqual(env['PAYLOAD'], f'$(touch {sentinel})')
            self.assertFalse(sentinel.exists())

    def test_init_env_creates_private_credentials_and_preserves_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.env.example').write_text('DB_PASSWORD=replace-with-your-local-password\n')
            self.manage.init_env(root)
            before = (root / '.env').read_bytes()
            self.assertNotIn(b'replace-with-your-local-password', before)
            self.assertGreater(len(before.split(b'=', 1)[1].strip()), 24)
            if os.name != 'nt':
                self.assertEqual((root / '.env').stat().st_mode & 0o777, 0o600)
            self.manage.init_env(root)
            self.assertEqual((root / '.env').read_bytes(), before)

    def test_invalid_env_line_is_rejected_without_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.env').write_text('export $(touch should-not-exist)\n')
            with self.assertRaises(ValueError):
                self.manage.load_env(root, {})

    def test_editor_setup_is_private_preserves_db_and_does_not_print_password(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.env.example').write_text('DB_PASSWORD=replace-with-your-local-password\nEDITING_ENABLED=false\nEDITOR_PASSWORD=\n')
            self.manage.init_env(root)
            database_password = self.manage.load_env(root, {})['DB_PASSWORD']
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                self.manage.init_editor(root)
            env = self.manage.load_env(root, {})
            self.assertEqual(env['EDITING_ENABLED'], 'true')
            self.assertEqual(env['EDITOR_USERNAME'], 'editor')
            self.assertGreaterEqual(len(env['EDITOR_PASSWORD']), 32)
            self.assertNotIn(env['EDITOR_PASSWORD'], output.getvalue())
            self.assertEqual(env['DB_PASSWORD'], database_password)
            before = (root / '.env').read_bytes()
            self.manage.init_editor(root)
            self.assertEqual((root / '.env').read_bytes(), before)
            if os.name != 'nt':
                self.assertEqual((root / '.env').stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()
