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
 * it runs through one convergence point: `Lphn;->b(Context)I`, which the settings fragment and the
 * key-release dispatch both call. A second gate — `Lpho;->n()Z` — suppresses the vibrator path on
 * modern Pixels even when the slider is shown.
 *
 * Both patches replace a method body with a constant return: no control-flow edits, no flag
 * chasing, no settings rows to add. The stock slider and toggle are already in the preference
 * XML; the point is making them survive the fragment setup and making the value reach the
 * vibrator.
 *
 * See `docs/vibration.md` for the full trace.
 */

/**
 * Forces the mode to 1 — "Gboard owns vibration" — on every device.
 *
 * The settings fragment takes the mode-1 branch: removes the gear row, keeps the toggle + slider,
 * renames the toggle to "Keyboard vibration". The key-release dispatch also sees mode 1, which is
 * what lets `Lpho;->f(I)V` (the `Vibrator.vibrate` call) run.
 */
internal val forceVibrationModePatch = bytecodePatch(
    description = "Forces Gboard to show its own vibration strength slider on every device, " +
        "rather than deferring to the system haptic settings page.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val method = VibrationModeFingerprint.method
        // `const/4 v0, 0x1` / `return v0` — v0 is the first local register, always valid for a
        // method with `regs >= 1`. The rest of the method becomes unreachable, which the verifier
        // accepts because no branch targets reach into it from outside.
        method.replaceInstruction(0, "const/4 v0, 0x1")
        method.replaceInstruction(1, "return v0")
    }
}

/**
 * Clears the suppression gate so the key-release vibrator path runs on every device.
 *
 * Without this, mode 1 on a Pixel (SDK ≥ 33, `d:Z` true) still skips the vibrator — the slider
 * would appear but dragging it would do nothing. `n()Z` returning false lets `d()` reach
 * `f(duration)` and the `Vibrator.vibrate` call underneath.
 */
internal val forceVibratorPathPatch = bytecodePatch(
    description = "Clears Gboard's internal suppression gate so the vibration duration slider " +
        "actually reaches the vibrator on every device.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val method = VibrationSuppressionFingerprint.method
        method.replaceInstruction(0, "const/4 v0, 0x0")
        method.replaceInstruction(1, "return v0")
    }
}
