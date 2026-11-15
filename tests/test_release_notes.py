"""Host JVM tests for the release notes check. No Android or network."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReleaseNotesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix='updater-notes-')
        jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cls.java = str(jdk / 'bin/java') if jdk.exists() else shutil.which('java')
        javac = str(jdk / 'bin/javac') if jdk.exists() else shutil.which('javac')
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', cls.scratch.name,
                        str(ROOT / 'src/app/seamlessupdate/client/ReleaseNotes.java'),
                        str(ROOT / 'tests/ReleaseNotesTest.java')], check=True, capture_output=True, timeout=30)

    @classmethod
    def tearDownClass(cls): cls.scratch.cleanup()

    def scenario(self, name):
        value = subprocess.run([self.java, '-cp', self.scratch.name,
                                'app.seamlessupdate.client.ReleaseNotesTest', name], capture_output=True, timeout=10)
        self.assertEqual(value.returncode, 0, value.stderr)

    def test_file_names_and_cache_keys(self): self.scenario('names')
    def test_only_bounded_plain_text(self): self.scenario('text')
    def test_two_bullet_levels(self): self.scenario('lines')


if __name__ == '__main__': unittest.main()
