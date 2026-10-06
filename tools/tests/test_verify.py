"""The register type-merge check — the class of bug ART rejects at class load.

`:driver:run` writes a dex; it never loads one, so a method the verifier would reject applies
cleanly through the whole pipeline and fails only on a phone, as a keyboard that will not open.
That shipped twice before anything looked for it.

The cases here are the ones that mattered, plus the ones that must stay quiet. A checker that
reports legal code gets switched off, and this project has enough checks that pass without meaning
anything — so the quiet cases are as load-bearing as the loud one.
"""

import unittest
import struct

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
        self.interface_types = set()
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

    def test_a_framework_value_cannot_extend_an_app_class(self):
        h = FakeHierarchy({"Ltarget;": None})
        self.assertFalse(h.assignable("Landroid/view/View;", "Ltarget;"))


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

    def test_join_is_commutative_with_framework_and_app_types(self):
        pairs = [("La;", "Lb;"), ("Lchild;", "La;"),
                 ("La;", "Landroid/view/MotionEvent;"),
                 ("Landroid/view/View;", "Landroid/view/MotionEvent;")]
        for a, b in pairs:
            with self.subTest(a=a, b=b):
                self.assertEqual(V.join(a, b, self.h), V.join(b, a, self.h))

    def test_unrelated_framework_types_join_to_unknown_not_an_arbitrary_operand(self):
        self.assertEqual(V.join("Landroid/view/View;", "Landroid/view/MotionEvent;", self.h),
                         V.UNKNOWN)


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

    def test_primitive_field_still_demands_a_typed_owner(self):
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("iget", "v1, v3, Lpvi;->count:I"),
            ("return-void", ""),
        )
        self.assertTrue(V.check_method(ins, 8, ["Lpvi;"], self.h),
                        "primitive fields require their owner to have the right type too")

    def test_unmodelled_destination_write_kills_old_conflict(self):
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("aget-object", "v3, v0, v1"),  # a new reference replaces both incoming types
            ("iget-object", "v2, v3, Lpvi;->value:Ljava/lang/String;"),
            ("return-object", "v2"),
        )
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h), [])

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

    def test_switch_case_is_a_real_edge_not_just_payload_fallthrough(self):
        ins = stream(
            ("packed-switch", "v0, -> 6"),
            ("move-object", "v3, v7"),
            goto(5),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(5),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),
            ("payload", "6 units"),
        )
        # Packed payload: tag, count, first case key, target offset relative to pc 0.
        class Dex:
            b = bytearray(40)
        struct.pack_into("<HHii", Dex.b, 12, 0x0100, 1, 10, 3)
        targets = V.switch_case_targets(Dex, {"insns_off": 0}, ins)
        self.assertEqual(targets, {0: [3]})
        self.assertTrue(V.check_method(ins, 8, ["Lpvi;"], self.h, targets))
        with self.assertRaisesRegex(ValueError, "switch cases were not decoded"):
            V.check_method(ins, 8, ["Lpvi;"], self.h)


class InvokeArguments(unittest.TestCase):
    """Arguments, not just the receiver — the bug class this project keeps writing."""

    def setUp(self):
        self.h = FakeHierarchy({"Lpvi;": None, "Lpmy;": None})

    def test_a_conflicting_argument_is_reported(self):
        ins = stream(
            if_eqz(0, 3),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            goto(4),
            ("move-object", "v3, v7"),
            ("invoke-static", "{v3}, Lfoo;->bar(Lpvi;)V"),
            ("return-void", ""),
        )
        findings = V.check_method(ins, 8, ["Lpvi;"], self.h)
        self.assertTrue(findings, "an argument is as much a use site as a receiver")
        self.assertEqual(findings[0][2], "Lpvi;")

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
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            ("invoke-static", "{v3}, Lfoo;->bar(Lpvi;)V"),
            ("return-void", ""),
        )
        findings = V.check_method(ins, 8, ["Lpvi;"], self.h)
        self.assertEqual([(i, required) for i, _reg, required, _mn in findings],
                         [(1, "Lpvi;")])


