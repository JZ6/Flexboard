package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * The extension call the emission makes instead of raising a Gboard event.
 *
 * Round one deleted a character, which was unmistakable and also ate text — and a deletion is a
 * poor signal anyway, since it is what a mis-fired scrub looks like too. Typing a marker through
 * `InputConnection.commitText` is clearer and non-destructive.
 *
 * Dispatching a printable character through Gboard's own event stream would have been the obvious
 * change and is the wrong one: a printable key travels as a keycode plus a `String` payload whose
 * convention I could not establish from the dex without guessing, and a wrong guess does not fail
 * loudly. It dispatches an event nothing consumes, which is indistinguishable from the gesture
 * never firing — a false negative in the one measurement this patch exists to take.
 */
private const val GESTURE_PROBE =
    "Ldev/jz6/flexboard/extension/diagnostic/GestureProbe;->fired()V"

/**
 * **Temporary. Delete this file once the question it answers is answered.**
 *
 * *Swipe up to undo autocorrect* does nothing on a device, and "does nothing" has two very different
 * causes that look identical from the outside:
 *
 *  1. the gesture is never recognised — the emission runs, but SLIDE_UP is not what the direction
 *     comes back as, or the `ActionDef` is not null, or the corridor rejects the swipe; or
 *  2. the gesture is recognised and dispatches correctly, and the **revert itself** is the no-op,
 *     because Gboard had nothing armed to revert.
 *
 * Nothing in the gate can tell those apart. There is no Android SDK here, so no patch is ever
 * executed locally, and the emission has no way to report anything from a phone.
 *
 * So this is the same patch with the payload changed: identical anchor, identical guards, and a
 * call into the extension where the revert dispatch was. Install it instead of the real patch and
 * swipe up over a letter.
 *
 * **A marker character appears** — the whole chain works: gesture, guards, and the emission being
 * reached. The fault is downstream, in the revert being unarmed, and the fix is the one-slot
 * capture-and-restore rather than anything about the gesture.
 *
 * **Nothing happens** — the chain fails before the emission is reached.
 *
 * Round one deleted a character instead, which was unmistakable and also ate text; worse, a
 * deletion is what a mis-fired scrub looks like, so it could not distinguish the two. This
 * paragraph described that build for two rounds after it stopped existing.
 *
 * Round two ran with only the direction test and answered the question: the gesture *is* detected,
 * intermittently, and the key was still typed alongside the marker. Both findings were mine to fix
 * — the emission fell through into Gboard's commit instead of consuming the gesture, and the slide
 * threshold sits right at the length of a normal swipe.
 *
 * Round three is the same probe against both fixes. The marker should now appear **instead of** the
 * letter, and on an ordinary upward swipe rather than an exaggerated one. A letter still appearing
 * means the consume branch is wrong; intermittency means the sensitivity default did not take.
 *
 * Do not enable this alongside *Swipe up to undo autocorrect*. Both attach to the same instruction
 * in `Lpvf;->t`. Morphe has no way to declare that two patches are mutually exclusive, so for a
 * while this paragraph was the only thing preventing it; the emitter's guard now refuses any prior
 * Flexboard emission at the anchor, so selecting both fails the patch instead of stacking two
 * guards. `tools/gate` asserts that failure, because a guard nobody tests is a guard that regresses
 * to a comment.
 */
@Suppress("unused")
val undoAutocorrectDiagnosticPatch = bytecodePatch(
    name = "Swipe up diagnostic (temporary)",
    description = "Diagnostic build only. Swipe up on a key to type a marker character, which " +
        "proves whether the swipe gesture is being detected at all without eating any text. " +
        "Cannot be used alongside \"Swipe up to undo autocorrect\" — they attach to the same " +
        "instruction, and selecting both fails the patch rather than giving you both effects. Off " +
        "by default, and this patch will be removed once it has answered its question.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // **Round two.** Round one kept all three guards and nothing happened, which proves the
        // chain fails before dispatch but not where. This drops everything except the direction
        // test, so it answers the one question the rest depend on: does `Lpvi;->h` ever come back
        // SLIDE_UP for a flick on an ordinary key?
        //
        // A marker types -> the direction works, and the fault is the null-ActionDef guard or
        // the corridor; those come back one at a time.
        // Nothing happens -> the direction is never SLIDE_UP, and the cause is upstream of
        // anything this patch controls: `Lpvi;->M()`, `Lpvj;->r()`, the `Lpvi;->t` branch, or the
        // per-key slide threshold in `Lpvf;->e`..`i`.
        emitUndoAutocorrectOnUpFlick(
            requireCorridor = false,
            requireUnclaimedKey = false,
            probe = GESTURE_PROBE,
        )
    }
}
