package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Swipe up on a key to put back the word an autocorrect replaced.
 *
 * Gboard already has this on backspace, behind **Undo autocorrect with backspace**. That path arms
 * itself when an autocorrection lands and, on the next backspace, dispatches an event carrying
 * keycode `-10045` instead of deleting. This patch dispatches the same event from an upward flick.
 *
 * It does not go through Gboard's arming, so it works whether or not that preference is on — no
 * consumer of `-10045` reads it. Firing with nothing to revert is a no-op: both handlers null-check
 * their tracked state and return.
 *
 * ## Where it attaches, and why not somewhere more obvious
 *
 * Gboard already detects the flick. `Lpvi;->h` turns a pointer delta into SLIDE_UP/DOWN/LEFT/RIGHT
 * against a per-key threshold and already returns SLIDE_UP on Latin keys; what is missing is
 * anything to do with it, because no Latin layout binds a SLIDE_UP action. So the emission sits at
 * the `ActionDef` lookup that comes back null, and nothing here detects a gesture.
 *
 * Three routes that looked better and are not, each recorded in `docs/undo-autocorrect.md`: binding
 * a SLIDE_UP action declaratively (no Latin layout binds any slide action, and doing so would switch
 * off flick-for-symbols on that key); hooking `LatinGestureMotionEventHandler` (gated on
 * `enable_gesture_input`, which Swipe to Delete turns off); and writing our own direction detection
 * (unnecessary).
 *
 * ## The scrub gesture
 *
 * Swipe to Delete widens the scrub corridor to the full keyboard height, so an upward swipe no
 * longer cancels a scrub and the two share one pointer stream. The corridor test is what separates
 * them, and it is a ratio rather than a distance on purpose: `ScrubTuningPatch` rescales the scrub's
 * own distance table, so anything derived from that would be wrong for a tuned build.
 *
 * The separation has to be spatial because it can no longer be temporal — `ScrubTuningPatch` lowered
 * the hold delay so a scrub registers on a flick, which is the same shape as this gesture.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Swipe up on the keyboard to put back the word an autocorrect replaced. Gboard " +
        "has the same undo on backspace, behind a setting; this adds a gesture for it and works " +
        "whether or not that setting is on. A swipe with nothing to undo does nothing. Off by " +
        "default until it has been confirmed on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitUndoAutocorrectOnUpFlick()
    }
}
