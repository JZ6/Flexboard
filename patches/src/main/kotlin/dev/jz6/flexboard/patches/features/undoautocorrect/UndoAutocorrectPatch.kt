package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Swipe up on the keyboard to undo, without the swiped key being typed.
 *
 * Runs in the motion-event-handler layer, beside swipe left to delete and swipe right to undo: the
 * scrub engine offers every event to `SwipeUpUndo`, which takes the gesture over the moment it has
 * risen 24dp at no worse than a 2:1 corridor, and the emission sends Gboard's UNDO. The values are
 * the ones a device diagnostic validated — most real flicks met them mid-swipe (`6m`). See
 * SwipeUpUndoEmitter.kt for the mechanism and docs/undo-autocorrect-plan.md for how it was reached.
 *
 * It replaces a key-pipeline design that could never fire: its claim point was unreachable for a
 * flick from the top row, and its "does this key own an upward action" check always passed.
 *
 * The keycode is Gboard's general UNDO, so with no autocorrection pending a swipe undoes the last
 * edit. Accepted as the design.
 *
 * Known limit: a key that binds its own swipe-up action is not spared. No Latin layout does — letter
 * keys bind none, and flick-for-symbols binds swipe *down* — but a layout that did would lose it.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Swipe up on the keyboard to undo the last autocorrection — or, with none " +
        "pending, the last edit — without the swiped key being typed. Off by default until it has " +
        "been confirmed on a device.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitSwipeUpUndo()
    }
}
