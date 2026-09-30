package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import dev.jz6.flexboard.patches.features.swipetodelete.scrubHandleMotionEventFingerprint
import dev.jz6.flexboard.patches.shared.callsMethod
import dev.jz6.flexboard.patches.shared.checkMethodExists

/**
 * The flick probe, in the extension. Declared beside its emission because
 * `check_shared_constants.py` matches a file's extension descriptors against the calls emitted in
 * that same file.
 */
internal const val FLICK_PROBE_OBSERVE =
    "Ldev/jz6/flexboard/extension/gesture/FlickProbe;->observe(Ljava/lang/Object;Landroid/view/MotionEvent;)V"

/**
 * Feeds every motion event the scrub engine sees to the flick probe.
 *
 * The scrub engine is a motion event handler, and handlers see the raw event stream — DOWN, every
 * MOVE, UP — for the view the finger went down on, whether or not the key pipeline still considers
 * the finger attached to a key. That is the property the previous probe lacked. It reported from
 * `Lpvi;->G`, which runs only while the finger is on a key; letter keys declare no upward action, so
 * an upward flick is treated as moving onto another key, and above the top row there is none. On a
 * device, top-row flicks reported nothing and bottom-row flicks reported numbers.
 *
 * **Register-free.** One `invoke-static {p0, p1}` — the handler and the event, both parameters —
 * that writes no register. Nothing about the method's liveness can be disturbed by it.
 *
 * **No interaction with the scrub's own edits.** `acceptWildcardStartKey` and
 * `trackAcrossFullKeyboard` locate their insertion points by content, not by index, so a
 * one-instruction prepend changes nothing for them whichever order the patches run in.
 */
internal fun BytecodePatchContext.emitFlickProbe() {
    val method = scrubHandleMotionEventFingerprint().method
    val what = "ScrubMotionEventHandler->g"
    checkMethodExists(FLICK_PROBE_OBSERVE, "the flick probe in the extension")
    check(method.instructions.none { it.callsMethod(FLICK_PROBE_OBSERVE) }) {
        "$what already feeds the flick probe — the diagnostic has been applied twice"
    }
    method.addInstructions(
        0,
        "invoke-static { p0, p1 }, $FLICK_PROBE_OBSERVE",
    )
}
