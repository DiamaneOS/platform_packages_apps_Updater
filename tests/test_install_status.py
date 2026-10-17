"""Host JVM test for the install-from-storage reservation. No Android or service."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return new Looper();} }',
    'android/os/Handler.java': 'package android.os; public class Handler { public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;} }',
}


class InstallStatusTests(unittest.TestCase):
    def test_second_pick_refused_until_service_finishes(self):
        with tempfile.TemporaryDirectory(prefix='updater-status-') as out:
            jdk = Path(os.environ.get('DOWNLOAD_POLICY_JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
            java = str(jdk / 'bin/java') if jdk.exists() else shutil.which('java')
            javac = str(jdk / 'bin/javac') if jdk.exists() else shutil.which('javac')
            for name, body in STUBS.items():
                path = Path(out) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body)
            sources = [str(p) for p in Path(out).rglob('*.java')] + [
                str(ROOT / 'src/app/seamlessupdate/client/InstallStatus.java'), str(ROOT / 'tests/InstallStatusTest.java')]
            subprocess.run([javac, '--release', '17', '-d', out] + sources, check=True, capture_output=True, timeout=30)
            result = subprocess.run([java, '-cp', out, 'app.seamlessupdate.client.InstallStatusTest'], capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == '__main__': unittest.main()
