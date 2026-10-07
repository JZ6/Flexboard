package dev.jz6.flexboard.patches.features.diagnostic

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.applyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callAtAppStart

/**
 * Records a keyboard crash and hands it back on the next start.
 *
 * Built while swipe up to undo autocorrect crashed the keyboard on a device with no logcat and no
 * adb, after which the cause could only be guessed at, and it was guessed at wrongly more than once.
 * It shipped inside that patch so only testers got it. When swipe up went on by default it moved
 * here, opt-in, so nobody gets it without asking.
 *
 * With this patch on, an uncaught exception is saved with a synchronous `commit()` and handed on to
 * Android's own handler, so crash handling is unchanged; the next time the keyboard starts the report
 * is copied to the clipboard, overwriting whatever was there, and forgotten. See `CrashRecorder.java`.
 */
@Suppress("unused")
val crashReporterPatch = bytecodePatch(
    name = "Crash reporter (debug)",
    description = "For debugging. After a keyboard crash, the error is saved and copied to your " +
        "clipboard the next time the keyboard starts, so it can be pasted into a bug report. This " +
        "replaces whatever was on the clipboard. Off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        applyPreferenceValuesFingerprint().method.callAtAppStart(CRASH_RECORDER_INSTALL)
    }
}

private const val CRASH_RECORDER_INSTALL =
    "Ldev/jz6/flexboard/extension/diagnostic/CrashRecorder;->install(Landroid/content/Context;)V"
