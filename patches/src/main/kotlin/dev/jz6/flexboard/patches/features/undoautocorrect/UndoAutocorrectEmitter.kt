package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.destinationRegistersOrEmpty
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.invokeRegisterAt
import dev.jz6.flexboard.patches.shared.assertNotReadBeforeWritten
import dev.jz6.flexboard.patches.shared.opcodeName
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
internal fun BytecodePatchContext.emitUndoAutocorrectOnUpFlick(
    keycode: Int = REVERT_AUTOCORRECT,
    requireCorridor: Boolean = true,
) {
    val method = pointerReleaseFingerprint().method
    val what = "$POINTER_DELEGATE->t"
    method.assertRegisterCount(RELEASE_REGISTER_COUNT, what)

    // Every obfuscated member the emission spells out, before a single instruction is written. A
    // rename is then a patch-time failure naming the member, rather than a verify error on a device
    // with no way to read it.
    checkInvokeKind(ACTION_DEF_LOOKUP, InvokeKind.VIRTUAL, "the action lookup this patch anchors on")
    checkInvokeKind(
        EVENT_FROM_KEY_DATA, InvokeKind.STATIC,
        "the event wrapper the revert is dispatched through",
    )
    // Both of these are spelled in the emission below and neither was checked, despite the comment
    // above claiming every member is.
    checkInvokeKind(KEY_DATA_CTOR, InvokeKind.DIRECT, "the key-data constructor the revert builds")
    checkInvokeKind(DISPATCH_EVENT, InvokeKind.INTERFACE, "the event sink the revert is raised on")
    checkFieldExists(SLIDE_UP, "the SLIDE_UP action constant")
    checkFieldExists(POINTER_DELEGATE_FIELD, "the pointer's delegate back-reference")
    checkFieldExists(EVENT_SINK_FIELD, "the delegate's event sink")
    for (field in listOf(POINTER_START_X, POINTER_START_Y, POINTER_X, POINTER_Y)) {
        checkFieldExists(field, "a pointer coordinate the corridor test reads")
    }

    val body = method.instructions.toList()

    // Both swipe-up patches attach to this one instruction, and Morphe cannot declare two patches
    // mutually exclusive, so selecting both used to fail here with "the fall-through this emission
    // relies on is not there" -- which reads as though Gboard had changed. It had not: the first
    // emission inserts about twenty instructions between the lookup and the `if-eqz`, and the
    // second patch's search window is eight.
    //
    // Stock `Lpvf;->t` contains no call to the event sink at all, pinned in preflight, so finding
    // one means this method has already been emitted into.
    val alreadyEmitted = body.count { it.callsMethod(DISPATCH_EVENT) }
    check(alreadyEmitted == 0) {
        "$what already carries a Flexboard emission. \"Swipe up to undo autocorrect\" and " +
            "\"Swipe up diagnostic (temporary)\" both attach to the same instruction — enable one " +
            "of them, not both."
    }

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
        registerCount = RELEASE_REGISTER_COUNT,
    )
    assertNotReadBeforeWritten(body, insertIndex, SCRATCH_REGISTERS, what)

    val (a, b, c, d, e) = SCRATCH_REGISTERS

    // Dropping the corridor is only ever a diagnostic: without it an upward-ish drag that was
    // meant as a scrub also fires. It exists so one install can separate "the flick is never
    // recognised" from "the corridor rejects it".
    val corridor = if (!requireCorridor) "" else """
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
    """.trimIndent().prependIndent("            ")

    method.addInstructionsWithLabels(
        insertIndex,
        """
            sget-object v$a, $SLIDE_UP
            if-ne v$directionRegister, v$a, :$SKIP_LABEL
            if-nez v$actionDefRegister, :$SKIP_LABEL
$corridor
            new-instance v$a, $KEY_DATA
            const/16 v$b, $keycode
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
