package dev.jz6.flexboard.patches.features.undoautocorrect

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.applyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callAtAppStart

/**
 * Swipe up on the keyboard to undo the last autocorrection.
 *
 * Built in stages from the diagnostic that measured the gesture, because the previous attempt —
 * which went straight to taking the gesture over and sending an undo — crashed the keyboard on a
 * swipe up, while the diagnostic it was built beside never did. One capability per release, so a
 * failure names its own cause:
 *
 *  1. detect, and type a single "6" — confirmed on a device;
 *  2. **take the gesture over, so the swiped key is not typed** — the current stage: "6" if the
 *     takeover took, "x" if it was refused;
 *  3. send an undo in place of the "6";
 *  4. undo only when an autocorrection is armed, as Gboard's own backspace revert does.
 *
 * It runs in the motion-event-handler layer, beside swipe left to delete and swipe right to undo.
 * See SwipeUpEmitter.kt, SwipeUp.java and docs/undo-autocorrect-plan.md.
 *
 * Replaces "Swipe up diagnostic (temporary)", whose measuring code this now is.
 *
 * **Also installs a crash recorder.** Stage 2 crashed the keyboard on a device with no logcat and no
 * adb, after which the cause could only be guessed at, and it was guessed at wrongly more than once.
 * With this patch on, an uncaught exception is saved and copied to the clipboard the next time the
 * keyboard starts. It is temporary, it is tied to this patch so only testers get it, and it is the
 * reason this patch mentions the clipboard: it overwrites it after a crash.
 */
@Suppress("unused")
val undoAutocorrectPatch = bytecodePatch(
    name = "Swipe up to undo autocorrect",
    description = "Work in progress, being built in stages. This build takes the swipe over: swipe " +
        "up on the keyboard and it types a 6 instead of the key you swiped on, or an x if it could " +
        "not take the swipe over. It does not undo anything yet. While it is on, a keyboard crash " +
        "is saved and copied to your clipboard the next time the keyboard starts, so it can be " +
        "pasted into a bug report. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        emitSwipeUp()
        applyPreferenceValuesFingerprint().method.callAtAppStart(CRASH_RECORDER_INSTALL)
    }
}

private const val CRASH_RECORDER_INSTALL =
    "Ldev/jz6/flexboard/extension/diagnostic/CrashRecorder;->install(Landroid/content/Context;)V"
