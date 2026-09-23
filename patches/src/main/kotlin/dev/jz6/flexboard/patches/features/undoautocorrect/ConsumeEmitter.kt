package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.methodDescriptorOrNull
import dev.jz6.flexboard.patches.shared.opcodeName
import dev.jz6.flexboard.patches.shared.sole
import dev.jz6.flexboard.patches.shared.usesField
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import dev.jz6.flexboard.patches.shared.assertRegisterCount
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * Swipe up to undo autocorrect, by telling Gboard the pointer was already handled.
 *
 * This is "option B" from `docs/undo-autocorrect-plan.md`, and it exists because the emission it
 * replaces could not meet goal 2 — *the key must not be typed*. That one hooks `Lpvf;->t` after
 * Gboard has already decided the pointer is a keypress, and every way of un-deciding it either
 * typed the letter anyway or crashed at class load.
 *
 * ### Why this one cannot crash the same way
 *
 * `Lpvi;->G` is Gboard's own "already handled" question, asked *before* any per-direction dispatch.
 * Returning true from it skips the commit and lands on a block Gboard reaches from eight other arms
 * — the block that does `move-object v3, v13` and exits. That `move-object` is the handover
 * `2.5.0-dev.0` and `dev.1` failed to emit and crashed without. Here it is not our instruction to
 * get right: **nothing jumps, so nothing merges.** The method returns and Gboard branches.
 *
 * ### Why it needs no register analysis
 *
 * The emission goes at pc 0. `G` has twenty registers and five parameters, so `this` is v15 and
 * v0–v14 are locals that nothing has written yet. There is no liveness question to get wrong, which
 * matters more than it sounds: the same liveness mistake has shipped from this repo twice, in the
 * same file, days apart. The safest analysis is the one that is not required.
 *
 * ### What it asks before claiming
 *
 * Three questions, in increasing cost:
 *
 *  1. is the gesture an upward slide, by Gboard's own reckoning (`Lpvi;->i()`);
 *  2. does this key define an upward action of its own (`Lpvi;->j(SLIDE_UP)`) — if it does, this is
 *     flick-for-symbols and none of our business;
 *  3. is the motion within a vertical corridor, `2·|dx| ≤ |dy|`.
 *
 * Question 2 is the one the old emission got for free by anchoring where that lookup returned null.
 * Asking it explicitly is what keeps flick-for-symbols working from the new position.
 */
/**
 * Where the journey is recorded, and where it is asked about.
 *
 * Declared beside the emissions rather than in `Fingerprints.kt`, because
 * `check_shared_constants.py` matches a file's extension descriptors against the calls emitted *in
 * that file*. Split across two files it found descriptors, matched no call, and reported that it
 * had silently stopped checking — which is the guard working.
 */
internal const val TRACK_MOVE =
    "Ldev/jz6/flexboard/extension/gesture/UpFlickTracker;->track(IFFFF)V"
internal const val WAS_UP_FLICK =
    "Ldev/jz6/flexboard/extension/gesture/UpFlickTracker;->wasUpFlick(IFF)Z"

/**
 * Records every pointer's furthest upward travel, once per move event.
 *
 * The companion to [emitConsumingUndoAutocorrect], and the reason the gesture can be recognised at
 * all. Gboard's `Lpvi;->h` classifies once at release from start-to-end displacement, which is why
 * this gesture fired intermittently while the scrub — a handler that sees every event — never
 * misses. Watching the journey is the property that makes the difference, not a lower threshold.
 */
