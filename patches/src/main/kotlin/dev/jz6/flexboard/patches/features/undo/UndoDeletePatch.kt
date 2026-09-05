package dev.jz6.flexboard.patches.features.undo

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch


/**
 * Swipe right, after the delete gesture has ended, to put back the words it removed.
 *
 * Gboard's own right-swipe restore only works *inside* a gesture — drag back before lifting and the
 * count shrinks toward zero. Lift, and the deletion is committed. This makes a fresh rightward
 * swipe undo it.
 *
 * ## Almost all of this already exists
 *
 * The first estimate for this feature assumed Flexboard would have to capture the deleted text
 * itself and reinsert it. It does not. Gboard already:
 *
 *  - **records the text.** `SCRUB_DELETE_FINISH` calls `Lomu;->a(I)`, which performs the deletion
 *    and *returns what it removed*, and the handler stores that in the undo slot
 *    (`LatinIme->y:Lqyc;`). The words a swipe deleted are sitting there when the finger lifts.
 *  - **knows how to put it back.** The stock `UNDO_MULTI_DELETION` handler pulls the slot and
 *    re-commits through an `AbstractIme` hook — `s` on 17.7.7, `t` on 18, which is why this patch
 *    reads the method out of that handler rather than naming it. Its own gate,
 *    `nga_enable_undo_delete`, is declared with a default of `true`, so there is nothing to turn on.
 *
 * The only missing piece was a way to ask for it. That is all this patch adds.
 *
 * ## Why a positive count is the right trigger
 *
 * `Lomu;->e(I)` opens with `count = Math.min(0, count)`, so a rightward scrub clamps to zero and
 * deletes nothing — the gesture is already an established no-op, and nothing is being taken away by
 * giving it a meaning. The clamp lives inside `Lomu;`, not in the event, so the signed count still
 * reaches the finish handler intact and `count > 0` *is* "the user swiped right".
 *
 * The obvious alternative — "the scrub finished having deleted nothing" — would also fire on a
 * short leftward flick that never crossed the first distance threshold, undoing something the user
 * was not asking about.
 *
 * ## Reusing the suppression branch instead of naming a target
 *
 * The handler's second instruction is `if-nez vFlag, :handled`, where `vFlag` is
 * `AbstractIme->O:Z` and `:handled` is the stock "treat as handled, do nothing" exit. Rather than
 * branch there — which would mean resolving a `packed-switch`-reached label — this sets `vFlag` and
 * lets the stock test do the jumping. Control flow converges on Gboard's own path with no external
 * label at all, and the epilogue's `Trace.endSection()` still runs, which an early `return` would
 * have skipped and left the trace stack unbalanced.
 */
@Suppress("unused")
val swipeRightToUndoPatch = bytecodePatch(
    name = "Swipe Right to Undo",
    description = "Swipe right after deleting to put the words back — the swipe starts on the " +
        "Delete key, or anywhere when Swipe Left to Delete is also applied. Uses Gboard's own undo, " +
        "which already records what a delete swipe removed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // No `checkPreferenceStorePins()` here any more: this patch reads no preference, so the
        // store's descriptors are not among the things it can be broken by.
        latinImeHandleEventFingerprint().method.undoOnRightwardScrub()
    }
}
