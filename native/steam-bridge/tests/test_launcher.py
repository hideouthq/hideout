import json
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "launcher"


class LauncherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hideout-launch-test-")
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle with spaces"
        self.bundle.mkdir()
        self.steam = self.root / "fake-Steam"
        self.steam.mkdir()
        (self.steam / "steam.sh").write_text("fake upstream launcher fixture\n")
        for name in ("launch.sh", "final-launch.sh", "client-container.sh"):
            shutil.copy2(SOURCE / name, self.bundle / name)
            (self.bundle / name).chmod(0o700)
        (self.bundle / "libhideout_steam_bridge.so").write_bytes(b"deliberately not a library")
        (self.bundle / "bridge_probe").write_text("#!/bin/sh\nexit 0\n")
        (self.bundle / "SHA256SUMS").write_text("".join(
            hashlib.sha256((self.bundle / name).read_bytes()).hexdigest() + "  " + name + "\n"
            for name in ("libhideout_steam_bridge.so", "bridge_probe", "launch.sh", "final-launch.sh", "client-container.sh")))
        digest = hashlib.sha256((self.bundle / "SHA256SUMS").read_bytes()).hexdigest()
        old = self.bundle.rename(self.root / "prepared")
        self.bundle.mkdir()
        self.bundle = old.rename(self.bundle / digest)
        (self.bundle / "endpoint-path").write_text(str(self.root / "endpoint") + "\n")
        (self.bundle / "steam-root").write_text(str(self.steam) + "\n")
        (self.bundle / "architecture").write_text("ubuntu12_32\n")
        (self.bundle / "package").write_text("Desktop\n")
        self.result = self.root / "result.json"
        self.program = self.root / "record.py"
        self.program.write_text("#!/usr/bin/env python3\nimport os,json,sys\n"
                                "open(os.environ['HB_TEST_RESULT'],'w').write(json.dumps({'args':sys.argv[1:],"
                                "'debugger':os.getenv('DEBUGGER'),'preload':os.getenv('LD_PRELOAD'),"
                                "'marker':os.getenv('HB_UNRELATED'),'endpoint':os.getenv('HIDEOUT_STEAM_BRIDGE_ENDPOINT')}))\n")
        self.program.chmod(0o700)
        self.env = dict(os.environ, HB_TEST_RESULT=str(self.result), XDG_RUNTIME_DIR=str(self.root / "runtime"))
        self.env.pop("DEBUGGER", None)
        self.env.pop("LD_PRELOAD", None)
        Path(self.env["XDG_RUNTIME_DIR"]).mkdir(mode=0o700)

    def tearDown(self):
        self.temp.cleanup()

    def run_launch(self):
        subprocess.run(["bash", str(self.bundle / "launch.sh"), "--", str(self.program), "a b", "steam://test"],
                       env=self.env, check=True, capture_output=True, timeout=5)
        return json.loads(self.result.read_text())

    def test_spaces_and_arguments_survive_outer_wrapper(self):
        result = self.run_launch()
        self.assertEqual(result["args"], ["a b", "steam://test"])
        self.assertNotIn(" ", result["debugger"])
        self.assertIsNone(result["preload"], "The shell/upstream launcher must never receive our preload")

    def test_existing_debugger_is_not_replaced(self):
        self.env["DEBUGGER"] = "user-debugger"
        self.assertEqual(self.run_launch()["debugger"], "user-debugger")

    def test_disabled_launcher_is_plain(self):
        (self.bundle / "disabled").touch()
        result = self.run_launch()
        self.assertIsNone(result["debugger"])
        self.assertIsNone(result["preload"])

    def test_unknown_executable_falls_back_without_preloading(self):
        self.env["HIDEOUT_STEAM_BRIDGE_LIBRARY"] = str(self.bundle / "libhideout_steam_bridge.so")
        self.env["HIDEOUT_STEAM_BRIDGE_ENDPOINT"] = str(self.root / "endpoint")
        subprocess.run(["bash", str(self.bundle / "final-launch.sh"), str(self.program), "unchanged"],
                       env=self.env, check=True, capture_output=True, timeout=5)
        result = json.loads(self.result.read_text())
        self.assertEqual(result["args"], ["unchanged"])
        self.assertIsNone(result["preload"])

    def test_damaged_bundle_uses_plain_launcher(self):
        (self.bundle / "libhideout_steam_bridge.so").write_bytes(b"changed")
        result = self.run_launch()
        self.assertIsNone(result["debugger"])
        self.assertIsNone(result["preload"])

    def run_final(self, report, code=0):
        probe = self.bundle / "bridge_probe"
        probe.write_text("#!/bin/sh\nprintf '%s\\n' '" + json.dumps(report, separators=(",", ":")) + "'\nexit " + str(code) + "\n")
        probe.chmod(0o700)
        manifest = self.bundle / "SHA256SUMS"
        names = [line.split("  ", 1)[1] for line in manifest.read_text().splitlines()]
        manifest.write_text("".join(hashlib.sha256((self.bundle / name).read_bytes()).hexdigest() + "  " + name + "\n" for name in names))
        digest = hashlib.sha256(manifest.read_bytes()).hexdigest()
        self.bundle = self.bundle.rename(self.bundle.parent / digest)
        binary = self.steam / "ubuntu12_32" / "steam"
        binary.parent.mkdir(exist_ok=True)
        shutil.copy2(self.program, binary)
        env = dict(self.env, HIDEOUT_STEAM_BRIDGE_ENDPOINT=str(self.root / "endpoint"),
                   HIDEOUT_STEAM_BRIDGE_LIBRARY=str(self.bundle / "libhideout_steam_bridge.so"),
                   HIDEOUT_STEAM_BRIDGE_PROBE=str(self.bundle / "bridge_probe"),
                   LD_PRELOAD="libm.so.6", HB_UNRELATED="preserved")
        subprocess.run(["bash", str(self.bundle / "final-launch.sh"), str(binary), "a b", "steam://test"],
                       env=env, check=True, capture_output=True, timeout=5)
        return json.loads(self.result.read_text())

    def test_probe_failure_starts_ordinary_steam_and_preserves_unrelated_environment(self):
        result = self.run_final({"version": 1, "compatible": False, "reason": "map_layout_drift", "buildId": "changed"}, 1)
        self.assertEqual(result["preload"], "libm.so.6")
        self.assertEqual(result["marker"], "preserved")
        self.assertIsNone(result["endpoint"])
        self.assertEqual(result["args"], ["a b", "steam://test"])

    def test_successful_report_preloads_only_the_final_target(self):
        result = self.run_final({"version": 1, "compatible": True, "reason": "compatible", "buildId": "any-build"})
        self.assertEqual(result["preload"], str(Path(self.env["XDG_RUNTIME_DIR"]) / ("hideout-steam-" + str(os.getuid())) / "loaded-bridge.so") + ":libm.so.6")
        self.assertEqual(result["args"], ["a b", "steam://test"])
        self.assertEqual(result["marker"], "preserved")

    def test_unknown_report_version_falls_back(self):
        result = self.run_final({"version": 2, "compatible": True, "reason": "compatible"})
        self.assertEqual(result["preload"], "libm.so.6")
        self.assertIsNone(result["endpoint"])

    def test_x64_selection_falls_back_and_launcher_updates_are_accepted(self):
        marker = self.steam / ".steam-enable-steamrt64-client"
        marker.touch()
        self.assertIsNone(self.run_launch()["debugger"])
        marker.unlink()
        self.env["STEAM_FORCE_CLIENT"] = "steamrt64"
        self.assertIsNone(self.run_launch()["debugger"])
        self.env.pop("STEAM_FORCE_CLIENT")
        (self.steam / "steam.sh").write_text("updated launcher\n")
        self.assertIsNotNone(self.run_launch()["debugger"])

    def test_container_command_wraps_each_final_client_across_restart(self):
        (self.bundle / "architecture").write_text("steamrt64\n")
        (self.steam / ".steam-enable-steamrt64-client").touch()
        runtime = self.steam / "steamrt64/pv-runtime/steam-runtime-steamrt"
        commands = runtime / "pressure-vessel/libexec/steam-runtime-tools-0"
        commands.mkdir(parents=True)
        (runtime / "run").write_text("#!/bin/sh\nexit 0\n")
        (runtime / "run").chmod(0o700)
        container = commands / "srt-client-container"
        container.write_text('#!/bin/bash\nset -eu\n[[ $1 == --pv-entry-point && -x $2 ]]\nshift 2\n'
                             '[[ $1 == -- ]]\nshift\nexec "$@"\n')
        container.chmod(0o700)
        binary = self.steam / "steamrt64/steam"
        binary.write_text('#!/usr/bin/env python3\nimport os,json,sys\n'
                          'first=not os.path.exists(os.environ["HB_TEST_RESULT"])\n'
                          'with open(os.environ["HB_TEST_RESULT"],"a") as f:\n'
                          ' f.write(json.dumps({"args":sys.argv[1:],"preload":os.getenv("LD_PRELOAD"),'
                          '"marker":os.getenv("HB_UNRELATED")})+"\\n")\n'
                          'sys.exit(42 if first else 0)\n')
        binary.chmod(0o700)
        probe = self.bundle / "bridge_probe"
        probe.write_text('#!/bin/sh\nprintf \'{"version":1,"compatible":true,"reason":"compatible"}\\n\'\n')
        probe.chmod(0o700)
        manifest = self.bundle / "SHA256SUMS"
        names = [line.split("  ", 1)[1] for line in manifest.read_text().splitlines()]
        manifest.write_text("".join(hashlib.sha256((self.bundle / n).read_bytes()).hexdigest() + "  " + n + "\n" for n in names))
        self.bundle = self.bundle.rename(self.bundle.parent / hashlib.sha256(manifest.read_bytes()).hexdigest())
        upstream = self.root / "upstream.sh"
        upstream.write_text('#!/bin/bash\nset -u\nexport HB_UNRELATED=upstream-initialized\n'
                            'for i in 1 2; do\n'
                            ' "$STEAM_RUNTIME_STEAMRT/pressure-vessel/libexec/steam-runtime-tools-0/srt-client-container" -- "$HB_TEST_STEAM" "$@"\n'
                            ' result=$?\n case "$i:$result" in 1:42) ;; 2:0) exit 0;; *) exit 99;; esac\n'
                            'done\nexit 99\n')
        upstream.chmod(0o700)
        env = dict(self.env, HB_TEST_STEAM=str(binary))
        subprocess.run([str(self.bundle / "launch.sh"), "--", str(upstream), "a b", "steam://test"],
                       env=env, check=True, capture_output=True, timeout=5)
        results = [json.loads(line) for line in self.result.read_text().splitlines()]
        self.assertEqual(len(results), 2)
        for result in results:
            self.assertEqual(result["args"], ["a b", "steam://test"])
            self.assertEqual(result["marker"], "upstream-initialized")
            self.assertTrue(result["preload"].endswith("/loaded-bridge.so"))
        self.assertNotIn('final-launch', container.read_text())


if __name__ == "__main__":
    unittest.main()
