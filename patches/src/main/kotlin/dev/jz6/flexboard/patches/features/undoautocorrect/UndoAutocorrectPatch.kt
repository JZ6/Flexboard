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
 *  1. detect, and type a single "6" — confirmed on a device;
 *  2. take the gesture over, so the swiped key is not typed — "6" if the takeover took, "x" if it
 *     was refused;
 *  3. **revert the last autocorrection** — confirmed on a device in 2.5.2-dev.0, with Gboard's
 *     "Undo autocorrect with backspace" setting off. Stages 3 and 4 of the original plan ("send an
 *     undo", then "only when an autocorrection is armed") collapsed into one: the swipe asks Gboard's
 *     decoder for its own autocorrect revert, and the decoder is the armed check.
 *
 * Like Gboard's own backspace revert, it reaches only the word just corrected: typing anything
 * after it clears the decoder's revert. Keeping a separate history to go further back was
 * considered and declined in favour of the native behaviour.
 *
 * Two emissions: SwipeUpEmitter.kt takes the gesture over and sends the request, and
 * RevertEmitter.kt teaches `LatinIme->q` to hand it to the decoder. See SwipeUp.java and
 * docs/undo-autocorrect-plan.md.
 *
 * Replaces "Swipe up diagnostic (temporary)", whose measuring code this now is. The crash recorder
 * this patch used to install while it was being tested is the opt-in "Crash reporter (debug)" now.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Swipe up on the keyboard to undo the last autocorrection: the word you typed " +
        "comes back, as with Gboard's own undo autocorrect on backspace. It works right after the " +
        "correction, before you type anything else; otherwise the swipe does nothing. The key you " +
        "swiped on is not typed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // The receiving end first: if it cannot apply, nothing has been changed yet. A request with
        // no receiver would be harmless anyway, since no stock code acts on -10076 as an event.
        routeRevertsToTheDecoder()
        emitSwipeUp()
    }
}
