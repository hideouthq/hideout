import json
from pathlib import Path
import subprocess
import tempfile
import unittest

BUILD = Path(__file__).resolve().parents[3] / "build" / "steam-bridge-i386"


@unittest.skipUnless((BUILD / "bridge_probe").is_file(), "Build the i386 native tests first")
class ProbeTest(unittest.TestCase):
    def probe(self, executable, module, library):
        result = subprocess.run([str(BUILD / "bridge_probe"), str(executable), str(module), str(library)],
                                capture_output=True, text=True, timeout=10)
        report = json.loads(result.stdout)
        self.assertEqual(report["version"], 1)
        self.assertEqual(report["compatible"], result.returncode == 0)
        return report

    def test_synthetic_module_and_isolated_library_load(self):
        module = BUILD / "libabi_fixture.so"
        report = self.probe(module, module, BUILD / "libhideout_steam_bridge.so")
        self.assertTrue(report["compatible"])
        self.assertEqual(report["reason"], "compatible")
        self.assertTrue(report["buildId"])

    def test_unsupported_target_and_failed_load_have_stable_reports(self):
        module = BUILD / "libabi_fixture.so"
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / "not-elf"
            bad.write_bytes(b"not an executable or library")
            self.assertEqual(self.probe(bad, module, bad)["reason"], "unsupported_target")
            self.assertEqual(self.probe(module, bad, bad)["reason"], "invalid_elf")
            self.assertEqual(self.probe(module, module, bad)["reason"], "runtime_load_failed")

    def test_snap_probe_rejects_an_unconfined_runtime(self):
        module = BUILD / "libabi_fixture.so"
        result = subprocess.run([str(BUILD / "bridge_probe"), str(module), str(module),
                                 str(BUILD / "libhideout_steam_bridge.so"), "--require-snap"],
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(json.loads(result.stdout)["compatible"])


if __name__ == "__main__":
    unittest.main()