class CatchHandlers(unittest.TestCase):
    """ART merges register state at handler entries too."""

    def setUp(self):
        self.h = FakeHierarchy({"Lpvi;": None, "Lpmy;": None})

    def test_handler_entries_are_a_fallback_for_synthetic_streams(self):
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
            if_eqz(0, 4),
            ("sget-object", "v3, Lpmy;->c:Lpmy;"),
            ("invoke-static", "{}, Lfoo;->couldThrow()V"),
            goto(6),
            ("move-object", "v3, v7"),
            ("invoke-static", "{}, Lfoo;->couldThrow()V"),
            ("return-void", ""),
            ("move-exception", "v0"),
            ("iget-object", "v1, v3, Lpvi;->B:Lwzc;"),
            ("throw", "v0"),
        )
        self.assertTrue(V.check_method(ins, 8, ["Lpvi;"], self.h,
                                       exception_targets={2: [7], 5: [7]}),
                         "the handler is reachable from both, so v3 conflicts there")
        self.assertEqual(V.check_method(ins, 8, ["Lpvi;"], self.h, exception_targets={}), [])

    def test_the_dex_try_table_limits_which_instructions_reach_a_handler(self):
        class Dex:
            b = bytearray(48)
        # insns_off=16; four code units => try_item at 24, handlers list at 32.
        # try covers pc 0..1, handler offset 1 points to encoded handler at 33.
        struct.pack_into('<IHH', Dex.b, 24, 0, 2, 1)
        Dex.b[32:36] = bytes([1, 1, 0, 3])  # list size, signed size, type, handler pc
        ins = stream(("nop", ""), ("invoke-static", "{}, Lfoo;->f()V"),
                     ("return-void", ""), ("move-exception", "v0"))
        self.assertEqual(V.catch_targets(Dex, {"tries_size": 1, "insns_off": 16,
                                              "insns_size": 4}, ins), {1: [3]})
        self.assertFalse(V.can_throw('move-object'))
        self.assertTrue(V.can_throw('binop93'))  # div-int can raise ArithmeticException


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


def fake_access(classes):
    """An `Access` over hand-written classes, so the tests do not need an APK.

    `classes` maps a descriptor to (flags, parent, interfaces, fields, methods), the last two as
    `{"name:Type": flags}` and `{"name(Args)Ret": flags}`. Anything not listed is a framework class.
    """
    a = V.Access.__new__(V.Access)
    a.flags, a.parent, a.interfaces, a._data = {}, {}, {}, {}
    a._members = {"field": {}, "method": {}}
    for name, (flags, parent, interfaces, fields, methods) in classes.items():
        a.flags[name] = flags
        a.parent[name] = parent
        a.interfaces[name] = list(interfaces)
        a._members["field"][name] = dict(fields)
        a._members["method"][name] = dict(methods)
    return a


SCRUB = "Lcom/google/scrubmove/ScrubMotionEventHandler;"
PUBLIC, PRIVATE, PROTECTED, FINAL, SYNTHETIC = 0x1, 0x2, 0x4, 0x10, 0x1000


