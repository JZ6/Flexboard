package dev.jz6.flexboard.patches.features.vibration

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Makes Gboard's own vibration strength slider appear and work on every device.
 *
 * On stock Android (Pixels), Gboard strips the slider and the "Vibrate on keypress" toggle from
 * its settings screen and defers to the system haptic settings page — the "Keyboard vibration"
 * gear link is all that survives. On Samsung (and other OEMs that don't answer the haptic-settings
 * Intent Gboard probes for), Gboard keeps its own slider and the value flows to the vibrator.
 *
 * The split is driven by server-side Phenotype flags rolled per-device, not by a code defect, and
 * it runs through two gates, both converging on constant-return edits:
 *
 *  1. **`Lphn;->b(Context)I`** — the settings fragment and the key-release dispatch both call
 *     this. Its return decides which rows survive on the settings screen and whether the
 *     vibration effect reaches the actual vibrator. Forcing it to **1** ("Gboard owns
 *     vibration") makes the fragment keep the slider strip and the gear row disappears.
 *
 *  2. **`Lpho;->n()Z`** — a suppression gate in the key-release path. Even when the slider is
 *     shown, a mode-1 Pixel on SDK ≥ 33 with `d:Z` true skips `f(I)V` (the `Vibrator.vibrate`
 *     call). Forcing it to **false** clears the gate.
 *
 * Both write only the first two instructions of the method (`const/4 v0, …`; `return v0`) — the
 * rest of the body is unreachable by the time the verifier accepts it, which the patcher does
 * without complaint because no branch targets reach into the overwritten site from outside.
 *
 * See `docs/vibration.md` for the full trace.
 */
@Suppress("unused")
val vibrationSliderPatch = bytecodePatch(
    name = "Vibration slider everywhere",
    description = "Forces Gboard to show its own vibration strength slider on every device, " +
        "rather than deferring to the system haptic settings page. The slider actually reaches " +
        "the vibrator instead of being suppressed by a server-side rollout.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // ----- Gate 1: keep Gboard's own slider ----
        // `const/4 v0, 0x1` / `return v0` — v0 is the first local register, always valid for a
        // method declared with `regs >= 1`. The rest of the method body is unreachable from the
        // outside, which the verifier accepts because no branch targets reach into the tail.
        val mode = VibrationModeFingerprint.method
        mode.replaceInstruction(0, "const/4 v0, 0x1")
        mode.replaceInstruction(1, "return v0")

        // ----- Gate 2: stop suppressing the vibrator ----
        // `const/4 v0, 0x0` / `return v0` — same shape, opposite constant.
        val suppression = VibrationSuppressionFingerprint.method
        suppression.replaceInstruction(0, "const/4 v0, 0x0")
        suppression.replaceInstruction(1, "return v0")
    }
}
