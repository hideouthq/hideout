import base64
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import make_feed

HERE = Path(__file__).resolve().parent
MAKE_FEED = HERE / "make_feed.py"
SIGN_FEED = HERE / "sign_feed.sh"
ROOT = HERE.parents[1]


def entry(version="1.4.0", **fields):
    return {"version": version, "date": "2026-11-02", "kind": "feature", "new": [{"area": "Library", "text": "A thing."}], **fields}


class Release(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        self.notes = self.dir / "notes"
        (self.notes / "art").mkdir(parents=True)
        self.packages = self.dir / "packages"
        self.packages.mkdir()
        self.out = self.dir / "out"
        self.module = self.dir / "module.yaml"
        self.module.write_text("dependencies:\n  - foo:\n    version: 9.9.9\nplugins:\n  native-package:\n    enabled: true\n    version: 1.4.0\n    name: Hideout\n")
        for size, name in enumerate(make_feed.package_files("1.4.0").values(), start=3):
            (self.packages / name).write_bytes(b"x" * size)
        (self.packages / "Hideout-1.4.0-Setup.exe").write_bytes(b"setup")
        self.write_notes(entry())

    def write_notes(self, *versions, **top):
        (self.notes / "releases.json").write_text(json.dumps({"schema": 1, "versions": list(versions), **top}))

    def run_tool(self, *extra, version="1.4.0", tag="v1.4.0"):
        return subprocess.run(
            [sys.executable, MAKE_FEED, "--version", version, "--tag", tag, "--notes", self.notes, "--module", self.module, *extra],
            capture_output=True, text=True)

    def run_full(self, *extra, **kwargs):
        return self.run_tool("--repo", "owner/name", "--packages", self.packages, "--out", self.out, *extra, **kwargs)

    def feed(self):
        return json.loads((self.out / "releases.json").read_text())

    def assert_fails(self, result, message):
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(message, result.stderr)
        self.assertFalse((self.out / "releases.json").exists())


class FeedOutputTests(Release):
    def test_the_feed_derives_latest_notes_url_and_a_download_per_format(self):
        result = self.run_full()
        self.assertEqual(0, result.returncode, result.stderr)
        feed = self.feed()
        self.assertEqual((1, "1.4.0", "https://github.com/owner/name/releases"), (feed["schema"], feed["latest"], feed["notesUrl"]))
        download = feed["versions"][0]["download"]
        self.assertEqual({"msi", "appimage", "deb", "rpm"}, set(download))
        self.assertEqual({
            "url": "https://github.com/owner/name/releases/download/v1.4.0/hideout-1.4.0-1.x86_64.rpm",
            "size": 6,
            "sha256": hashlib.sha256(b"x" * 6).hexdigest(),
        }, download["rpm"])
        self.assertTrue(download["appimage"]["url"].endswith("/Hideout-1.4.0-x86_64.AppImage"))
        self.assertTrue(download["deb"]["url"].endswith("/hideout_1.4.0_amd64.deb"))
        self.assertTrue(download["msi"]["url"].endswith("/Hideout-1.4.0.msi"))

    def test_only_the_latest_version_has_a_download(self):
        self.write_notes(entry(), entry("1.3.2", kind="fixes"))
        self.assertEqual(0, self.run_full().returncode)
        self.assertNotIn("download", self.feed()["versions"][1])

    def test_a_local_feed_points_at_its_own_server(self):
        self.assertEqual(0, self.run_full("--download-base", "http://127.0.0.1:8000/").returncode)
        self.assertEqual("http://127.0.0.1:8000/Hideout-1.4.0.msi", self.feed()["versions"][0]["download"]["msi"]["url"])

    def test_every_listed_versions_art_uses_the_website_without_release_assets(self):
        for name in ("1.4.0-a@2x.webp", "1.3.0-b@2x.webp", "unused.webp"):
            (self.notes / "art" / name).write_bytes(name.encode())
        self.write_notes(
            entry(highlights=[{"title": "A", "text": "Text.", "image": "1.4.0-a@2x.webp"}]),
            entry("1.3.0", highlights=[{"title": "B", "text": "Text.", "image": "1.3.0-b@2x.webp"}]))
        self.assertEqual(0, self.run_full().returncode)
        self.assertEqual(["notes.md", "releases.json"], sorted(p.name for p in self.out.iterdir()))
        self.assertEqual(
            ["https://hideouthq.gg/release-art/1.4.0-a@2x.webp", "https://hideouthq.gg/release-art/1.3.0-b@2x.webp"],
            [version["highlights"][0]["image"] for version in self.feed()["versions"]])

    def test_notes_have_headings_in_order_area_items_and_inline_images(self):
        (self.notes / "art" / "a@2x.webp").write_bytes(b"a")
        self.write_notes(entry(
            highlights=[{"title": "Skins", "text": "Sets of skins.", "image": "a@2x.webp"}],
            fixed=[{"area": "Install", "text": "Installs no longer stop."}],
            improved=[{"area": "Stats", "text": "Ranks are faster."}]))
        self.assertEqual(0, self.run_full().returncode)
        self.assertEqual(
            "### Skins\n\nSets of skins.\n\n![Skins](https://hideouthq.gg/release-art/a@2x.webp)\n\n"
            "## New\n\n- **Library:** A thing.\n\n## Improved\n\n- **Stats:** Ranks are faster.\n\n## Fixed\n\n- **Install:** Installs no longer stop.\n",
            (self.out / "notes.md").read_text())

    def test_notes_list_only_the_released_version_and_skip_empty_groups(self):
        self.write_notes(entry(), entry("1.3.0", new=[{"area": "Stats", "text": "Old."}]))
        self.assertEqual(0, self.run_full().returncode)
        self.assertEqual("## New\n\n- **Library:** A thing.\n", (self.out / "notes.md").read_text())

    def test_versions_compare_as_numbers(self):
        self.write_notes(entry("1.10.0"), entry("1.9.0"))
        self.module.write_text("plugins:\n  native-package:\n    version: 1.10.0\n")
        for name in make_feed.package_files("1.10.0").values():
            (self.packages / name).write_bytes(b"x")
        (self.packages / "Hideout-1.10.0-Setup.exe").write_bytes(b"setup")
        self.assertEqual(0, self.run_full(version="1.10.0", tag="v1.10.0").returncode)


class RefusalTests(Release):
    def assert_notes_refused(self, message, *versions, **top):
        self.write_notes(*versions, **top)
        self.assert_fails(self.run_full(), message)

    def test_a_version_without_an_entry(self):
        self.assert_notes_refused("version 1.4.0 has no entry", entry("1.3.0"))

    def test_a_first_entry_that_is_another_version(self):
        self.assert_notes_refused("the first entry is 1.3.0", entry("1.3.0"), entry())

    def test_versions_out_of_order_or_repeated(self):
        self.assert_notes_refused("newest first", entry(), entry("1.2.0"), entry("1.3.0"))
        self.assert_notes_refused("newest first", entry(), entry())

    def test_a_version_that_differs_from_module_yaml(self):
        self.module.write_text("plugins:\n  native-package:\n    version: 1.3.0\n")
        self.assert_fails(self.run_full(), "differs from 1.3.0")

    def test_a_module_without_a_plugin_version(self):
        self.module.write_text("dependencies:\n  - foo:\n    version: 1.4.0\nplugins:\n  native-package:\n    enabled: true\nsettings:\n  version: 1.4.0\n")
        self.assert_fails(self.run_full(), "no native-package version")

    def test_a_tag_that_is_not_the_version(self):
        self.assert_fails(self.run_full(tag="v1.4.1"), "tag v1.4.1 is not v1.4.0")

    def test_a_missing_or_empty_package(self):
        name = "hideout_1.4.0_amd64.deb"
        (self.packages / name).write_bytes(b"")
        self.assert_fails(self.run_full(), f"package {name} is empty")
        (self.packages / name).unlink()
        self.assert_fails(self.run_full(), f"package {name} is missing")

    def test_a_missing_or_empty_exe_installer(self):
        name = "Hideout-1.4.0-Setup.exe"
        (self.packages / name).write_bytes(b"")
        self.assert_fails(self.run_full(), f"package {name} is empty")
        (self.packages / name).unlink()
        self.assert_fails(self.run_full(), f"package {name} is missing")

    def test_a_malformed_date(self):
        for bad in ("2026-13-01", "2026-02-30", "20261102", "2 Nov 2026", None):
            with self.subTest(date=bad):
                self.assert_notes_refused("is not a real yyyy-mm-dd date", entry(date=bad))

    def test_more_than_two_highlights(self):
        one = {"title": "A", "text": "Text."}
        self.assert_notes_refused("has 3 highlights", entry(highlights=[one, one, one]))

    def test_highlights_on_a_fixes_release(self):
        self.assert_notes_refused("only a feature update", entry(kind="fixes", highlights=[{"title": "A", "text": "Text."}]))

    def test_an_image_the_app_would_drop(self):
        for bad in ("art/a.webp", "https://example.test/a.webp", "a b.webp", "a~b.webp", "a#b.webp", ".a.webp", "a..webp", 7):
            with self.subTest(image=bad):
                self.assert_notes_refused("must be a bare file name", entry(highlights=[{"title": "A", "text": "Text.", "image": bad}]))

    def test_an_image_that_is_not_in_art(self):
        self.assert_notes_refused("image 'a.webp' is missing", entry(highlights=[{"title": "A", "text": "Text.", "image": "a.webp"}]))

    def test_an_image_named_like_a_release_file(self):
        for name in ("releases.json", "Hideout-1.4.0-Setup.exe"):
            with self.subTest(image=name):
                (self.notes / "art" / name).write_bytes(b"x")
                self.assert_notes_refused("would collide", entry(highlights=[{"title": "A", "text": "Text.", "image": name}]))

    def test_fields_make_feed_writes(self):
        self.assert_notes_refused("latest is written by make_feed", entry(), latest="1.4.0")
        self.assert_notes_refused("download is written by make_feed", entry(download={}))

    def test_a_misspelled_group(self):
        self.assert_notes_refused("improvd is written by make_feed or unknown", entry(improvd=[]))

    def test_a_version_with_nothing_to_say(self):
        self.assert_notes_refused("has nothing under", {"version": "1.4.0", "date": "2026-11-02", "kind": "feature"})

    def test_a_broken_item_or_unknown_area(self):
        self.assert_notes_refused("needs an area and a text", entry(new=[{"area": "Library"}]))
        self.assert_notes_refused("area 'Mods' is not one of", entry(new=[{"area": "Mods", "text": "A thing."}]))

    def test_a_bad_kind_or_schema(self):
        self.assert_notes_refused("kind 'minor'", entry(kind="minor"))
        self.assert_notes_refused("schema 2", entry(), schema=2)

    def test_notes_that_are_not_json_or_not_there(self):
        (self.notes / "releases.json").write_text("{")
        self.assert_fails(self.run_full(), "not valid JSON")
        (self.notes / "releases.json").unlink()
        self.assert_fails(self.run_full(), "cannot read")

    def test_an_output_folder_with_files_in_it(self):
        self.out.mkdir()
        (self.out / "stale.webp").write_bytes(b"x")
        self.assert_fails(self.run_full(), "must be an empty or new folder")

    def test_every_problem_is_reported_at_once(self):
        self.write_notes(entry(date="soon", kind="minor"))
        result = self.run_full(tag="v2")
        self.assertEqual(1, result.returncode)
        for message in ("tag v2", "date 'soon'", "kind 'minor'"):
            self.assertIn(message, result.stderr)

    def test_a_full_run_needs_the_repo_packages_and_out(self):
        result = self.run_tool()
        self.assertEqual(2, result.returncode)
        self.assertIn("--repo, --packages and --out are required", result.stderr)


class CheckTests(Release):
    def test_check_needs_no_packages_and_writes_nothing(self):
        result = self.run_tool("--check")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertFalse(self.out.exists())

    def test_check_catches_the_mistakes_a_build_would_waste_time_on(self):
        self.assert_fails(self.run_tool("--check", tag="v1.4.1"), "tag v1.4.1")
        self.assert_fails(self.run_tool("--check", version="1.5.0", tag="v1.5.0"), "differs from 1.4.0")
        self.write_notes(entry("1.3.0"))
        self.assert_fails(self.run_tool("--check"), "version 1.4.0 has no entry")

    def test_the_repositorys_own_notes_match_its_module_version(self):
        version = make_feed.module_version((ROOT / "app/desktopApp/module.yaml").read_text())
        result = subprocess.run(
            [sys.executable, MAKE_FEED, "--check", "--version", version, "--tag", f"v{version}", "--notes", ROOT / "release-notes"],
            capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


class SignTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.dir = Path(temp.name)
        self.key = self.dir / "throwaway.pem"
        self.public = self.dir / "public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519", "-out", self.key], check=True)
        subprocess.run(["openssl", "pkey", "-in", self.key, "-pubout", "-out", self.public], check=True)
        der = subprocess.run(["openssl", "pkey", "-pubin", "-in", self.public, "-outform", "DER"], check=True, capture_output=True).stdout
        self.env = {**os.environ, "RELEASE_FEED_PUBLIC_KEY": base64.b64encode(der).decode()}
        self.feed = self.dir / "releases.json"
        self.feed.write_text('{"schema":1}\n')
        self.signature = self.dir / "releases.json.sig"

    def sign(self, env=None, key=None):
        return subprocess.run([SIGN_FEED, key or self.key, self.feed], capture_output=True, text=True, env=env or self.env)

    def verify(self):
        raw = self.dir / "raw"
        raw.write_bytes(base64.b64decode(self.signature.read_bytes()))
        return subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-inkey", self.public, "-sigfile", raw, "-in", self.feed],
                              capture_output=True).returncode

    def test_the_signature_verifies_over_the_exact_bytes(self):
        self.assertEqual(0, self.sign().returncode)
        self.assertEqual(0, self.verify())
        self.feed.write_text('{"schema":2}\n')
        self.assertNotEqual(0, self.verify())

    def test_the_signature_is_base64_of_64_raw_bytes_on_one_line(self):
        self.assertEqual(0, self.sign().returncode)
        text = self.signature.read_bytes()
        self.assertEqual(88, len(text))
        self.assertNotIn(b"\n", text)
        self.assertEqual(64, len(base64.b64decode(text, validate=True)))

    def test_a_key_other_than_the_trusted_one_is_refused(self):
        env = {k: v for k, v in os.environ.items() if k != "RELEASE_FEED_PUBLIC_KEY"}
        result = self.sign(env=env)
        self.assertEqual(1, result.returncode)
        self.assertIn("does not match the key the app trusts", result.stderr)
        self.assertFalse(self.signature.exists())

    def test_a_missing_or_broken_key_fails_clearly(self):
        result = self.sign(key=self.dir / "none.pem")
        self.assertEqual(1, result.returncode)
        self.assertIn("cannot read the key file", result.stderr)
        broken = self.dir / "broken.pem"
        broken.write_text("\n")
        result = self.sign(key=broken)
        self.assertEqual(1, result.returncode)
        self.assertIn("not a readable private key", result.stderr)
        self.assertFalse(self.signature.exists())


if __name__ == "__main__":
    unittest.main()
