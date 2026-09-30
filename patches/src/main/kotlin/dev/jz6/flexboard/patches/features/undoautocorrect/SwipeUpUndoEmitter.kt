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
 * The decision, in the extension. Declared beside its emission because `check_shared_constants.py`
 * matches a file's extension descriptors against the calls emitted in that same file.
 */
internal const val SWIPE_UP_ON_EVENT =
    "Ldev/jz6/flexboard/extension/gesture/SwipeUpUndo;->onEvent(Ljava/lang/Object;Landroid/view/MotionEvent;)I"

private const val SCRUB_HANDLER =
    "Lcom/google/android/libraries/inputmethod/motioneventhandler/scrubmove/ScrubMotionEventHandler;"

/** The handler's route to its manager: take the gesture over, or send an IME event. */
private const val HANDLER_EVENTS_FIELD = "$SCRUB_HANDLER->p:Lpvo;"
private const val TAKE_OVER = "Lpvo;->m()V"
private const val SEND_EVENT = "Lpvo;->n(Lnur;)V"

/** The only implementation of the route, and how to ask it who owns the gesture now. */
private const val ROUTE_IMPL = "Lozi;"
private const val ROUTE_MANAGER_FIELD = "Lozi;->b:Lozj;"
private const val GESTURE_OWNER_FIELD = "Lozj;->k:Lpvn;"

/** The scrub's own "is this the end of my pointer" test, at the end of every `g` call. */
private const val SCRUB_POINTER_ENDED = "$SCRUB_HANDLER->t(Landroid/view/MotionEvent;)Z"

private const val TRACE_BEGIN = "Landroid/os/Trace;->beginSection(Ljava/lang/String;)V"

private const val STOCK = "flexboard_swipe_up_stock"
private const val END = "flexboard_swipe_up_end"

/**
 * Swipe up to undo, from inside the scrub engine — the layer swipe left and swipe right run on.
 *
 * Every event reaching `ScrubMotionEventHandler->g` is first offered to `SwipeUpUndo.onEvent`:
 *
 *  - **pass**: stock `g`, untouched.
 *  - **claim**: take the gesture over with the same call the scrub makes for its own swipes
 *    (`Lpvo;->m()`), confirm it actually took, send Gboard's UNDO through the handler's own route,
 *    and skip to the end of `g`.
 *  - **swallow**: the gesture is already ours; skip to the end of `g`.
 *
 * What the takeover does, read out of the dex rather than assumed: `Lozi;->m()` records this handler
 * as the gesture's owner in `Lozj;->k` — only if nobody owns it yet — and calls `l()` on every other
 * handler, which is what makes the key pipeline drop the pending keypress without typing it. From
 * then on the dispatcher sends this gesture's events to the owner alone.
 *
 * **Why the takeover is confirmed before the undo is sent.** `m()` returns nothing and silently does
 * nothing when the gesture already has an owner. Sending the undo regardless would, on a failed
 * takeover, type the letter *and* undo. So the owner is read back and compared with this handler.
 *
 * **Why it cannot leave the keyboard stuck.** A takeover that outlived its gesture would send every
 * later tap to the scrub handler and the keyboard would stop typing. The owner is cleared by the
 * dispatcher itself — `Lozj;->o` runs after every event and nulls `k` on UP and CANCEL, whatever the
 * handler did with the event. Pinned in preflight, because it is the property this rests on.
 *
 * **Why skipped events jump to the end instead of returning.** The end of `g` asks the scrub's own
 * `t(event)` and, when its pointer has ended, calls its `l()` reset — and it closes the trace section
 * opened at the top. Jumping there keeps both; the emission is placed after `Trace.beginSection` so
 * the pair stays balanced. v0-v10 are dead at both the insertion point and the jump target
 * (`preflight.live_free`), so the six scratch registers disturb nothing stock reads.
 */
internal fun BytecodePatchContext.emitSwipeUpUndo() {
    val method = scrubHandleMotionEventFingerprint().method
    val what = "ScrubMotionEventHandler->g"

    checkMethodExists(SWIPE_UP_ON_EVENT, "the swipe-up decision in the extension")
    checkFieldExists(HANDLER_EVENTS_FIELD, "the handler's route to its manager")
    checkInvokeKind(TAKE_OVER, InvokeKind.INTERFACE, "the takeover the scrub uses for its own swipes")
    checkInvokeKind(SEND_EVENT, InvokeKind.INTERFACE, "the handler's route for IME events")
    checkFieldExists(ROUTE_MANAGER_FIELD, "the route's manager")
    checkFieldExists(GESTURE_OWNER_FIELD, "the manager's record of who owns the gesture")
    checkMethodExists(SCRUB_POINTER_ENDED, "the scrub's end-of-pointer test")
    checkInvokeKind(KEY_DATA_CTOR, InvokeKind.DIRECT, "the key-data constructor the undo builds")
    checkInvokeKind(EVENT_FROM_KEY_DATA, InvokeKind.STATIC, "the event wrapper the undo uses")

    val body = method.instructions.toList()
    check(body.none { it.callsMethod(SWIPE_UP_ON_EVENT) }) {
        "$what already carries the swipe-up emission — the patch has been applied twice"
    }

    val traceBegin = body.indexOfFirst { it.callsMethod(TRACE_BEGIN) }
    check(traceBegin >= 0) { "$what no longer opens a trace section; the insertion point has moved" }
    val insertAt = traceBegin + 1
    val endOfCall = body.indexOfSoleCall(SCRUB_POINTER_ENDED, what)

    val registerCount = method.implementation!!.registerCount
    val parameters = listOf(registerCount - 2, registerCount - 1)
    validateScratchRegisters(
        scratch = listOf(0, 1, 2, 3, 4, 5),
        avoid = parameters,
        what = what,
        registerCount = registerCount,
    )

    method.addInstructionsWithLabels(
        insertAt,
        """
            invoke-static { p0, p1 }, $SWIPE_UP_ON_EVENT
            move-result v0
            if-eqz v0, :$STOCK
            const/4 v1, 0x1
            if-ne v0, v1, :$END
            iget-object v1, p0, $HANDLER_EVENTS_FIELD
            invoke-interface { v1 }, $TAKE_OVER
            instance-of v2, v1, $ROUTE_IMPL
            if-eqz v2, :$END
            move-object v2, v1
            check-cast v2, $ROUTE_IMPL
            iget-object v2, v2, $ROUTE_MANAGER_FIELD
            iget-object v2, v2, $GESTURE_OWNER_FIELD
            if-ne v2, p0, :$END
            new-instance v2, $KEY_DATA
            const/16 v3, $UNDO_KEYCODE
            const v4, $EVENT_PRIORITY
            const/4 v5, 0x0
            invoke-direct { v2, v3, v5, v5, v4 }, $KEY_DATA_CTOR
            invoke-static { v2 }, $EVENT_FROM_KEY_DATA
            move-result-object v2
            invoke-interface { v1, v2 }, $SEND_EVENT
            goto :$END
        """.trimIndent(),
        ExternalLabel(STOCK, body[insertAt]),
        ExternalLabel(END, body[endOfCall]),
    )
}
