"""The register type-merge check — the class of bug ART rejects at class load.

`:driver:run` writes a dex; it never loads one, so a method the verifier would reject applies
cleanly through the whole pipeline and fails only on a phone, as a keyboard that will not open.
That shipped twice before anything looked for it.

The cases here are the ones that mattered, plus the ones that must stay quiet. A checker that
reports legal code gets switched off, and this project has enough checks that pass without meaning
anything — so the quiet cases are as load-bearing as the loud one.
"""

import unittest

from support import goto, if_eqz, stream  # noqa: E402

import verify as V  # noqa: E402


class FakeHierarchy:
    """A hand-written class hierarchy, so the tests do not need an APK.

    `known` is what the dex would contain. Anything outside it is a framework class whose
    supertypes cannot be walked — which is the distinction the real bug turned on.
    """

    def __init__(self, parent=None, interfaces=None, known=None):
        self.parent = parent or {}
        self.interfaces = interfaces or {}
        if known:
            for k in known:
                self.parent.setdefault(k, None)

    assignable = V.Hierarchy.assignable


class Assignability(unittest.TestCase):
    def test_identical_types(self):
        h = FakeHierarchy({"La;": None})
        self.assertTrue(h.assignable("La;", "La;"))

    def test_everything_is_an_object(self):
        h = FakeHierarchy({"La;": None})
        self.assertTrue(h.assignable("La;", "Ljava/lang/Object;"))

    def test_subclass_of_a_known_class(self):
        h = FakeHierarchy({"Lchild;": "Lparent;", "Lparent;": None})
        self.assertTrue(h.assignable("Lchild;", "Lparent;"))

    def test_implemented_interface(self):
        h = FakeHierarchy({"Limpl;": None, "Liface;": None}, {"Limpl;": ["Liface;"]})
        self.assertTrue(h.assignable("Limpl;", "Liface;"))

    def test_unrelated_app_classes_are_not_assignable(self):
        h = FakeHierarchy({"La;": None, "Lb;": None})
        self.assertFalse(h.assignable("La;", "Lb;"))

    def test_a_chain_leaving_the_dex_cannot_reach_a_known_target(self):
        """The bug that made the first version of this file miss the shipped crash.

        `Lpmy;` extends `Ljava/lang/Enum;`, which is not in the APK. Bailing out with "unknowable"
        there is wrong: the target `Lpvi;` *is* in the APK, so walking off the end without meeting
        it proves the answer is no. Returning True merged the two types instead of conflicting
        them, and the check stayed silent on a keyboard that would not open.
        """
        h = FakeHierarchy({"Lenumish;": "Ljava/lang/Enum;", "Ltarget;": None})
        self.assertFalse(h.assignable("Lenumish;", "Ltarget;"))

    def test_a_chain_leaving_the_dex_is_unknowable_for_a_framework_target(self):
        # Neither side is walkable, so refusing to guess is the only honest answer.
        h = FakeHierarchy({"Lenumish;": "Ljava/lang/Enum;"})
        self.assertTrue(h.assignable("Lenumish;", "Landroid/os/Parcelable;"))

    def test_a_framework_value_is_unknowable(self):
        h = FakeHierarchy({"Ltarget;": None})
        self.assertTrue(h.assignable("Landroid/view/View;", "Ltarget;"))


class Join(unittest.TestCase):
    def setUp(self):
        self.h = FakeHierarchy({"La;": None, "Lb;": None, "Lchild;": "La;"})

    def test_same_type(self):
        self.assertEqual(V.join("La;", "La;", self.h), "La;")

    def test_unknown_poisons_to_unknown_not_conflict(self):
        # Never report what we could not determine.
        self.assertEqual(V.join(V.UNKNOWN, "La;", self.h), V.UNKNOWN)

    def test_zero_merges_with_any_reference(self):
        # null is assignable to everything, and const/4 0 is how every null arrives.
        self.assertEqual(V.join(V.ZERO, "La;", self.h), "La;")
        self.assertEqual(V.join("La;", V.ZERO, self.h), "La;")

    def test_subclass_widens_to_the_parent(self):
        self.assertEqual(V.join("Lchild;", "La;", self.h), "La;")

    def test_unrelated_types_conflict(self):
        self.assertEqual(V.join("La;", "Lb;", self.h), V.CONFLICT)


