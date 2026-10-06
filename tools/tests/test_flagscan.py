"""A nearby boolean declaration cannot turn a long/string flag into a boolean."""

import unittest

import support  # noqa: F401 — adds tools/apk to sys.path
import flagscan


class BooleanInventory(unittest.TestCase):
    def test_a_long_flag_before_a_boolean_is_not_a_boolean(self):
        class Dex:
            @staticmethod
            def classes():
                return [("Lholder;", 0, 1)]

            @staticmethod
            def class_methods(cd):
                return [("Lholder;-><clinit>()V", 0, 1)]

            @staticmethod
            def code(co):
                return {"registers": 3}

        instructions = [
            (0, "const-string", "v0, 'a_long_flag'"),
            (2, "invoke-static", "{v0, v1}, Lnxs;->c(Ljava/lang/String;J)Lnxp;"),
            (5, "const-string", "v0, 'a_boolean_flag'"),
            (7, "const/4", "v1, #0"),
            (8, "invoke-static", "{v0, v1}, Lnxs;->a(Ljava/lang/String;Z)Lnxp;"),
            (11, "move-result-object", "v2"),
            (12, "sput-object", "v2, Lholder;->a:Lnxp;"),
        ]
        original = flagscan.ddis.disasm
        try:
            flagscan.ddis.disasm = lambda _d, _c: instructions
            found = flagscan.collect_flags([Dex()])
        finally:
            flagscan.ddis.disasm = original
        self.assertEqual(set(found), {"a_boolean_flag"})
        self.assertEqual(found["a_boolean_flag"]["default"], 0)


if __name__ == "__main__":
    unittest.main()
