"""The smali emission linter.

Two of its five rules matched zero items for their whole existence, because every label in this
project is interpolated (`:$SKIP_LABEL`) and the patterns used `\\w+`, which does not include `$`.
The lane passed on every run while checking nothing. These tests exercise the rules against
payloads written here, so a pattern that stops matching fails immediately rather than going quiet.
"""

import unittest

import support  # noqa: F401  (import for its sys.path side effect)

import check_emission_lint as L  # noqa: E402


class LabelPatterns(unittest.TestCase):
    def test_definition_matches_an_interpolated_label(self):
        self.assertTrue(L.LABEL_DEF.match("    :$SKIP_LABEL"))
        self.assertTrue(L.LABEL_DEF.match("    :${SKIP_LABEL}"))
        self.assertTrue(L.LABEL_DEF.match("    :$lab$slot"))

    def test_definition_matches_a_plain_label(self):
        self.assertTrue(L.LABEL_DEF.match("    :done"))

    def test_definition_rejects_an_instruction(self):
        self.assertIsNone(L.LABEL_DEF.match("    const/4 v0, 0x1"))

    def test_reference_matches_an_interpolated_label(self):
        self.assertEqual(L.LABEL_REF.findall("if-eqz v$value, :$UNDO_DONE_LABEL"),
                         ["$UNDO_DONE_LABEL"])
        self.assertEqual(L.LABEL_REF.findall("if-eqz $g, :$lab$slot"), ["$lab$slot"])


class Canon(unittest.TestCase):
    def test_expands_a_known_constant(self):
        self.assertEqual(L.canon("$SKIP", {"SKIP": "flexboard_skip"}), "flexboard_skip")

    def test_strips_markers_from_a_local_val(self):
        # `val done = "..."` is invisible to the const table, so the reference `:$done` and the
        # declaration `ExternalLabel(done, ...)` have to meet on a canonical form.
        self.assertEqual(L.canon("$done", {}), "done")
        self.assertEqual(L.canon("done", {}), "done")

    def test_a_multi_part_label_reduces_consistently(self):
        self.assertEqual(L.canon("$lab$slot", {}), L.canon("${lab}${slot}", {}))


class Rules(unittest.TestCase):
    """`lint_block(problems, name, line, payload, externals, consts, labeled)`."""

    def lint(self, payload, externals=(), consts=None, labeled=True):
        problems = []
        L.lint_block(problems, "T.kt", 1, payload, set(externals), consts or {}, labeled)
        return problems

    def codes(self, problems):
        return sorted(p.split("(")[-1].rstrip(") ") for p in problems)

    def test_accepts_a_well_formed_block(self):
        payload = "\n".join([
            "    if-eqz v0, :$SKIP",
            "    const/4 v1, 0x1",
            "    :$SKIP",
            "    nop",
        ])
        self.assertEqual(self.lint(payload, consts={"SKIP": "flexboard_skip"}), [])

    def test_r2_undefined_label(self):
        payload = "    if-eqz v0, :$MISSING\n    nop"
        self.assertIn("R2", self.codes(self.lint(payload)))

    def test_r2_satisfied_by_an_external_label(self):
        payload = "    if-eqz v0, :$SKIP\n    nop"
        problems = self.lint(payload, externals={"flexboard_skip"},
                             consts={"SKIP": "flexboard_skip"})
        self.assertEqual(problems, [])

    def test_r2_satisfied_by_a_local_val_external(self):
        # The spelling that made the repaired rule report four correctly declared branches.
        payload = "    if-gez v0, :$done\n    nop"
        self.assertEqual(self.lint(payload, externals={"done"}), [])

    def test_r3_duplicate_definition(self):
        payload = "    :$SKIP\n    nop\n    :$SKIP\n    nop"
        self.assertIn("R3", self.codes(self.lint(payload, consts={"SKIP": "s"})))

    def test_r1_block_ending_on_a_label(self):
        # Morphe resolves a terminal label as external and dies at `length=0; index=0`.
        payload = "    nop\n    :$SKIP"
        self.assertIn("R1", self.codes(self.lint(payload, externals={"s"},
                                                 consts={"SKIP": "s"})))

    def test_r4_const_4_out_of_range(self):
        self.assertIn("R4", self.codes(self.lint("    const/4 v0, 0x40")))

    def test_r4_const_4_in_range(self):
        self.assertEqual(self.lint("    const/4 v0, 0x7"), [])
        self.assertEqual(self.lint("    const/4 v0, -0x8"), [])

    def test_r4_const_16_out_of_range(self):
        self.assertIn("R4", self.codes(self.lint("    const/16 v0, 0x8000")))

    def test_r4_const_16_accepts_a_real_keycode(self):
        # -10045, the revert-autocorrect code, fits a signed 16-bit operand.
        self.assertEqual(self.lint("    const/16 v5, -10045"), [])

    def test_r4_sees_an_interpolated_register(self):
        # Composed payloads interpolate the whole register token rather than spelling `v$name`,
        # and requiring a literal `v` made the rule miss them.
        self.assertIn("R4", self.codes(self.lint("    const/4 $a, 0x7f")))

    def test_r2_in_an_unlabeled_payload(self):
        payload = "    if-eqz v0, :$SKIP\n    nop"
        self.assertIn("R2", self.codes(self.lint(payload, labeled=False)))

    def test_a_field_descriptor_colon_is_not_a_label(self):
        # Rejecting these is what pushed authors toward composing payloads out of reach entirely.
        payload = "    iget-object v0, p0, Lqhy;->a:Landroid/content/Context;"
        self.assertEqual(self.lint(payload, labeled=False), [])


class CallSurface(unittest.TestCase):
    def test_matches_every_emission_entry_point(self):
        for call in ("addInstructions(", "addInstructionsWithLabels(",
                     "addInstruction(", "replaceInstruction("):
            self.assertTrue(L.CALL.search(call), f"{call} is an emission entry point")

    def test_does_not_match_an_unrelated_call(self):
        self.assertIsNone(L.CALL.search("addInstrumentation("))


if __name__ == "__main__":
    unittest.main()
