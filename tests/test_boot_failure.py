"""Host JVM tests for the update that did not start. No Android or service."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STUB = '''package android.content;
public interface SharedPreferences {
    String getString(String key, String def); long getLong(String key, long def); int getInt(String key, int def);
    Editor edit();
    interface Editor {
        Editor putString(String key, String value); Editor putLong(String key, long value);
        Editor putInt(String key, int value); Editor remove(String key); boolean commit();
    }
}'''


class BootFailureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory(prefix='updater-boot-')
        jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        cls.java = str(jdk / 'bin/java') if jdk.exists() else shutil.which('java')
        javac = str(jdk / 'bin/javac') if jdk.exists() else shutil.which('javac')
        stub = Path(cls.scratch.name) / 'android/content/SharedPreferences.java'
        stub.parent.mkdir(parents=True)
        stub.write_text(STUB)
        subprocess.run([javac, '--release', '17', '-d', cls.scratch.name, str(stub),
                        str(ROOT / 'src/app/seamlessupdate/client/BootFailure.java'),
                        str(ROOT / 'tests/BootFailureTest.java')], check=True, capture_output=True, timeout=30)

    @classmethod
    def tearDownClass(cls): cls.scratch.cleanup()

    def scenario(self, name):
        value = subprocess.run([self.java, '-cp', self.scratch.name,
                                'app.seamlessupdate.client.BootFailureTest', name], capture_output=True, timeout=10)
        self.assertEqual(value.returncode, 0, value.stderr)

    def test_started_update_leaves_nothing(self): self.scenario('started')
    def test_first_failure_retried_second_blocks(self): self.scenario('retry_once_then_block')
    def test_only_first_boot_after_install_counts(self): self.scenario('only_first_boot_counts')
    def test_started_update_ends_failures(self): self.scenario('started_ends_failures')
    def test_newer_build_resets_block(self): self.scenario('newer_build_resets')
    def test_manual_install_after_block(self): self.scenario('manual_install_after_block')
    def test_newer_build_installed_otherwise(self): self.scenario('other_install_runs')
    def test_older_build_flashed_is_no_failure(self): self.scenario('other_old_build_runs')
    def test_fallback_told_by_build_time(self): self.scenario('same_name_older_date')
    def test_package_without_incremental(self): self.scenario('no_incremental')
    def test_marked_boot_clears(self): self.scenario('marked_clears')
    def test_fallback_after_hook_counts(self): self.scenario('fallback_after_hook_counts')
    def test_second_fallback_after_hook_blocks(self): self.scenario('second_fallback_after_hook_blocks')
    def test_block_after_hook_ends(self): self.scenario('block_after_hook_ends')


if __name__ == '__main__': unittest.main()
