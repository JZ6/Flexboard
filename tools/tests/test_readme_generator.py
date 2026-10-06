"""Generated README links and descriptions must survive real release substitution."""

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class ReadmeGenerator(unittest.TestCase):
    def test_backslash_in_description_and_punctuation_in_heading(self):
        root = Path(__file__).resolve().parents[2]
        script = root / ".github/scripts/generate_patches_readme.py"
        with tempfile.TemporaryDirectory() as work:
            inventory = Path(work) / "patches-list.json"
            readme = Path(work) / "README.md"
            inventory.write_text(json.dumps({"version": "1.0.0", "patches": [
                {"name": "A & B", "description": r"match \d exactly", "compatiblePackages": []}
            ]}, ensure_ascii=False), encoding="utf-8")
            readme.write_text("# Sample\n## A & B\n<!-- PATCHES_START -->\nold\n"
                              "<!-- PATCHES_END -->\n", encoding="utf-8")
            done = subprocess.run([sys.executable, "-B", str(script), "JZ6/Flexboard", "dev",
                                   str(inventory), str(readme)], capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            generated = readme.read_text(encoding="utf-8")
            self.assertIn("[A & B](#a--b)", generated)
            self.assertIn(r"match \d exactly", generated)


if __name__ == "__main__":
    unittest.main()