class MergeDetection(unittest.TestCase):
    """`check_method(ins, registers, parameters, hierarchy)`."""

    def setUp(self):
        self.h = FakeHierarchy({"Lpvi;": None, "Lpmy;": None})

    def test_the_shipped_bug(self):
        """Two paths into a block, one supplying the wrong type, and the block dereferences it."""
        # v7 is the pointer parameter. The stock path copies it into v3, ours overwrites v3 with
        # an enum -- the register has to be *typed* on both paths or the merge is UNKNOWN, which
        # is deliberately never reported.
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),        # our path: v3 is a Lpmy;
            goto(5),
            ("move-object", "v3, v7"),                    # stock path: v3 is the Lpvi;
            ("nop", ""),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),    # requires Lpvi;
            ("return-void", ""),
        )
        findings = V.check_method(ins, 8, ["Lpvi;"], self.h)
        self.assertTrue(findings, "a conflicting register reaching a typed use must be reported")
        self.assertEqual(findings[0][1], 3)
        self.assertEqual(findings[0][2], "Lpvi;")

    def test_the_fix_silences_it(self):
        # The same shape with the handover the emission should have made.
        ins = stream(
            if_eqz(0, 4),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            ("move-object", "v3, v7"),                    # hand the pointer over before branching
            goto(5),
            ("move-object", "v3, v7"),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

    def test_a_conflict_nobody_reads_is_not_reported(self):
        # Merging unrelated types into a dead register is legal and common.
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

    def test_unknown_never_conflicts(self):
        """A register nothing gave a type to is UNKNOWN, and UNKNOWN is never reported.

        This is why the fixtures above have to type v3 on both paths: without that the merge is
        UNKNOWN, the check is silent, and a test asserting a finding would be asserting a bug.
        """
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("nop", ""),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

    def test_a_straight_line_method_is_never_reported(self):
        ins = stream(
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

    def test_an_invoke_receiver_is_a_use_site(self):
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(5),
            ("move-object", "v3, v7"),
            ("nop", ""),
            ("invoke-virtual", "{v3}, Lpvi;->w()V"),
            ("return-void", ""),
        )
        self.assertTrue(V.check_method(ins, 8, ["Lpvi;"], self.h))


class InvokeArguments(unittest.TestCase):
    """Arguments, not just the receiver — the bug class this project keeps writing."""

    def setUp(self):
        self.h = FakeHierarchy({"Lpvi;": None, "Lpmy;": None, "Landroid/content/Context;": None})

    def test_a_conflicting_argument_is_reported(self):
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("invoke-static", "{v3}, Lfoo;->bar(Landroid/content/Context;)V"),
            ("return-void", ""),
        )
        findings = V.check_method(ins, 8, ["Landroid/content/Context;"], self.h)
        self.assertTrue(findings, "an argument is as much a use site as a receiver")
        self.assertEqual(findings[0][2], "Landroid/content/Context;")

    def test_a_primitive_parameter_demands_nothing(self):
        # The lattice models references; claiming a type for an int would invent findings.
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("invoke-static", "{v3}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

    def test_a_wide_parameter_does_not_shift_the_ones_after_it(self):
        # A long occupies two registers. Miscounting here would misalign every later argument and
        # report conflicts that are only an off-by-one in the checker.
        self.assertEqual(V._parameter_slots("JLbar;"), [None, None, "Lbar;"])
        self.assertEqual(V._parameter_slots("Lfoo;DLbar;"), ["Lfoo;", None, None, "Lbar;"])

    def test_invoke_static_has_no_receiver_slot(self):
        # Treating arg0 as a receiver on a static call shifts every argument by one.
        ins = stream(
            ("invoke-static", "{v7}, Lfoo;->bar(Lpvi;)V"),
            ("return-void", ""),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])


