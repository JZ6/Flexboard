package dev.jz6.flexboard.patches.features.scrubdelete

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
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
 * ## Both writes are skipped when Flexboard is switched off
 *
 * There is still no restore: turning the switch off leaves glide typing off, to be ticked back on
 * in Gboard's own settings. Skipping the writes is what makes that possible rather than merely
 * documented — left unconditional, this would re-force glide typing off at every app start, so
 * ticking it back on would silently undo itself on the next launch.
 *
 * The `enable_scrub_delete` write is skipped for symmetry rather than necessity. It is Gboard's own
 * default and harmless to leave on, but a disabled Flexboard has no business writing preferences.
 */
internal val forceScrubPreferencesPatch = bytecodePatch(
    description = "Forces Gboard's scrub delete preference on and glide typing off at app start, " +
        "unless Flexboard's own switch is off."
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

private const val NOT_ENABLED_LABEL = "flexboard_not_enabled"

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

    // Captured before the insertion shifts indices; the label resolves by instruction identity.
    val stockResumes = instructions.first()

    // v0..v2 are dead at method entry — the stock body's first act is to load v0 with a string —
    // so they are free to claim. The store is copied out of its parameter register with
    // `move-object/from16`, whose 16-bit source field can address it wherever it lands; an
    // `invoke` could not, and emitting `pN` into one is what produced an unappliable bundle once
    // before. See docs/register-encoding.md.
    //
    // The branch leaves v0 and v1 holding different types on the two edges — an int against a
    // `Boolean` — which is harmless because the stock body writes both before reading either.
    // `flickSymbolsPatch` relies on exactly the same thing in this method.
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v2, p1

            const-string v0, "$SCRUB_ENABLED_KEY"
            const/4 v1, 0x1
            invoke-virtual {v2, v0, v1}, $PREFERENCE_GET_BOOLEAN
            move-result v0
            if-eqz v0, :$NOT_ENABLED_LABEL

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
        ExternalLabel(NOT_ENABLED_LABEL, stockResumes),
    )
}
