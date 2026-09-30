package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.methodDescriptorOrNull
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.usesField
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/*
 * The real "Swipe up to undo autocorrect" emission, in the key pipeline.
 *
 * **This design is being replaced, and as it stands it cannot fire.** Two findings from review,
 * both verified against the dex; see docs/undo-autocorrect-plan.md.
 *
 *  1. `Lpvi;->G` is only reached while the finger is still attached to a key. Letter keys declare
 *     no upward action, so Gboard treats an upward slide as moving onto another key, and above the
 *     top row there is none: the finger detaches and `G` never runs. Confirmed on a device, where a
 *     top-row flick produced nothing and a bottom-row flick produced a report.
 *  2. The "does this key own an upward action" check asks `Lpvi;->j(SLIDE_UP)`, whose lookup falls
 *     back to the key's PRESS action when there is no exact match. It is therefore never null on a
 *     letter key, the skip is always taken, and the payload is unreachable.
 *
 * The replacement lives in the motion-event-handler layer, the one swipe left and swipe right use,
 * where the diagnostic now measures (FlickProbeEmitter.kt). This file is kept, not rewritten, until
 * that diagnostic has answered whether a real flick crosses the threshold before the finger lifts,
 * because the answer decides the new shape.
 *
 * What remains true of this emission, and is worth keeping when it is rebuilt:
 *  - Returning true from `G` skips the keypress commit. Confirmed on a device (2.5.1-dev.1).
 *  - Inserting at pc 0 of `G` needs no liveness analysis: twenty registers, five parameters, every
 *    local unwritten on entry.
 */

/**
 * Where the journey is recorded, and where it is asked about.
 *
 * Declared beside the emissions rather than in `Fingerprints.kt`, because
 * `check_shared_constants.py` matches a file's extension descriptors against the calls emitted in
 * that same file.
 */
internal const val TRACK_MOVE =
    "Ldev/jz6/flexboard/extension/gesture/UpFlickTracker;->track(IFFFF)V"
internal const val WAS_UP_FLICK =
    "Ldev/jz6/flexboard/extension/gesture/UpFlickTracker;->wasUpFlick(IFF)Z"

/**
 * Feeds each pointer's position to the tracker, once per move event.
 *
 * Placed after the one write to the pointer's y in `TouchActionBundle.handleActionMove`, located by
 * that write rather than by a pc. The scratch registers are dead there by `preflight.live_free`,
 * which matters more than usual: this is inside the per-pointer loop, so a register that is not
 * really free corrupts every later pointer in the same event.
 *
 * Inherits the move path's skips: a pointer whose index is stale, and one whose `M()` is false —
 * which includes every pointer that has detached from its key, so a top-row flick stops being
 * sampled at exactly the moment it leaves the keyboard.
 */
internal fun BytecodePatchContext.emitUpFlickTracking() {
    val method = pointerMoveFingerprint().method
    val what = "$POINTER_DELEGATE->h"
    checkMethodExists(TRACK_MOVE, "the up-flick tracker in the extension")
    for (field in listOf(POINTER_ID, POINTER_START_X, POINTER_START_Y, POINTER_X, POINTER_Y)) {
        checkFieldExists(field, "a pointer field the move emission reads")
    }

    val body = method.instructions.toList()
    check(body.none { it.callsMethod(TRACK_MOVE) }) {
        "$what already feeds the up-flick tracker — the patch has been applied twice"
    }

    val yWrite = body.withIndex()
        .filter { (_, instruction) ->
            instruction.usesField(POINTER_Y) && instruction.opcodeName().startsWith("IPUT")
        }
        .sole { "$what writes $POINTER_Y $it times, expected exactly one" }
    val pointerRegister = (yWrite.value as TwoRegisterInstruction).registerB

    validateScratchRegisters(
        scratch = MOVE_SCRATCH,
        avoid = listOf(pointerRegister),
        what = what,
        registerCount = method.implementation!!.registerCount,
    )
    val (a, b, c, d, e) = MOVE_SCRATCH

    method.addInstructions(
        yWrite.index + 1,
        """
            iget v$a, v$pointerRegister, $POINTER_ID
            iget v$b, v$pointerRegister, $POINTER_START_X
            iget v$c, v$pointerRegister, $POINTER_START_Y
            iget v$d, v$pointerRegister, $POINTER_X
            iget v$e, v$pointerRegister, $POINTER_Y
            invoke-static { v$a, v$b, v$c, v$d, v$e }, $TRACK_MOVE
        """.trimIndent(),
    )
}

