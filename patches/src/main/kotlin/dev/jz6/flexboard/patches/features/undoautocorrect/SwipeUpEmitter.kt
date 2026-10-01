package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import dev.jz6.flexboard.patches.features.swipetodelete.scrubHandleMotionEventFingerprint
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkMethodExists

/**
 * The swipe-up logic, in the extension. Declared beside its emission because
 * `check_shared_constants.py` matches a file's extension descriptors against the calls emitted in
 * that same file.
 */
internal const val SWIPE_UP_OBSERVE =
    "Ldev/jz6/flexboard/extension/gesture/SwipeUp;->observe(Ljava/lang/Object;Landroid/view/MotionEvent;)V"

/**
 * Feeds every motion event the scrub engine sees to `SwipeUp`.
 *
 * This is the diagnostic's emission, unchanged, because it is the one thing in this feature that has
 * been watched working on a device: one `invoke-static {p0, p1}` — the handler and the event, both
 * parameters — writing no register, placed first in the scrub engine's `g`. The scrub engine is a
 * motion event handler, so it receives DOWN, every MOVE and UP for the view the finger went down on,
 * whether or not the key pipeline still considers the finger attached to a key.
 *
 * Stage 1 needs nothing more, because `SwipeUp` acts on its own (it types "6") and calls nothing in
 * Gboard. Stage 2 adds the takeover, which has to be done in Gboard's own terms and so will widen this
 * emission. Keeping stage 1 to the proven instruction means that if stage 1 misbehaves, the cause is
 * in `SwipeUp.java`, not here.
 *
 * Swipe-to-delete's own edits to `g` find their insertion points by content, so a one-instruction
 * prepend changes nothing for them whichever order the patches run in.
 */
internal fun BytecodePatchContext.emitSwipeUp() {
    val method = scrubHandleMotionEventFingerprint().method
    val what = "ScrubMotionEventHandler->g"
    checkMethodExists(SWIPE_UP_OBSERVE, "the swipe-up logic in the extension")
    check(method.instructions.none { it.callsMethod(SWIPE_UP_OBSERVE) }) {
        "$what already feeds the swipe-up logic — the patch has been applied twice"
    }
    method.addInstructions(
        0,
        "invoke-static { p0, p1 }, $SWIPE_UP_OBSERVE",
    )
}