class CatchHandlers(unittest.TestCase):
    """ART merges register state at handler entries too."""

    def setUp(self):
        self.h = FakeHierarchy({"Lpvi;": None, "Lpmy;": None})

    def test_handlers_are_successors_of_everything(self):
        ins = stream(
            ("nop", ""),
            ("move-exception", "v0"),
            ("return-void", ""),
        )
        self.assertEqual(V.handler_entries(ins), [1])
        self.assertIn(1, V.successors(ins, 0, {0: 0, 1: 1, 2: 2}, [1]))

    def test_a_handler_does_not_loop_to_itself(self):
        ins = stream(("move-exception", "v0"), ("return-void", ""))
        self.assertNotIn(0, V.successors(ins, 0, {0: 0, 1: 1}, [0]))

    def test_a_conflict_reaching_a_handler_is_visible(self):
        ins = stream(
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),   # one path types v3 as an enum
            ("move-object", "v3, v7"),               # the other as the pointer
            ("return-void", ""),
            ("move-exception", "v0"),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),
            ("throw", "v0"),
        )
        self.assertTrue(V.check_method(ins, 8, ["Lpvi;"], self.h),
                        "the handler is reachable from both, so v3 conflicts there")


class ExtensionReferences(unittest.TestCase):
    """Calls into the Flexboard extension, which is the part we can actually adjudicate.

    The general question -- does every member a patched method calls exist -- is not answerable
    without android.jar. Gboard classes implement framework interfaces and inherit framework
    methods, so a hierarchy walk leaves the APK almost immediately and has to say "unknowable".
    Trying it anyway produced three false positives out of four findings. The extension has no such
    problem: every class is in the APK and this project writes all of them.
    """

    METHODS = {"Ldev/jz6/flexboard/extension/diagnostic/GestureProbe;->fired()V"}
    FIELDS = {"Ldev/jz6/flexboard/extension/ime/ImeService;->service:I"}

    def flag(self, rows):
        return {i for i, _ in V.unresolved_extension_references(rows, self.METHODS, self.FIELDS)}

    def test_a_call_that_resolves(self):
        rows = [(0, "invoke-static",
                 "{}, Ldev/jz6/flexboard/extension/diagnostic/GestureProbe;->fired()V")]
        self.assertEqual(self.flag(rows), set())

    def test_a_renamed_extension_member(self):
        # The shape that silently defeated a collision guard: the payload was renamed and the
        # emission kept calling the old name.
        rows = [(0, "invoke-static",
                 "{}, Ldev/jz6/flexboard/extension/diagnostic/GestureProbe;->renamed()V")]
        self.assertEqual(self.flag(rows), {0})

    def test_a_gboard_call_is_not_adjudicated(self):
        self.assertEqual(self.flag([(0, "invoke-virtual", "{v0}, Lpvi;->anything()V")]), set())

    def test_a_missing_extension_field(self):
        rows = [(0, "sget", "v0, Ldev/jz6/flexboard/extension/ime/ImeService;->gone:I")]
        self.assertEqual(self.flag(rows), {0})


class Parameters(unittest.TestCase):
    def test_instance_method_gets_its_receiver(self):
        self.assertEqual(V.parameters_of("Lfoo;->m(Lbar;)V", False), ["Lfoo;", "Lbar;"])

    def test_static_method_does_not(self):
        self.assertEqual(V.parameters_of("Lfoo;->m(Lbar;)V", True), ["Lbar;"])

    def test_wide_parameters_take_two_slots(self):
        # A long occupies two registers; miscounting shifts every later parameter.
        self.assertEqual(len(V.parameters_of("Lfoo;->m(JLbar;)V", True)), 3)


if __name__ == "__main__":
    unittest.main()
