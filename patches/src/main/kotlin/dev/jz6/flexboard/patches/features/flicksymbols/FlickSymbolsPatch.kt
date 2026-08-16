package dev.jz6.flexboard.patches.features.flicksymbols

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import dev.jz6.flexboard.patches.features.scrubdelete.ApplyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.features.scrubdelete.PreferenceStoreWriteFingerprint
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD

/**
 * Turns on Gboard's own **"Flick keys to enter symbols"** — pull down on a key to enter the symbol
 * hinted in its corner.
 *
 * Gboard already implements this and ships it off. The setting is
 * `pref_enable_flick_symbols` (`0x7f140a01`), and two places read it at runtime:
 *
 * ```
 * LatinGestureMotionEventHandler->d():  Lcdl;->x(0x7f140a01, false)Z  → this.l:Z
 * Lpvf;->fV(Lqhy;String):               key 0x7f140a01
 * ```
 *
 * On 17.7.7 the first of those went through `Lpnp;->at(I)Z`, a one-line forward to the store's
 * superclass. Gboard 18 dropped the forwarder and calls the superclass directly; the preference
 * read is the same one either way.
 *
 * Neither consults anything else, so writing that one preference is the whole feature.
 *
 * ## Set once, not forced
 *
 * Unlike `forceScrubPreferencesPatch`, which rewrites its preferences on every start because the
 * swipe gesture cannot work otherwise, this one writes only when the key has never been set —
 * `Lqhy;->ak(I)Z` is `SharedPreferences.contains` keyed by resource id. So it behaves as a genuine
 * default: on out of the box, and it stays off if the user turns it off.
 *
 * ## The greyed row
 *
 * In `res/xml/setting_preferences.xml` the row carries `dependency="enable_secondary_digits"`, and
 * androidx disables a preference whose dependency is unchecked. So with "Touch & hold keys for
 * numbers" off — its own default — the row renders **checked but greyed**: the feature is on and
 * working, but it cannot be toggled from there until that other setting is enabled.
 *
 * That dependency is deliberately not written here. It is a UI relationship only — neither runtime
 * consumer above looks at it — and enabling "Touch & hold keys for numbers" would change long-press
 * behaviour that nobody asked this patch to change. Turning it on in Gboard's settings is what
 * un-greys the row.
 */
@Suppress("unused")
val flickSymbolsPatch = bytecodePatch(
    name = "Flick Keys for Symbols",
    description = "Turn on Gboard's \"Flick keys to enter symbols\" — pull down on a key to " +
        "enter the symbol hinted in its corner. Written once as a default, so it can still be " +
        "turned off in Gboard's own settings.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute {
        // Resolving the setter is the assertion that it still exists. An emitted invoke to a
        // missing method assembles happily and only fails on the device, with no diagnostic.
        val setter = PreferenceStoreWriteFingerprint.method
        val setterDescriptor = "${setter.definingClass}->${setter.name}(ILjava/lang/Object;)V"

        ApplyPreferenceValuesFingerprint.method.defaultFlickSymbolsOn(setterDescriptor)
    }
}

/**
 * Resource ids are build-specific and nothing at patch time can confirm one still means what it
 * meant. `COMPATIBILITY_GBOARD` pinning the bundle to a single Gboard build is what makes this safe
 * to hardcode; it was resolved from the resource table with `tools/apk/arsc.py`, and its label
 * (`0x7f140c14`) reads "Flick keys to enter symbols".
 */
private const val FLICK_SYMBOLS_PREFERENCE = "0x7f140a01"

/** `SharedPreferences.contains`, keyed by resource id rather than by the resolved string. */
private const val PREFERENCE_CONTAINS = "Lqhy;->ak(I)Z"

private const val APPLY_PREFERENCES_REGISTER_COUNT = 13

private const val ALREADY_SET_LABEL = "flexboard_flick_symbols_already_set"

/**
 * Injected at the head of `LatinApp.applyPreferenceValues`, which runs at Application start with
 * the preference store as its parameter — before any keyboard is built, so the value is in place by
 * the time `LatinGestureMotionEventHandler` reads it.
 *
 * v0..v2 are dead at method entry: the stock body writes v0 at offset 0, v1 at 14 and v2 at 16,
 * each before any read. So the branch leaving v1 an int on one edge and a `Boolean` on the other is
 * harmless — nothing reads it before it is overwritten.
 *
 * The store is copied out of its parameter register with `move-object/from16`, whose 16-bit source
 * field can address it wherever it lands; an `invoke` could not, and emitting `pN` into one is what
 * produced an unappliable bundle once before. See `docs/register-encoding.md`.
 */
private fun MutableMethod.defaultFlickSymbolsOn(setterDescriptor: String) {
    val registerCount = implementation?.registerCount
        ?: error("LatinApp->d(Lqhy;)V has no implementation")
    check(registerCount == APPLY_PREFERENCES_REGISTER_COUNT) {
        "LatinApp->d(Lqhy;)V has $registerCount registers, " +
            "expected $APPLY_PREFERENCES_REGISTER_COUNT — refusing to guess register mapping"
    }
    check(parameterTypes.map(Any::toString) == listOf("Lqhy;")) {
        "LatinApp->d takes $parameterTypes, expected a single Lqhy;"
    }

    val resume = instructions.first()

    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v2, p1
            const v0, $FLICK_SYMBOLS_PREFERENCE
            invoke-virtual { v2, v0 }, $PREFERENCE_CONTAINS
            move-result v1
            if-nez v1, :$ALREADY_SET_LABEL
            const/4 v1, 0x1
            invoke-static { v1 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v1
            invoke-virtual { v2, v0, v1 }, $setterDescriptor
        """,
        ExternalLabel(ALREADY_SET_LABEL, resume),
    )
}
