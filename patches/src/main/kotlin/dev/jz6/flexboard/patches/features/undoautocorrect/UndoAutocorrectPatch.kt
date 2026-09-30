package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Swipe up on a letter key to undo, without the key being typed.
 *
 * **Not working yet, and being rebuilt.** The current emission lives in the key pipeline
 * (ConsumeEmitter.kt), and review showed it cannot fire: the check meant to spare keys with their
 * own swipe-up action uses a lookup that falls back to the key's PRESS action, so it is always
 * taken; and even without that, the claim point is unreachable for a flick from the top row, which
 * detaches the finger from every key. docs/undo-autocorrect-plan.md has the detail.
 *
 * It moves to the motion-event-handler layer — where swipe left and swipe right run, and where the
 * "Swipe up diagnostic (temporary)" patch now measures — once that diagnostic has shown whether a
 * real flick crosses the threshold before the finger lifts.
 *
 * The keycode it sends is Gboard's general UNDO, so with no autocorrection pending a swipe undoes the
 * last edit. Accepted as the design.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Experimental and not yet working — it is being rebuilt. Intended to undo the " +
        "last autocorrection, or the last edit, when you swipe up on a letter key, without typing " +
        "the key. Off by default; leave it off until a release says it works.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitUpFlickTracking()
        emitConsumingUndoAutocorrect()
    }
}
