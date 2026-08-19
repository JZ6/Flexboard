package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.ApplyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.callAtAppStart

/**
 * Two of Gboard's own preferences decide whether the widened gesture works at all.
 *
 * `enable_scrub_delete` gates the handler *declaratively* — the keyboard layout attaches
 * `ScrubDeleteMotionEventHandler` only when it is true:
 *
 * ```xml
 * <motion_event_handler class=".motioneventhandler.scrubmove.ScrubDeleteMotionEventHandler"
 *                       preference_key="@0x7f140a1f" reverse_preference="false"/>
 * ```
 *
 * So with Gboard's "Delete swipe" turned off, the patch does nothing whatsoever and looks broken.
 *
 * `enable_gesture_input` is glide typing. A leftward drag across the letters is also a glide
 * input, so the two cannot both be live.
 *
 * Both are written at application start, from `LatinApp.applyPreferenceValues` — reached from
 * `Llzd;->onCreate()V`, which `LatinApp` overrides. That is the earliest point that has the
 * preference store in hand, and crucially it is before any keyboard is built, so the scrub
 * preference is already true by the time handlers are attached.
 *
 * Writing the setting rather than intercepting the read is deliberate. An earlier version of this
 * project hooked `Lqhy;->at(I)Z` and it failed on device: every consumer caches the value and the
 * caches only refresh when the keyboard is shown, so glide typing kept coming back. See
 * `docs/glide-detection.md`.
 *
 * ## Both writes are unconditional, and there is no restore
 *
 * These used to be gated on a `flexboard_enabled` preference, so that switching Flexboard off
 * stopped it re-forcing glide typing off at every launch and let the user tick glide back on. That
 * switch is gone — see [swipeToDeletePatch] for why — so the writes now happen on every start for
 * as long as the patch is applied, and glide typing cannot be ticked back on while it is. Getting
 * glide typing back means re-patching without Swipe to Delete.
 *
 * That is the trade the removal made, and it is worth being explicit that it is user-visible rather
 * than purely internal. What it buys is one fewer preference read, which is one fewer place needing
 * registers proved dead against each Gboard build.
 *
 * [glideTypingRowPatch] greys the two affected rows out so this is visible in the settings rather
 * than presenting as a switch that will not stay where it is put.
 */
internal val forceScrubPreferencesPatch = bytecodePatch(
    description = "Forces Gboard's scrub delete preference on and glide typing off at app start, " +
        "because the widened gesture needs the first and conflicts with the second."
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        ApplyPreferenceValuesFingerprint.method.callAtAppStart(FORCE_SCRUB_PREFERENCES)
    }
}

private const val GBOARD_SETTINGS = "Ldev/jz6/flexboard/extension/prefs/GboardSettings;"

private const val FORCE_SCRUB_PREFERENCES =
    "$GBOARD_SETTINGS->forceScrubPreferences(Landroid/content/Context;)V"

