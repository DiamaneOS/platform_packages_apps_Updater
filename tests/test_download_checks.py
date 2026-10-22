"""Host JVM tests for untrusted selector and resume handling. No Android or network."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DownloadChecksTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix='updater-checks-')
        jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cls.java = str(jdk / 'bin/java') if jdk.exists() else shutil.which('java')
        javac = str(jdk / 'bin/javac') if jdk.exists() else shutil.which('javac')
        subprocess.run([javac, '--release', '17', '-d', cls.scratch.name,
                        str(ROOT / 'src/app/seamlessupdate/client/DownloadChecks.java'),
                        str(ROOT / 'tests/DownloadChecksTest.java')], check=True, capture_output=True, timeout=30)

    @classmethod
    def tearDownClass(cls): cls.scratch.cleanup()

    def scenario(self, name):
        value = subprocess.run([self.java, '-cp', self.scratch.name,
                                'app.seamlessupdate.client.DownloadChecksTest', name], capture_output=True, timeout=10)
        self.assertEqual(value.returncode, 0, value.stderr)

    def test_selector_hints_never_supply_urls(self): self.scenario('metadata')
    def test_selector_bound_and_encoding(self): self.scenario('metadata_bounds')
    def test_timestamp_ascii_and_long_range(self): self.scenario('metadata_timestamp')
    def test_identity_fails_and_older_is_up_to_date(self): self.scenario('identity_and_downgrade')
    def test_exact_range_before_append(self): self.scenario('range')
    def test_length_bound_and_overflow(self): self.scenario('length_bounds')


if __name__ == '__main__': unittest.main()
