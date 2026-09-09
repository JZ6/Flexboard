"""The register analysis every emitter picks its scratch registers from.

Four separate defects have shipped in this code, and all four pointed the same way: reporting a
register free while something still read it. That is the direction that corrupts a value silently
on a device, because a patch that borrows a live register still assembles, still applies, and still
installs. Each defect below has a test named after it.

The bar for a test here is that it fails against the old behaviour. A test that passes both before
and after a fix documents an opinion, not a property.
"""

import unittest

from support import goto, if_eqz, stream  # noqa: E402

import preflight as P  # noqa: E402


class Regs(unittest.TestCase):
    def test_ignores_registers_inside_descriptors(self):
        # `v7` appears inside the type name, not as an operand. 8,530 operands in the shipped APK
        # have this shape, and a naive `v\d+` scan turns every one into a phantom read.
        self.assertEqual(P.regs("v3, Landroid/support/v7/widget/AppCompatTextView;"), [3])
        self.assertEqual(P.regs("v0, 'TLSv1.3'"), [0])

    def test_reads_a_plain_operand_list(self):
        self.assertEqual(P.regs("v1, v2, v3"), [1, 2, 3])

    def test_range_invoke_looks_like_two_endpoints(self):
        # Documenting the trap rather than the fix: this is what `regs` legitimately returns, and
        # why every caller that needs an argument list has to use `invoke_regs`.
        self.assertEqual(P.regs("{v3 .. v12}, Lfoo;->bar()V"), [3, 12])


class InvokeRegs(unittest.TestCase):
    def test_expands_a_range(self):
        self.assertEqual(P.invoke_regs("{v3 .. v12}, Lfoo;->bar()V"), list(range(3, 13)))

    def test_passes_a_packed_invoke_through(self):
        self.assertEqual(P.invoke_regs("{v1, v2}, Lfoo;->bar(I)V"), [1, 2])

    def test_single_register_range(self):
        self.assertEqual(P.invoke_regs("{v5 .. v5}, Lfoo;->bar()V"), [5])


class WidePairs(unittest.TestCase):
    """64-bit values occupy r and r+1. Modelling only r loses half of every long and double."""

    def test_move_result_wide_writes_the_pair(self):
        self.assertEqual(P.wide_pairs("move-result-wide", [5]), ([], [6]))

    def test_const_wide_writes_the_pair(self):
        self.assertEqual(P.wide_pairs("const-wide/16", [1]), ([], [2]))

    def test_cmp_long_reads_two_pairs_and_writes_a_narrow(self):
        # vAA is a narrow int result; vBB and vCC are pairs. Reporting v12 as a source here would
        # be conservative but wrong, and it is the shape that first exposed the modelling gap.
        sources, destinations = P.wide_pairs("cmp-long", [11, 4, 11])
        self.assertEqual(sorted(sources), [5, 12])
        self.assertEqual(destinations, [])

    def test_float_to_double_writes_the_pair(self):
        # 0x89. Omitted from the first version of the table, which made the scrub clamp's own
        # insertion point -- a float-to-double writing the very pair the emission borrows --
        # report its high half live on entry. Caught by a real pin, hence a test.
        self.assertEqual(P.wide_pairs("unop89", [6, 0]), ([], [7]))

    def test_int_to_long_writes_a_pair_and_reads_a_narrow(self):
        self.assertEqual(P.wide_pairs("unop81", [0, 1]), ([], [1]))

    def test_long_to_int_reads_a_pair_and_writes_a_narrow(self):
        self.assertEqual(P.wide_pairs("unop84", [0, 2]), ([3], []))

    def test_add_long_is_wide_throughout(self):
        sources, destinations = P.wide_pairs("binop9b", [0, 2, 4])
        self.assertEqual(sorted(sources), [3, 5])
        self.assertEqual(destinations, [1])

    def test_shl_long_takes_a_narrow_shift_count(self):
        # 0xa3. The value is a pair, the shift distance is an int.
        sources, destinations = P.wide_pairs("binopa3", [0, 2, 4])
        self.assertEqual(sources, [3])
        self.assertEqual(destinations, [1])

    def test_narrow_instructions_contribute_nothing(self):
        self.assertEqual(P.wide_pairs("iget", [3, 2]), ([], []))
        self.assertEqual(P.wide_pairs("const/4", [1]), ([], []))
        self.assertEqual(P.wide_pairs("invoke-virtual", [1, 2]), ([], []))

    def test_no_registers_is_not_an_index_error(self):
        self.assertEqual(P.wide_pairs("nop", []), ([], []))


class ReadsFirstOperand(unittest.TestCase):
    def test_check_cast_is_a_source(self):
        # check-cast reads its register, verifies the type and leaves the value. Treating it as a
        # write let liveness discard the register and stop propagating it backward.
        self.assertTrue("check-cast".startswith(P.READS_FIRST_OPERAND))

    def test_filled_new_array_is_a_source(self):
        # Not covered by the 'fill-array' prefix, which matches only fill-array-data.
        self.assertTrue("filled-new-array".startswith(P.READS_FIRST_OPERAND))
        self.assertTrue("filled-new-array/range".startswith(P.READS_FIRST_OPERAND))

    def test_fill_array_data_is_still_a_source(self):
        self.assertTrue("fill-array-data".startswith(P.READS_FIRST_OPERAND))

    def test_a_plain_move_is_not(self):
        self.assertFalse("move-object".startswith(P.READS_FIRST_OPERAND))


