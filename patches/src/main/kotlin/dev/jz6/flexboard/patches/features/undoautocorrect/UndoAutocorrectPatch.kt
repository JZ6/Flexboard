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
 *  2. take the gesture over, so the swiped key is not typed — "6" if the takeover took, "x" if it
 *     was refused;
 *  3. **revert the last autocorrection** — the current stage. Stages 3 and 4 of the original plan
 *     ("send an undo", then "only when an autocorrection is armed") collapse into one: the swipe
 *     asks Gboard's decoder for its own autocorrect revert, and the decoder is the armed check.
 *
 * Two emissions: SwipeUpEmitter.kt takes the gesture over and sends the request, and
 * RevertEmitter.kt teaches `LatinIme->q` to hand it to the decoder. See SwipeUp.java and
 * docs/undo-autocorrect-plan.md.
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
    description = "Swipe up on the keyboard to undo the last autocorrection: the word you typed " +
        "comes back, as with Gboard's own undo autocorrect on backspace. Gboard's decoder decides " +
        "whether there is one to undo; if not, the swipe does nothing. The key you swiped on is " +
        "not typed. Not yet confirmed on a device. While it is on, a keyboard crash is saved and " +
        "copied to your clipboard the next time the keyboard starts, so it can be pasted into a " +
        "bug report. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        // The receiving end first: if it cannot apply, nothing has been changed yet. A request with
        // no receiver would be harmless anyway, since no stock code acts on -10076 as an event.
        routeRevertsToTheDecoder()
        emitSwipeUp()
        applyPreferenceValuesFingerprint().method.callAtAppStart(CRASH_RECORDER_INSTALL)
    }
}

private const val CRASH_RECORDER_INSTALL =
    "Ldev/jz6/flexboard/extension/diagnostic/CrashRecorder;->install(Landroid/content/Context;)V"
