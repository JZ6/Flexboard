"""A delegate cannot escape the structural checker by acquiring a block body."""

import tempfile
import unittest
from pathlib import Path

import support  # noqa: F401 — adds .github/scripts to sys.path
import check_delegates as delegates


class DelegateChecks(unittest.TestCase):
    def test_block_body_swapped_arguments_are_rejected(self):
        source = '''internal fun findField(lookup: ClassLookup, type: String, name: String): FieldLookup =
    lookup(type)

internal fun BytecodePatchContext.findField(type: String, name: String): FieldLookup {
    return findField(classLookup, name, type)
}
'''
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "Types.kt"
            path.write_text(source, encoding="utf-8")
            count, problems = delegates.check(path)
        self.assertEqual(count, 0)
        self.assertTrue(any("block body" in message for message in problems), problems)


if __name__ == "__main__":
    unittest.main()
