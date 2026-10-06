"""Shifting absolute branch PCs is not a removal; changing their targets is."""

import unittest

import support  # noqa: F401 — loads tools/apk
import patched


class PatchedDiff(unittest.TestCase):
    def setUp(self):
        self.stock = [(0, 'goto', '-> 3'), (1, 'const/4', 'v0, #1'),
                      (2, 'nop', ''), (3, 'return', 'v0')]

    def test_insertion_shifts_pc_but_keeps_branch_target(self):
        emitted = [(0, 'goto', '-> 4'), (1, 'const/4', 'v1, #1'),
                   (2, 'const/4', 'v0, #1'), (3, 'nop', ''), (4, 'return', 'v0')]
        a, b = patched.diff_text(self.stock, emitted)
        self.assertEqual(a[0], b[0])
        self.assertEqual(len(b), len(a) + 1)

    def test_branch_to_a_different_block_is_reported(self):
        emitted = [(0, 'goto', '-> 3'), (1, 'const/4', 'v1, #1'),
                   (2, 'const/4', 'v0, #1'), (3, 'nop', ''), (4, 'return', 'v0')]
        a, b = patched.diff_text(self.stock, emitted)
        self.assertNotEqual(a[0], b[0])
        self.assertIn('target patched instruction 3', b[0])


if __name__ == '__main__':
    unittest.main()
