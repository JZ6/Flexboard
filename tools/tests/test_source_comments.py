"""Checkers must see Kotlin/Java literals intact while ignoring actual comments."""

import unittest

import support  # noqa: F401 — adds .github/scripts to sys.path
from source_comments import without_comments


class SourceComments(unittest.TestCase):
    def test_url_in_a_string_is_not_a_line_comment(self):
        source = 'const val SOURCE = "https://github.com/JZ6/Flexboard" // note\n'
        masked = without_comments(source)
        self.assertIn('https://github.com/JZ6/Flexboard', masked)
        self.assertNotIn('note', masked)
        self.assertEqual(len(masked), len(source))

    def test_raw_smali_string_keeps_slashes_and_comment_markers(self):
        source = 'val smali = """http://a/*b*/""" // actual comment\n'
        masked = without_comments(source)
        self.assertIn('http://a/*b*/', masked)
        self.assertNotIn('actual comment', masked)

    def test_nested_block_comment_keeps_following_source_and_line_numbers(self):
        source = 'a /* outer\n/* inner */ still comment */ b'
        masked = without_comments(source)
        self.assertEqual(masked.count('\n'), source.count('\n'))
        self.assertEqual(masked.strip().replace(' ', ''), 'a\nb')


if __name__ == '__main__':
    unittest.main()
