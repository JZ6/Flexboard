"""Generated outputs describe the last release, not unreleased source edits."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

import support  # noqa: F401 — adds .github/scripts to the import path
import check_generated_artifacts as artifacts


class PublishedArtifacts(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        root = Path(work.name)
        self.addCleanup(setattr, artifacts, "ROOT", artifacts.ROOT)
        self.addCleanup(setattr, artifacts, "PATCHES", artifacts.PATCHES)
        artifacts.ROOT = root
        artifacts.PATCHES = root / "patches/src/main/kotlin"
        artifacts.PATCHES.mkdir(parents=True)
        (root / "patches-list.json").write_text(
            '{"patches":[{"name":"Old","default":true}]}', encoding="utf-8")
        (root / "README.md").write_text(
            '<!-- PATCHES_START -->\n> 1 patches total\n'
            '| &nbsp;Patch | Description | Options |\n|---|---|---|\n| Old | yes | |\n'
            '<!-- PATCHES_END -->\n', encoding="utf-8")
        (artifacts.PATCHES / "Patch.kt").write_text(
            'val old = bytecodePatch(\n    name = "Old",\n)\n', encoding="utf-8")

    def test_new_patch_does_not_require_editing_the_generated_readme(self):
        (artifacts.PATCHES / "New.kt").write_text(
            'val new = bytecodePatch(\n    name = "New",\n)\n', encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(artifacts.main(), 0)
        self.assertIn("not yet in patches-list.json", output.getvalue())

    def test_missing_published_inventory_is_not_silently_accepted(self):
        (artifacts.ROOT / "patches-list.json").write_text('{"patches":[]}', encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(artifacts.main(), 1)
        self.assertIn("no named patches", output.getvalue())

    def test_raw_resource_patch_declarations_are_counted(self):
        (artifacts.PATCHES / "New.kt").write_text(
            'val new = rawResourcePatch(\n    name = "New",\n)\n', encoding="utf-8")
        self.assertIn("New", artifacts.declared_patches())


if __name__ == "__main__":
    unittest.main()
