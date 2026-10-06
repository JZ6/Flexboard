"""The source checker must not skip a descriptor merely because of Kotlin spelling."""

import unittest
import tempfile
from pathlib import Path

import support  # noqa: F401 — adds .github/scripts to sys.path
import check_shared_constants as constants


class ConstantParsing(unittest.TestCase):
    def test_longer_name_is_not_replaced_as_a_shorter_prefix(self):
        text = '''const val EXT = "Ldev/jz6/flexboard/extension/X;"
const val EXT_INSTALL = "$EXT->install()V"
invoke-static { p0 }, $EXT_INSTALL
'''
        expanded = constants._expand(text)
        self.assertIn("Ldev/jz6/flexboard/extension/X;->install()V", expanded)
        self.assertNotIn("X;_INSTALL", expanded)

    def test_braced_interpolation_is_expanded(self):
        text = 'const val EXT = "Ldev/jz6/flexboard/extension/X;"\n' \
               'const val INSTALL = "${EXT}->install()V"\n'
        self.assertIn("X;->install()V", constants._expand(text))

    def test_named_helper_argument_is_checked(self):
        found = constants.HELPER_CALL.findall("callAtAppStart(descriptor = INSTALL)")
        self.assertEqual(found, [("callAtAppStart", "INSTALL")])

    def test_bare_interpolated_register_still_stages_a_key(self):
        self.assertEqual(constants.EMITTED_KEY_READ.findall(
            'const-string $reg, "$MAX_WORDS_KEY"'), ["MAX_WORDS_KEY"])

    def test_same_count_with_wrong_action_id_fails(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work) / 'patches/src/main/kotlin'
            slot = root.parent / 'resources/values/flexboard_toolbar_slots.xml'
            toolbar = root / 'dev/jz6/flexboard/patches/features/toolbar'
            toolbar.mkdir(parents=True)
            slot.parent.mkdir(parents=True)
            (toolbar / 'ToolbarHotkeys.kt').write_text(
                'const val HOTKEY_SLOTS = 1\nconst val HOTKEY_ID_PREFIX = "flexboard_hotkey_"',
                encoding='utf-8')
            (toolbar / 'ToolbarButtonsPatch.kt').write_text(
                'internal const val SELECT_ALL_ID = "flexboard_select_all"\n'
                'internal const val COPY_ID = "flexboard_copy"\n'
                'internal const val PASTE_ID = "flexboard_paste"\n', encoding='utf-8')
            slot.write_text('<resources><string name="flexboard_hotkey_1">flexboard_hotkey_1</string>'
                            '<string name="flexboard_select_all">flexboard_select_all</string>'
                            '<string name="flexboard_copy">flexboard_copy</string>'
                            '<string name="flexboard_unregistered">flexboard_unregistered</string>'
                            '</resources>', encoding='utf-8')
            previous = constants.PATCHES
            try:
                constants.PATCHES = root
                problems = []
                constants._check_admitted_ids(problems)
                self.assertTrue(any('action ids' in p for p in problems), problems)
            finally:
                constants.PATCHES = previous


if __name__ == "__main__":
    unittest.main()
