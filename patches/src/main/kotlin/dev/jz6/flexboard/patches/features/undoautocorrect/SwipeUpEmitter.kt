package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.smali.ExternalLabel
import dev.jz6.flexboard.patches.features.swipetodelete.scrubHandleMotionEventFingerprint
import dev.jz6.flexboard.patches.shared.InvokeKind
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkFieldExists
import dev.jz6.flexboard.patches.shared.checkInvokeKind
import dev.jz6.flexboard.patches.shared.checkMethodExists
import dev.jz6.flexboard.patches.shared.indexOfSoleCall
import dev.jz6.flexboard.patches.shared.validateScratchRegisters

/**
 * The swipe-up logic, in the extension. Declared beside the emission because
 * `check_shared_constants.py` matches a file's extension descriptors against the calls emitted in
 * that same file.
 */
internal const val SWIPE_UP_DECIDE =
    "Ldev/jz6/flexboard/extension/gesture/SwipeUp;->decide(Ljava/lang/Object;Landroid/view/MotionEvent;)I"
internal const val SWIPE_UP_TOOK_OVER =
    "Ldev/jz6/flexboard/extension/gesture/SwipeUp;->tookOver(Z)V"

private const val SCRUB_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubMotionEventHandler;"

/** The handler's route to its manager, and the takeover the scrub uses for its own swipes. */
private const val HANDLER_ROUTE_FIELD = "$SCRUB_HANDLER->p:Lpvo;"
private const val TAKE_OVER = "Lpvo;->m()V"

/** The route's only implementation, and how to ask it who owns the gesture now. */
private const val ROUTE_IMPL = "Lozi;"
private const val ROUTE_MANAGER_FIELD = "Lozi;->b:Lozj;"
private const val GESTURE_OWNER_FIELD = "Lozj;->k:Lpvn;"

/** The scrub's own "is my pointer finished" test, at the end of every `g` call. */
private const val SCRUB_POINTER_ENDED = "$SCRUB_HANDLER->t(Landroid/view/MotionEvent;)Z"

private const val TRACE_BEGIN = "Landroid/os/Trace;->beginSection(Ljava/lang/String;)V"

private const val STOCK = "flexboard_swipe_up_stock"
private const val END = "flexboard_swipe_up_end"
private const val REPORT = "flexboard_swipe_up_report"

/**
 * Stage 2: take the gesture over, and report whether it took.
 *
 * This is deliberately **the takeover path of 2.5.1-dev.7, instruction for instruction**, with the
 * undo it then sent replaced by a report to `SwipeUp.tookOver`. dev.7 crashed on a swipe up, and this
 * splits that crash: if stage 2 crashes, the cause is somewhere in the takeover or in how the rest of
 * the gesture is skipped; if it does not, the cause was building or sending the undo.
 *
 * Every event reaching `ScrubMotionEventHandler->g` is first offered to `SwipeUp.decide`:
 *
 *  - **pass**: stock `g`, untouched.
 *  - **claim**: call `Lpvo;->m()` — the takeover the scrub makes for its own swipes — then read back
 *    the gesture's owner and report whether it is this handler, then skip to the end of `g`.
 *  - **swallow**: the gesture is already ours; skip to the end of `g`.
 *
 * Why the owner is read back: `m()` returns nothing, and does nothing when the gesture already has
 * an owner. Reporting regardless would hide a refused takeover.
 *
 * Why skipped events jump to the end rather than returning: the end of `g` asks the scrub's own
 * `t(event)` and, when its pointer has finished, runs its `l()` reset, then closes the trace section
 * opened at the top. The emission is placed after `Trace.beginSection` so that pair stays balanced.
 *
 * Why the takeover cannot leave the keyboard stuck: the dispatcher clears the owner itself, in
 * `Lozj;->o`, after every UP and CANCEL, whatever the handler did. Pinned in preflight.
 *
 * v0-v3 are the only registers written, and v0-v10 are dead at both the insertion point and the jump
 * target (`preflight.live_free`, pinned).
 */
internal fun BytecodePatchContext.emitSwipeUp() {
    val method = scrubHandleMotionEventFingerprint().method
    val what = "ScrubMotionEventHandler->g"

    checkMethodExists(SWIPE_UP_DECIDE, "the swipe-up decision in the extension")
    checkMethodExists(SWIPE_UP_TOOK_OVER, "the takeover report in the extension")
    checkFieldExists(HANDLER_ROUTE_FIELD, "the handler's route to its manager")
    checkInvokeKind(TAKE_OVER, InvokeKind.INTERFACE, "the takeover the scrub uses for its own swipes")
    checkFieldExists(ROUTE_MANAGER_FIELD, "the route's manager")
    checkFieldExists(GESTURE_OWNER_FIELD, "the manager's record of who owns the gesture")
    checkMethodExists(SCRUB_POINTER_ENDED, "the scrub's end-of-pointer test")

    val body = method.instructions.toList()
    check(body.none { it.callsMethod(SWIPE_UP_DECIDE) }) {
        "$what already carries the swipe-up emission — the patch has been applied twice"
    }

    val traceBegin = body.indexOfFirst { it.callsMethod(TRACE_BEGIN) }
    check(traceBegin >= 0) { "$what no longer opens a trace section; the insertion point has moved" }
    val insertAt = traceBegin + 1
    val endOfCall = body.indexOfSoleCall(SCRUB_POINTER_ENDED, what)

    val registerCount = method.implementation!!.registerCount
    validateScratchRegisters(
        scratch = listOf(0, 1, 2, 3),
        avoid = listOf(registerCount - 2, registerCount - 1),
        what = what,
        registerCount = registerCount,
    )

    method.addInstructionsWithLabels(
        insertAt,
        """
            invoke-static { p0, p1 }, $SWIPE_UP_DECIDE
            move-result v0
            if-eqz v0, :$STOCK
            const/4 v1, 0x1
            if-ne v0, v1, :$END
            iget-object v1, p0, $HANDLER_ROUTE_FIELD
            invoke-interface { v1 }, $TAKE_OVER
            const/4 v3, 0x0
            instance-of v2, v1, $ROUTE_IMPL
            if-eqz v2, :$REPORT
            move-object v2, v1
            check-cast v2, $ROUTE_IMPL
            iget-object v2, v2, $ROUTE_MANAGER_FIELD
            iget-object v2, v2, $GESTURE_OWNER_FIELD
            if-ne v2, p0, :$REPORT
            const/4 v3, 0x1
            :$REPORT
            invoke-static { v3 }, $SWIPE_UP_TOOK_OVER
            goto :$END
        """.trimIndent(),
        ExternalLabel(STOCK, body[insertAt]),
        ExternalLabel(END, body[endOfCall]),
    )
}
