package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Android's `KEYCODE_DEL`, and what Gboard's own edit tracker tests for on the backspace path
 * (`Lnur;->a() == 67`). Dispatching it through the same sink produces an ordinary delete, which is
 * the point: it is impossible to mistake for nothing happening.
 */
private const val KEYCODE_DEL = 67

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
 * So this is the same patch with one operand changed: identical anchor, identical guards, and a
 * backspace where the revert keycode was. Install it instead of the real patch and swipe up over a
 * letter.
 *
 * **A character disappears** — the whole chain works: gesture, guards, event construction and
 * dispatch. The fault is downstream, in the revert being unarmed, and the fix is the one-slot
 * capture-and-restore rather than anything about the gesture.
 *
 * **Nothing happens** — the chain fails before dispatch. The next build drops the corridor test
 * (`requireCorridor = false`, one line below) to separate "the flick is never recognised" from "the
 * corridor rejects it".
 *
 * Do not enable this alongside *Swipe up to undo autocorrect*. Both attach to the same instruction
 * in `Lpvf;->t`, and selecting both emits two guards at one anchor: a swipe would delete a
 * character *and* dispatch a revert. Morphe has no way to declare that two patches are mutually
 * exclusive, so this paragraph is the only thing preventing it.
 */
@Suppress("unused")
val undoAutocorrectDiagnosticPatch = bytecodePatch(
    name = "Swipe up diagnostic (temporary)",
    description = "Diagnostic build only. Swipe up on a key to delete a character, which proves " +
        "whether the swipe gesture is being detected at all. Do not enable this at the same time " +
        "as \"Swipe up to undo autocorrect\" — they attach to the same place and you would get " +
        "both effects. Off by default, and this patch will be removed once it has answered its " +
        "question.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // Same guards as the real emission on purpose. Changing two things at once would leave a
        // negative result meaning nothing.
        emitUndoAutocorrectOnUpFlick(keycode = KEYCODE_DEL, requireCorridor = true)
    }
}
