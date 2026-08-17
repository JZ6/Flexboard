package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD

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
        // Resolving the setter is the assertion that it still exists. An emitted invoke to a
        // missing method assembles happily and only fails on the device, with no diagnostic.
        val setter = PreferenceStoreWriteFingerprint.method
        val setterDescriptor =
            "${setter.definingClass}->${setter.name}(ILjava/lang/Object;)V"

        ApplyPreferenceValuesFingerprint.method.forcePreferences(setterDescriptor)
    }
}

/**
 * `Lqhy;->T` resolves the resource id to a preference key, dispatches on the boxed type and
 * commits with `Editor.apply()`.
 */
object PreferenceStoreWriteFingerprint : Fingerprint(
    definingClass = "Lqhy;",
    name = "T",
    parameters = listOf("I", "Ljava/lang/Object;"),
    returnType = "V",
)

/**
 * `LatinApp.applyPreferenceValues`. Takes the preference store as its only parameter, so no
 * lookup, no reflection and no extension are needed to reach it.
 */
object ApplyPreferenceValuesFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/inputmethod/latin/LatinApp;",
    name = "d",
    parameters = listOf("Lqhy;"),
    returnType = "V",
)

/**
 * Resource ids are build-specific, and nothing at patch time can confirm one still means what it
 * meant. `COMPATIBILITY_GBOARD` pinning the bundle to a single Gboard build is what makes them
 * safe to hardcode; both were resolved from the resource table with `tools/apk/arsc.py`.
 */
private const val SCRUB_DELETE_PREFERENCE = "0x7f140a1f" // enable_scrub_delete
private const val GLIDE_TYPING_PREFERENCE = "0x7f140a05" // enable_gesture_input

private const val APPLY_PREFERENCES_REGISTER_COUNT = 13

private fun MutableMethod.forcePreferences(setterDescriptor: String) {
    val registerCount = implementation?.registerCount
        ?: error("LatinApp->d(Lqhy;)V has no implementation")
    check(registerCount == APPLY_PREFERENCES_REGISTER_COUNT) {
        "LatinApp->d(Lqhy;)V has $registerCount registers, " +
            "expected $APPLY_PREFERENCES_REGISTER_COUNT — refusing to guess register mapping"
    }
    check(parameterTypes.map(Any::toString) == listOf("Lqhy;")) {
        "LatinApp->d takes $parameterTypes, expected a single Lqhy;"
    }

    // v0..v2 are dead at method entry — the stock body's first act is to load v0 with a string —
    // so they are free to claim. The store is copied out of its parameter register with
    // `move-object/from16`, whose 16-bit source field can address it wherever it lands; an
    // `invoke` could not, and emitting `pN` into one is what produced an unappliable bundle once
    // before. See docs/register-encoding.md.
    //
    // Straight-line now that the preference gate is gone, so there is no longer a branch merging
    // two different types into v0 and v1. `flickSymbolsPatch` still inserts into this method and
    // still relies on v0..v2 being dead at entry, which this leaves true: everything below writes
    // each register before reading it.
    addInstructions(
        0,
        """
            move-object/from16 v2, p1

            const v0, $SCRUB_DELETE_PREFERENCE
            const/4 v1, 0x1
            invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v1
            invoke-virtual {v2, v0, v1}, $setterDescriptor

            const v0, $GLIDE_TYPING_PREFERENCE
            const/4 v1, 0x0
            invoke-static {v1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v1
            invoke-virtual {v2, v0, v1}, $setterDescriptor
        """,
    )
}
