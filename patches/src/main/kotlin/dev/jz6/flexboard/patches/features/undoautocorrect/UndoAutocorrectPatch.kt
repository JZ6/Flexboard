package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Swipe up on the keyboard to undo the last autocorrection.
 *
 * Built in stages from the diagnostic that measured the gesture, because the previous attempt —
 * which went straight to taking the gesture over and sending an undo — crashed the keyboard on a
 * swipe up, while the diagnostic it was built beside never did. One capability per release, so a
 * failure names its own cause:
 *
 *  1. **detect, and type a single "6"** — the current stage; nothing in Gboard is called;
 *  2. take the gesture over, so the swiped key is not typed;
 *  3. send an undo in place of the "6";
 *  4. undo only when an autocorrection is armed, as Gboard's own backspace revert does.
 *
 * It runs in the motion-event-handler layer, beside swipe left to delete and swipe right to undo.
 * See SwipeUpEmitter.kt, SwipeUp.java and docs/undo-autocorrect-plan.md.
 *
 * Replaces "Swipe up diagnostic (temporary)", whose measuring code this now is.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Work in progress, being built in stages. This build only detects the gesture: " +
        "swipe up on the keyboard and it types a 6. It does not undo anything yet, and the swiped " +
        "key is still typed. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitSwipeUp()
    }
}
