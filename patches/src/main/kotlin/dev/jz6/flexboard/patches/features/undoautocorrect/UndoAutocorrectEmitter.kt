package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.destinationRegistersOrEmpty
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.registersRead
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * Asserted rather than adapted to. Every register the guard reads is derived from the anchor, but
 * the scratch choice rests on a liveness result taken against this build. `tools/apk/preflight.py`
 * re-derives it there with a real backward analysis over the control-flow graph; this only refuses
 * a frame that is not the one that was measured.
 */
private const val RELEASE_REGISTER_COUNT = 16

/**
 * Dead at the insertion point by that analysis, and all below v16 because the key-data constructor
 * is a `35c` invoke whose registers are nibbles.
 *
 * Not chosen by reading the instruction stream forward. Preflight's own `live_free` docstring
 * records why that is unsound — a forward "is the next touch a write?" scan called a register free
 * in `r()` that a branch reached and read — so the five below come from the backward analysis and
 * preflight re-checks them on every run.
 */
private val SCRATCH_REGISTERS = listOf(3, 5, 6, 7, 8)

/** How far past the lookup the stock null test may sit. It is two `const/4`s away on this build. */
private const val TEST_SEARCH_WINDOW = 8

private const val SKIP_LABEL = "flexboard_not_undo_autocorrect"

/**
 * Emits the revert on an upward flick that no key claims.
 *
 * Inserted immediately before the `if-eqz` that tests the `ActionDef` lookup, so the stock path is
 * reached by falling out of the guard rather than by branching into it. Nothing is excised and the
 * stock instruction keeps its identity as the label target.
 */
internal fun BytecodePatchContext.emitUndoAutocorrectOnUpFlick() {
    val method = pointerReleaseFingerprint().method
    val what = "$POINTER_DELEGATE->t"
    method.assertRegisterCount(RELEASE_REGISTER_COUNT, what)

    // Every obfuscated member the emission spells out, before a single instruction is written. A
    // rename is then a patch-time failure naming the member, rather than a verify error on a device
    // with no way to read it.
    checkMethodExists(ACTION_DEF_LOOKUP, "the action lookup this patch anchors on")
    checkMethodExists(EVENT_FROM_KEY_DATA, "the event wrapper the revert is dispatched through")
    checkFieldExists(SLIDE_UP, "the SLIDE_UP action constant")
    checkFieldExists(POINTER_DELEGATE_FIELD, "the pointer's delegate back-reference")
    checkFieldExists(EVENT_SINK_FIELD, "the delegate's event sink")
    for (field in listOf(POINTER_START_X, POINTER_START_Y, POINTER_X, POINTER_Y)) {
        checkFieldExists(field, "a pointer coordinate the corridor test reads")
    }

    val body = method.instructions.toList()
    val lookupIndex = body.indexOfSoleCall(ACTION_DEF_LOOKUP, what)

    // Both registers read off the anchor rather than pinned.
    val pointerRegister = body[lookupIndex].invokeRegisterAt(0)
    val directionRegister = body[lookupIndex].invokeRegisterAt(1)

    val move = body[lookupIndex + 1]
    check(move.opcodeName() == "MOVE_RESULT_OBJECT") {
        "$ACTION_DEF_LOOKUP in $what is not followed by a move-result-object but by " +
            "${move.opcodeName()} — the shape the ActionDef register is read from is gone"
    }
    val actionDefRegister = (move as OneRegisterInstruction).registerA

    // The stock null test. Searched for rather than assumed adjacent: on this build two `const/4`s
    // sit between it and the move-result, and assuming adjacency is how the first attempt at this
    // patch pointed at the wrong instruction.
    val insertIndex = (lookupIndex + 2 until minOf(lookupIndex + 2 + TEST_SEARCH_WINDOW, body.size))
        .firstOrNull { index ->
            val instruction = body[index]
            instruction.opcodeName() == "IF_EQZ" &&
                (instruction as? OneRegisterInstruction)?.registerA == actionDefRegister
        } ?: error(
            "no if-eqz on v$actionDefRegister within $TEST_SEARCH_WINDOW instructions of the " +
                "action lookup in $what — the fall-through this emission relies on is not there"
        )
    val stockTest = body[insertIndex]

    validateScratchRegisters(
        scratch = SCRATCH_REGISTERS,
        avoid = listOf(pointerRegister, directionRegister, actionDefRegister),
        what = what,
    )
    assertNotReadBeforeWritten(body, insertIndex, what)

    val (a, b, c, d, e) = SCRATCH_REGISTERS

    method.addInstructionsWithLabels(
        insertIndex,
        """
            sget-object v$a, $SLIDE_UP
            if-ne v$directionRegister, v$a, :$SKIP_LABEL
            if-nez v$actionDefRegister, :$SKIP_LABEL

            iget v$a, v$pointerRegister, $POINTER_X
            iget v$b, v$pointerRegister, $POINTER_START_X
            sub-float/2addr v$a, v$b
            iget v$b, v$pointerRegister, $POINTER_Y
            iget v$c, v$pointerRegister, $POINTER_START_Y
            sub-float/2addr v$b, v$c
            invoke-static { v$a }, Ljava/lang/Math;->abs(F)F
            move-result v$a
            invoke-static { v$b }, Ljava/lang/Math;->abs(F)F
            move-result v$b
            add-float/2addr v$a, v$a
            cmpg-float v$c, v$a, v$b
            if-gtz v$c, :$SKIP_LABEL

            new-instance v$a, $KEY_DATA
            const/16 v$b, $REVERT_AUTOCORRECT
            const v$c, $EVENT_PRIORITY
            const/4 v$d, 0x0
            invoke-direct { v$a, v$b, v$d, v$d, v$c }, $KEY_DATA_CTOR
            invoke-static { v$a }, $EVENT_FROM_KEY_DATA
            move-result-object v$a
            iget-object v$e, v$pointerRegister, $POINTER_DELEGATE_FIELD
            check-cast v$e, $POINTER_DELEGATE
            iget-object v$e, v$e, $EVENT_SINK_FIELD
            invoke-interface { v$e, v$a }, $DISPATCH_EVENT
        """.trimIndent(),
        ExternalLabel(SKIP_LABEL, stockTest),
    )
}

/**
 * Refuses a build where a scratch register is read before anything writes it, walking forward from
 * the insertion point.
 *
 * A veto, never a licence. This follows the instruction stream rather than the control-flow graph,
 * so a register reached only by a branch is not modelled and a *pass* here proves nothing. The
 * registers were chosen from preflight's backward analysis; this exists so that a build which moved
 * them fails at patch time as well as in the gate.
 */
private fun assertNotReadBeforeWritten(body: List<Instruction>, insertIndex: Int, what: String) {
    for (register in SCRATCH_REGISTERS) {
        for (index in insertIndex until body.size) {
            val instruction = body[index]
            if (register in instruction.destinationRegistersOrEmpty()) break
            if (register !in instruction.registersRead()) continue
            error(
                "v$register is read by `${instruction.opcodeName()}` at $index before anything " +
                    "writes it, walking forward from the insertion point in $what — it carries a " +
                    "live value across the seam and cannot be scratch",
            )
        }
    }
}