/**
 * At pc 0 of `Lpvi;->G`: if the tracker saw an upward flick, send Gboard's UNDO and return true so
 * the keypress is not committed. See the file header for why this is unreachable today.
 */
internal fun BytecodePatchContext.emitConsumingUndoAutocorrect() {
    val method = alreadyHandledFingerprint().method
    val what = "$POINTER->G"
    method.assertRegisterCount(ALREADY_HANDLED_REGISTER_COUNT, what)

    // Every obfuscated member the emission spells, before an instruction is written, so a rename is
    // a refused patch naming the member rather than a verify error on a device.
    checkMethodExists(WAS_UP_FLICK, "the up-flick question in the extension")
    checkInvokeKind(ACTION_DEF_LOOKUP, InvokeKind.VIRTUAL, "the action lookup")
    checkInvokeKind(KEY_DATA_CTOR, InvokeKind.DIRECT, "the key-data constructor the undo builds")
    checkInvokeKind(EVENT_FROM_KEY_DATA, InvokeKind.STATIC, "the event wrapper the undo uses")
    checkInvokeKind(DISPATCH_EVENT, InvokeKind.INTERFACE, "the event sink the undo is raised on")
    checkFieldExists(SLIDE_UP, "the SLIDE_UP action constant")
    checkFieldExists(POINTER_DELEGATE_FIELD, "the pointer's delegate back-reference")
    checkFieldExists(EVENT_SINK_FIELD, "the delegate's event sink")

    val body = method.instructions.toList()
    check(body.none {
        it.callsMethod(DISPATCH_EVENT) ||
            it.methodDescriptorOrNull()?.startsWith(EXTENSION_PACKAGE) == true
    }) {
        "$what already carries a Flexboard emission — the patch has been applied twice"
    }

    // v15 is `this`, the pointer itself. v0-v4 are locals, unwritten at pc 0.
    val pointer = ALREADY_HANDLED_REGISTER_COUNT - 5
    val (a, b, c, d, e) = CONSUME_SCRATCH
    validateScratchRegisters(
        scratch = CONSUME_SCRATCH,
        avoid = listOf(pointer),
        what = what,
        registerCount = ALREADY_HANDLED_REGISTER_COUNT,
    )

    method.addInstructionsWithLabels(
        0,
        """
            iget v$a, v$pointer, $POINTER_ID
            iget v$b, v$pointer, $POINTER_START_X
            iget v$c, v$pointer, $POINTER_START_Y
            invoke-static { v$a, v$b, v$c }, $WAS_UP_FLICK
            move-result v$a
            if-eqz v$a, :$STOCK_LABEL
            sget-object v$b, $SLIDE_UP
            invoke-virtual { v$pointer, v$b }, $ACTION_DEF_LOOKUP
            move-result-object v$b
            if-nez v$b, :$STOCK_LABEL
            new-instance v$a, $KEY_DATA
            const/16 v$b, $UNDO_KEYCODE
            const v$c, $EVENT_PRIORITY
            const/4 v$d, 0x0
            invoke-direct { v$a, v$b, v$d, v$d, v$c }, $KEY_DATA_CTOR
            invoke-static { v$a }, $EVENT_FROM_KEY_DATA
            move-result-object v$a
            iget-object v$e, v$pointer, $POINTER_DELEGATE_FIELD
            check-cast v$e, $POINTER_DELEGATE
            iget-object v$e, v$e, $EVENT_SINK_FIELD
            invoke-interface { v$e, v$a }, $DISPATCH_EVENT
            const/4 v$a, 0x1
            return v$a
        """.trimIndent(),
        ExternalLabel(STOCK_LABEL, body.first()),
    )
}
