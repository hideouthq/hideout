import unittest

import make_feed
import release

MODULE = "dependencies:\n  - foo:\n    version: 9.9.9\nplugins:\n  native-package:\n    enabled: true\n    version: '1.4.0' # shown\n    name: Hideout\n"


class ModuleVersion(unittest.TestCase):
    def test_only_the_native_package_version_changes(self):
        changed = release.with_module_version(MODULE, "1.5.0")
        self.assertEqual(MODULE.replace("'1.4.0'", "'1.5.0'"), changed)
        self.assertEqual("1.5.0", make_feed.module_version(changed))

    def test_a_module_without_one_has_none_to_set(self):
        self.assertIsNone(release.with_module_version("plugins:\n  native-package:\n    enabled: true\nother:\n  version: 1.0.0\n", "1.5.0"))

    def test_the_real_module_declares_one(self):
        text = (release.ROOT / release.MODULE).read_text()
        self.assertEqual("9.9.9", make_feed.module_version(release.with_module_version(text, "9.9.9")))


if __name__ == "__main__":
    unittest.main()
