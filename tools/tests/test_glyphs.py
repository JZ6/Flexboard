"""Compact SVG commands must not silently disappear from glyph comparisons."""

import unittest

import support  # noqa: F401 — puts tools/apk on sys.path
import glyphs


class Geometry(unittest.TestCase):
    def test_compact_arc_flags_keep_the_endpoint_and_geometry(self):
        compact = 'M18.3 5.71a.996.996 0 00-1.41 0l1 1'
        spaced = 'M18.3 5.71 a .996 .996 0 0 0 -1.41 0 l1 1'
        self.assertEqual(glyphs.points(compact), glyphs.points(spaced))
        self.assertEqual(glyphs.points(compact)[-1], (17.89, 6.71))

    def test_incomplete_arc_is_not_silently_ignored(self):
        with self.assertRaisesRegex(ValueError, 'incomplete SVG arc'):
            glyphs.points('M1 2 A 1 1 0 0 1 3')

    def test_smooth_cubic_reflects_previous_control(self):
        smooth = glyphs.points('M2 2 c1 0 2 1 3 3 s2 3 3 3')
        explicit = glyphs.points('M2 2 c1 0 2 1 3 3 c1 2 2 3 3 3')
        self.assertEqual(smooth, explicit)

    def test_svg_path_filter_uses_its_own_attributes(self):
        svg = '<svg><path d="M1 1L2 2"/><path d="M0 0" fill="none"/></svg>'
        self.assertEqual(glyphs.svg_points(svg), glyphs.points('M1 1L2 2'))


if __name__ == '__main__':
    unittest.main()