class Access(unittest.TestCase):
    """References a patched method's class is not allowed to make.

    ART does not refuse a class for these. An access failure is a soft verification failure: the
    class loads, the keyboard opens, and the instruction throws IllegalAccessError when it runs.
    That is how 2.5.1-dev.7 and dev.9 crashed on every swipe up with nothing in any lane to say so.
    """

    def route(self, class_flags=FINAL, field_flags=FINAL | SYNTHETIC):
        # Lozi; as Gboard ships it: package-private, unnamed package, field `b` package-private.
        return fake_access({
            SCRUB: (PUBLIC, "Ljava/lang/Object;", [], {}, {}),
            "Lozi;": (class_flags, "Ljava/lang/Object;", [], {"b:Lozj;": field_flags}, {}),
            "Lozj;": (PUBLIC | FINAL, "Ljava/lang/Object;", [], {"k:Lpvn;": PUBLIC}, {}),
        })

    EMISSION = [
        (20, "instance-of", "v2, v1, Lozi;"),
        (25, "check-cast", "v2, Lozi;"),
        (27, "iget-object", "v2, v2, Lozi;->b:Lozj;"),
        (29, "iget-object", "v2, v2, Lozj;->k:Lpvn;"),
    ]

    def flagged(self, access, rows=None, host=SCRUB):
        findings, _ = V.inaccessible_references(host, rows or self.EMISSION, access)
        return [rows[i][0] if rows else self.EMISSION[i][0] for i, _r, _w in findings]

    def test_the_shipped_crash(self):
        self.assertEqual(self.flagged(self.route()), [20, 25, 27],
                         "a package-private class in another package, at every use")

    def test_widening_the_class_alone_still_leaves_the_field(self):
        # Half a fix. The class becomes reachable; its package-private field does not.
        findings, _ = V.inaccessible_references(SCRUB, self.EMISSION, self.route(class_flags=PUBLIC | FINAL))
        self.assertEqual([self.EMISSION[i][0] for i, _r, _w in findings], [27])
        self.assertIn("package-private in Lozi;", findings[0][2])

    def test_widening_both_is_the_fix(self):
        access = self.route(class_flags=PUBLIC | FINAL, field_flags=PUBLIC | FINAL | SYNTHETIC)
        self.assertEqual(self.flagged(access), [])

    def test_the_same_package_may_use_package_private(self):
        # Stock Gboard code in the unnamed package reads Lozi;->b all the time. Quiet.
        self.assertEqual(self.flagged(self.route(), host="Lozz;"), [])

    def test_a_private_member_of_another_class(self):
        access = fake_access({
            "Lapp/A;": (PUBLIC, None, [], {}, {}),
            "Lapp/B;": (PUBLIC, None, [], {"x:I": PRIVATE}, {}),
        })
        rows = [(0, "iget", "v0, v1, Lapp/B;->x:I")]
        self.assertEqual(self.flagged(access, rows, host="Lapp/A;"), [0],
                         "private means the class itself, not its package")
        self.assertEqual(self.flagged(access, rows, host="Lapp/B;"), [])

    def test_protected_reaches_a_subclass_in_another_package(self):
        access = fake_access({
            "Lbase/Handler;": (PUBLIC, None, [], {"p:Lpvo;": PROTECTED}, {}),
            "Lother/Scrub;": (PUBLIC, "Lbase/Handler;", [], {}, {}),
            "Lother/Stranger;": (PUBLIC, None, [], {}, {}),
        })
        rows = [(0, "iget-object", "v1, v0, Lother/Scrub;->p:Lpvo;")]
        self.assertEqual(self.flagged(access, rows, host="Lother/Scrub;"), [],
                         "inherited and protected, which is how the stock scrub reads its route")
        rows = [(0, "iget-object", "v1, v0, Lbase/Handler;->p:Lpvo;")]
        self.assertEqual(self.flagged(access, rows, host="Lother/Stranger;"), [0])

    def test_an_inherited_member_is_judged_where_it_is_declared(self):
        # Referenced through a public subclass, declared package-private in its parent. A
        # resolution that stopped at the named class would call this unknowable and stay quiet.
        access = fake_access({
            "Lbase/Parent;": (PUBLIC, None, [], {"secret:I": 0}, {}),
            "Lbase/Child;": (PUBLIC, "Lbase/Parent;", [], {}, {}),
            SCRUB: (PUBLIC, None, [], {}, {}),
        })
        findings, unchecked = V.inaccessible_references(
            SCRUB, [(0, "iget", "v0, v1, Lbase/Child;->secret:I")], access)
        self.assertEqual(([i for i, _r, _w in findings], unchecked), ([0], 0))
        self.assertIn("Lbase/Parent;", findings[0][2])

    def test_a_public_member_of_a_hidden_class_is_still_hidden(self):
        # The class named in the reference is checked first, as ART does.
        access = fake_access({
            "Lhidden;": (FINAL, None, [], {}, {"m()V": PUBLIC}),
            SCRUB: (PUBLIC, None, [], {}, {}),
        })
        self.assertEqual(self.flagged(access, [(0, "invoke-virtual", "{v0}, Lhidden;->m()V")]), [0])

    def test_an_interface_method_resolves_through_a_superinterface(self):
        access = fake_access({
            "Lapi/Route;": (PUBLIC | 0x200, None, ["Lapi/Base;"], {}, {}),
            "Lapi/Base;": (PUBLIC | 0x200, None, [], {}, {"m()V": PUBLIC | 0x400}),
            SCRUB: (PUBLIC, None, [], {}, {}),
        })
        findings, unchecked = V.inaccessible_references(
            SCRUB, [(0, "invoke-interface", "{v1}, Lapi/Route;->m()V")], access)
        self.assertEqual((findings, unchecked), ([], 0), "resolved and judged, not skipped")

    def test_the_framework_is_counted_never_passed_or_failed(self):
        access = fake_access({SCRUB: (PUBLIC, "Landroid/view/View;", [], {}, {})})
        rows = [(0, "invoke-virtual", "{v12}, Landroid/view/MotionEvent;->getActionMasked()I"),
                (1, "invoke-virtual", "{v0}, Lcom/google/scrubmove/ScrubMotionEventHandler;->invalidate()V")]
        findings, unchecked = V.inaccessible_references(SCRUB, rows, access)
        self.assertEqual(findings, [])
        self.assertEqual(unchecked, 2, "a framework class, and a member inherited from one")

    def test_a_primitive_array_needs_no_access(self):
        access = fake_access({SCRUB: (PUBLIC, None, [], {}, {})})
        findings, unchecked = V.inaccessible_references(SCRUB, [(0, "new-array", "v0, v1, [I")], access)
        self.assertEqual((findings, unchecked), ([], 0))

    def test_packages(self):
        self.assertEqual(V.package_of("Lozi;"), "")
        self.assertEqual(V.package_of("Lcom/a/B$C;"), "com/a")


class MethodDiffs(unittest.TestCase):
    def test_a_same_size_in_place_reference_change_is_still_a_change(self):
        class Dex:
            def __init__(self, signature, reference):
                self.b = b"\0" * 12 + signature * 20 + b"\0" * 16
                self.reference = reference

        stock = Dex(b"a", "Lold;->a()V")
        patched = Dex(b"b", "Lnew;->a()V")
        code = {"registers": 1, "insns_size": 1, "insns_off": 32}
        original = V.ddis.disasm
        try:
            V.ddis.disasm = lambda dex, _code: [(0, "invoke-static", dex.reference)]
            self.assertFalse(V.same_body((1, 1, stock, code), (1, 1, patched, code)))
            self.assertTrue(V.same_body((1, 1, stock, code), (1, 1, stock, code)))
        finally:
            V.ddis.disasm = original


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
