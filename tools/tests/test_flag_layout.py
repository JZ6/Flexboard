"""How a Phenotype flag takes its default, which is the thing this project keeps misreading.

Three separate builds were refused on a device because the patch believed something about the flag
layout that the dex did not agree with. Each time the belief was different and each time it was
plausible:

  dev.3  "hoisted means off"           -- it does not; a hoisted constant can hold 1
  dev.4  "default=? is close enough"   -- the scan never resolved the value and was used anyway
  dev.5  "owns its constant, so safe"  -- owning the one before you says nothing about who reads
                                          it next

`flag_layout` is the single answer to all three, and these are the cases. The streams are written
here rather than read from the APK, so they can describe layouts 18.0.3 does not happen to contain
-- which is the point, because the next Gboard will contain some of them.
"""

import unittest

from support import stream  # noqa: E402

import preflight as P  # noqa: E402

FACTORY = "Lnxs;->a(Ljava/lang/String;Z)Lnxp;"
LONG_FACTORY = "Lnxs;->c(Ljava/lang/String;J)Lnxp;"


def declare(flag, register, const=None, field="a"):
    """The four instructions Gboard emits per boolean flag, with the constant optional."""
    rows = [("const-string", f"v0, '{flag}'")]
    if const is not None:
        rows.append(("const/4", f"v{register}, #{const}"))
    rows += [
        ("invoke-static", "{v0, v%d}, %s" % (register, FACTORY)),
        ("move-result-object", "v0"),
        ("sput-object", f"v0, Lholder;->{field}:Lnxp;"),
    ]
    return rows


class OwnConstant(unittest.TestCase):
    def test_own_zero_and_unshared_needs_no_isolation(self):
        ins = stream(*declare("alpha", 1, 0), *declare("beta", 2, 1, "b"))
        layout = P.flag_layout(ins, "alpha")
        self.assertTrue(layout["own"])
        self.assertEqual(layout["effective"], 0)
        self.assertFalse(layout["shared"])
        self.assertFalse(layout["isolate"])

    def test_own_constant_but_read_later_needs_isolation(self):
        # dev.5. `alpha` writes v1 itself; `beta` reads v1 afterwards without rewriting it, so
        # rewriting alpha's constant would turn beta on too.
        ins = stream(*declare("alpha", 1, 0), *declare("beta", 1, None, "b"))
        layout = P.flag_layout(ins, "alpha")
        self.assertTrue(layout["own"], "it does write its own constant")
        self.assertTrue(layout["shared"], "and a later flag still reads it")
        self.assertTrue(layout["isolate"])

    def test_a_later_write_ends_the_sharing(self):
        # Same shape, except the register is rewritten before the second flag reads it.
        ins = stream(*declare("alpha", 1, 0), *declare("beta", 1, 1, "b"))
        self.assertFalse(P.flag_layout(ins, "alpha")["isolate"])


class HoistedConstant(unittest.TestCase):
    def test_hoisted_zero_needs_isolation(self):
        ins = stream(*declare("alpha", 1, 0), *declare("beta", 1, None, "b"))
        layout = P.flag_layout(ins, "beta")
        self.assertFalse(layout["own"])
        self.assertEqual(layout["effective"], 0)
        self.assertTrue(layout["isolate"])

    def test_hoisted_is_not_a_synonym_for_off(self):
        # dev.3. The constant `beta` inherits holds 1, so the flag already ships on and forcing it
        # is refused -- reading "hoisted" as "off" is what put it in the list.
        ins = stream(*declare("alpha", 1, 1), *declare("beta", 1, None, "b"))
        layout = P.flag_layout(ins, "beta")
        self.assertFalse(layout["own"])
        self.assertEqual(layout["effective"], 1, "inherited a 1, so it is already on")

    def test_effective_value_is_never_unresolved_when_a_write_exists(self):
        # dev.4. The scan that fed the flag list printed "default=?" here and was believed anyway.
        ins = stream(*declare("alpha", 3, 1), *declare("beta", 3, None, "b"))
        self.assertIsNotNone(P.flag_layout(ins, "beta")["effective"])


class Absent(unittest.TestCase):
    def test_a_flag_that_is_not_here_returns_none(self):
        self.assertIsNone(P.flag_layout(stream(*declare("alpha", 1, 0)), "nope"))

    def test_a_long_flag_is_not_treated_as_boolean(self):
        # The boolean factory is matched by signature, so a long declaration is not picked up by
        # the boolean path and mistaken for one.
        ins = stream(
            ("const-string", "v0, 'ad_activation_type'"),
            ("const-wide/16", "v1, #1"),
            ("invoke-static", "{v0, v1, v2}, %s" % LONG_FACTORY),
            ("move-result-object", "v0"),
            ("sput-object", "v0, Lholder;->a:Lnxp;"),
        )
        self.assertIsNone(P.flag_layout(ins, "ad_activation_type"))


class DeclaredSets(unittest.TestCase):
    """The patch's own `forceFlagsOn(...)` call, parsed rather than restated."""

    SOURCE = '''
        forceFlagsOn(
            "one",
            "two",
            // a comment naming "three" must not be read as a flag
            isolating = setOf(
                "two",
            ),
        )
    '''

    def test_separates_forced_from_isolated(self):
        forced, isolated = P.declared_flag_sets(self.SOURCE)
        self.assertEqual(forced, {"one", "two"})
        self.assertEqual(isolated, {"two"})

    def test_comments_are_not_flags(self):
        forced, _ = P.declared_flag_sets(self.SOURCE)
        self.assertNotIn("three", forced)

    def test_no_call_is_reported_rather_than_guessed(self):
        self.assertEqual(P.declared_flag_sets("no call here"), (None, None))


if __name__ == "__main__":
    unittest.main()