class LiveFree(unittest.TestCase):
    def free(self, ins, count, at_pc):
        return set(P.live_free(ins, count, at_pc))

    def test_a_register_read_later_is_not_free(self):
        # No write between the query point and the read, so the value has to come from before.
        ins = stream(
            ("nop", ""),
            ("invoke-static", "{v0}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertNotIn(0, self.free(ins, 4, 0))

    def test_a_register_the_queried_instruction_writes_is_free(self):
        # Liveness at a pc is live-*in*. An instruction that writes a register without reading it
        # makes that register dead on entry, which is what lets an emission insert there and use
        # it -- the property the scrub clamp depends on at its float-to-double.
        ins = stream(
            ("const/4", "v0, #1"),
            ("invoke-static", "{v0}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertIn(0, self.free(ins, 4, 0))

    def test_a_register_written_before_its_next_read_is_free(self):
        ins = stream(
            ("nop", ""),
            ("const/4", "v0, #1"),
            ("invoke-static", "{v0}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertIn(0, self.free(ins, 4, 0))

    def test_range_invoke_arguments_are_live(self):
        # The defect: `regs` gave [3, 12] for `{v3 .. v12}`, so v4-v11 were reported dead at the
        # instruction that passes them as arguments.
        ins = stream(
            ("nop", ""),
            ("invoke-virtual/range", "{v3 .. v8}, Lfoo;->bar(IIIIII)V"),
            ("return-void", ""),
        )
        free = self.free(ins, 12, 0)
        for register in range(3, 9):
            self.assertNotIn(register, free, f"v{register} is a range argument and must be live")

    def test_check_cast_does_not_kill_its_register(self):
        ins = stream(
            ("nop", ""),
            ("check-cast", "v5, Lfoo;"),
            ("invoke-virtual", "{v5}, Lfoo;->bar()V"),
            ("return-void", ""),
        )
        self.assertNotIn(5, self.free(ins, 8, 0))

    def test_two_addr_reads_its_destination(self):
        ins = stream(
            ("nop", ""),
            ("binop2addrb0", "v2, v3"),
            ("invoke-static", "{v2}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertNotIn(2, self.free(ins, 8, 0))

    def test_filled_new_array_reads_its_arguments(self):
        ins = stream(
            ("nop", ""),
            ("filled-new-array", "{v0, v1}, [I"),
            ("move-result-object", "v2"),
            ("return-void", ""),
        )
        free = self.free(ins, 8, 0)
        self.assertNotIn(0, free)
        self.assertNotIn(1, free)

    def test_a_wide_write_kills_both_halves(self):
        ins = stream(
            ("nop", ""),
            ("move-result-wide", "v4"),
            ("invoke-static", "{v4, v5}, Lfoo;->bar(J)V"),
            ("return-void", ""),
        )
        free = self.free(ins, 8, 0)
        self.assertIn(4, free)
        self.assertIn(5, free, "the high half of a wide destination is written too")

    def test_a_wide_read_keeps_both_halves_live(self):
        ins = stream(
            ("nop", ""),
            ("iput-wide", "v4, v9, Lfoo;->x:J"),
            ("return-void", ""),
        )
        free = self.free(ins, 12, 0)
        self.assertNotIn(4, free)
        self.assertNotIn(5, free, "the high half of a wide source is read too")

    def test_a_branch_past_the_write_keeps_the_register_live(self):
        # The case preflight's own docstring records: a forward "is the next touch a write?" scan
        # calls v3 free because the fall-through writes it, while the branch reaches a read.
        ins = stream(
            ("nop", ""),                       # pc 0 -- the point under test
            if_eqz(0, 4),                      # pc 1 -- skips the write
            ("const/4", "v3, #0"),             # pc 2 -- the write a forward scan would stop at
            goto(5),                           # pc 3
            ("invoke-static", "{v3}, Lfoo;->bar(I)V"),  # pc 4 -- reached only by the branch
            ("return-void", ""),               # pc 5
        )
        self.assertNotIn(3, self.free(ins, 8, 0))

    def test_an_exception_handler_read_keeps_the_register_live(self):
        ins = stream(
            ("nop", ""),
            ("invoke-static", "{}, Lfoo;->mayThrow()V"),
            ("return-void", ""),
            ("move-exception", "v0"),
            ("invoke-static", "{v6}, Lfoo;->log(I)V"),
            ("throw", "v0"),
        )
        self.assertNotIn(6, self.free(ins, 8, 0))

    def test_a_switch_is_refused_rather_than_guessed(self):
        # The case targets live in a payload live_free does not read, so every register the cases
        # read would look dead. Refusing is the only safe answer available.
        ins = stream(
            ("packed-switch", "v0, -> 3"),
            ("return-void", ""),
        )
        with self.assertRaises(ValueError):
            P.live_free(ins, 4, 0)

    def test_fill_array_data_does_not_create_a_branch_edge(self):
        # Its operand also carries `-> pc`, and matching that invents an edge to the payload.
        ins = stream(
            ("nop", ""),
            ("fill-array-data", "v0, -> 9"),
            ("invoke-static", "{v1}, Lfoo;->bar(I)V"),
            ("return-void", ""),
        )
        self.assertNotIn(1, self.free(ins, 4, 0))


if __name__ == "__main__":
    unittest.main()