internal fun BytecodePatchContext.emitUpFlickTracking() {
    val method = pointerMoveFingerprint().method
    val what = "$POINTER_DELEGATE->h"
    checkMethodExists(TRACK_MOVE, "the up-flick tracker in the extension")
    for (field in listOf(POINTER_ID, POINTER_START_X, POINTER_START_Y, POINTER_X, POINTER_Y)) {
        checkFieldExists(field, "a pointer field the move emission reads")
    }

    val body = method.instructions.toList()
    check(body.count { it.callsMethod(TRACK_MOVE) } == 0) {
        // Worded to match the guard in the consuming emission, because either can fire first and
        // the reader should get the same diagnosis either way. It said "this patch has been applied
        // twice", which named the wrong situation: the two swipe-up patches both emit here, so the
        // second one to run trips this — and it is the mutual exclusion doing its job, not a double
        // application. `tools/gate` asserts on this wording, and caught the mismatch.
        "$what already carries a Flexboard emission. \"Swipe up to undo autocorrect\" and " +
            "\"Swipe up diagnostic (temporary)\" both record the gesture here — enable one or the " +
            "other."
    }

    // After the y write, where the pointer holds both the gesture start and the current position.
    // Located by the write itself rather than by a pc, so a build that moves it is followed.
    val yWrite = body.withIndex()
        .filter { (_, instruction) -> instruction.usesField(POINTER_Y) && instruction.opcodeName().startsWith("IPUT") }
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

internal fun BytecodePatchContext.emitConsumingUndoAutocorrect(
    keycode: Int = REVERT_AUTOCORRECT,
    probe: String? = null,
    requireCorridor: Boolean = true,
    requireUnclaimedKey: Boolean = true,
) {
    val method = alreadyHandledFingerprint().method
    val what = "$POINTER->G"
    method.assertRegisterCount(ALREADY_HANDLED_REGISTER_COUNT, what)

    // Every obfuscated member the emission spells, before an instruction is written, so a rename is
    // a refused patch naming the member rather than a verify error on a device.
    checkMethodExists(WAS_UP_FLICK, "the up-flick question in the extension")
    checkInvokeKind(ACTION_DEF_LOOKUP, InvokeKind.VIRTUAL, "the action lookup that spares a symbol key")
    if (probe == null) {
        checkInvokeKind(KEY_DATA_CTOR, InvokeKind.DIRECT, "the key-data constructor the revert builds")
        checkInvokeKind(EVENT_FROM_KEY_DATA, InvokeKind.STATIC, "the event wrapper the revert uses")
        checkInvokeKind(DISPATCH_EVENT, InvokeKind.INTERFACE, "the event sink the revert is raised on")
    } else {
        checkMethodExists(probe, "the diagnostic probe in the extension")
    }
    checkFieldExists(SLIDE_UP, "the SLIDE_UP action constant")
    checkFieldExists(POINTER_DELEGATE_FIELD, "the pointer's delegate back-reference")
    checkFieldExists(EVENT_SINK_FIELD, "the delegate's event sink")
    for (field in listOf(POINTER_START_X, POINTER_START_Y, POINTER_X, POINTER_Y)) {
        checkFieldExists(field, "a pointer coordinate the corridor test reads")
    }

    // Refuse a second emission at this anchor. Both swipe-up patches attach here and Morphe cannot
    // declare two patches mutually exclusive, so this is the only thing keeping them apart --
    // `tools/gate` asserts that it fires, because a guard nobody watches is a comment.
    val body = method.instructions.toList()
    val already = body.count {
        it.callsMethod(DISPATCH_EVENT) ||
            it.methodDescriptorOrNull()?.startsWith(EXTENSION_PACKAGE) == true
    }
    check(already == 0) {
        "$what already carries a Flexboard emission. \"Swipe up to undo autocorrect\" and " +
            "\"Swipe up diagnostic (temporary)\" both attach to it — enable one or the other."
    }

    // v15 is `this`, the pointer itself, so every coordinate the corridor reads is a field on it and
    // the delegate is one hop away. v0-v4 are locals, uninitialised at pc 0.
    val pointer = ALREADY_HANDLED_REGISTER_COUNT - 5
    val (a, b, c, d, e) = CONSUME_SCRATCH
    validateScratchRegisters(
        scratch = CONSUME_SCRATCH,
        avoid = listOf(pointer),
        what = what,
        registerCount = ALREADY_HANDLED_REGISTER_COUNT,
    )

    val unclaimed = if (!requireUnclaimedKey) "" else """
            sget-object v$b, $SLIDE_UP
            invoke-virtual { v$pointer, v$b }, $ACTION_DEF_LOOKUP
            move-result-object v$b
            if-nez v$b, :$STOCK_LABEL
    """.trimIndent().prependIndent("            ")

    val corridor = if (!requireCorridor) "" else """
            iget v$a, v$pointer, $POINTER_X
            iget v$b, v$pointer, $POINTER_START_X
            sub-float/2addr v$a, v$b
            iget v$b, v$pointer, $POINTER_Y
            iget v$c, v$pointer, $POINTER_START_Y
            sub-float/2addr v$b, v$c
            invoke-static { v$a }, Ljava/lang/Math;->abs(F)F
            move-result v$a
            invoke-static { v$b }, Ljava/lang/Math;->abs(F)F
            move-result v$b
            add-float/2addr v$a, v$a
            cmpg-float v$c, v$a, v$b
            if-gtz v$c, :$STOCK_LABEL
    """.trimIndent().prependIndent("            ")

    val payload = if (probe != null) "            invoke-static { }, $probe" else """
            new-instance v$a, $KEY_DATA
            const/16 v$b, $keycode
            const v$c, $EVENT_PRIORITY
            const/4 v$d, 0x0
            invoke-direct { v$a, v$b, v$d, v$d, v$c }, $KEY_DATA_CTOR
            invoke-static { v$a }, $EVENT_FROM_KEY_DATA
            move-result-object v$a
            iget-object v$e, v$pointer, $POINTER_DELEGATE_FIELD
            check-cast v$e, $POINTER_DELEGATE
            iget-object v$e, v$e, $EVENT_SINK_FIELD
            invoke-interface { v$e, v$a }, $DISPATCH_EVENT
    """.trimIndent().prependIndent("            ")

    // `const/4 v$a, 0x1 / return v$a` is the whole of goal 2. Gboard's caller does the rest.
    method.addInstructionsWithLabels(
        0,
        """
            iget v$a, v$pointer, $POINTER_ID
            iget v$b, v$pointer, $POINTER_START_X
            iget v$c, v$pointer, $POINTER_START_Y
            invoke-static { v$a, v$b, v$c }, $WAS_UP_FLICK
            move-result v$a
            if-eqz v$a, :$STOCK_LABEL
$unclaimed$corridor
$payload
            const/4 v$a, 0x1
            return v$a
        """.trimIndent(),
        ExternalLabel(STOCK_LABEL, body.first()),
    )
}
