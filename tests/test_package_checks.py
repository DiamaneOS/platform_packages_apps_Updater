"""Host JVM tests for package metadata parsing and refusals. No Android, signatures or update_engine."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class PackageChecksTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix='updater-package-')
        jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cls.java = str(jdk / 'bin/java') if jdk.exists() else shutil.which('java')
        javac = str(jdk / 'bin/javac') if jdk.exists() else shutil.which('javac')
        subprocess.run([javac, '--release', '17', '-d', cls.scratch.name,
                        str(ROOT / 'src/app/seamlessupdate/client/PackageChecks.java'),
                        str(ROOT / 'tests/PackageChecksTest.java')], check=True, capture_output=True, timeout=30)

    @classmethod
    def tearDownClass(cls): cls.scratch.cleanup()

    def scenario(self, name):
        value = subprocess.run([self.java, '-cp', self.scratch.name,
                                'app.seamlessupdate.client.PackageChecksTest', name], capture_output=True, timeout=10)
        self.assertEqual(value.returncode, 0, value.stderr)

    def test_release_tool_metadata_parses(self): self.scenario('parse')
    def test_malformed_metadata_is_refused(self): self.scenario('malformed')
    def test_other_device_or_source_is_refused(self): self.scenario('device')
    def test_download_must_match_server_selector(self): self.scenario('server_target')

    def test_service_parses_packages_only_through_package_checks(self):
        service = (ROOT / 'src/app/seamlessupdate/client/Service.java').read_text()
        for key in ('post-timestamp', 'pre-device', 'ota-streaming-property-files', 'serialno'):
            self.assertNotIn(key, service)


if __name__ == '__main__': unittest.main()
