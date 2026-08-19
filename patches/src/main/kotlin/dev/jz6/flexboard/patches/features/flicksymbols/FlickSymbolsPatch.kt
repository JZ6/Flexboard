package dev.jz6.flexboard.patches.features.flicksymbols

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.ApplyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.callAtAppStart

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
 * swipe gesture cannot work otherwise, this one writes only when the key has never been set. So it
 * behaves as a genuine default: on out of the box, and it stays off if the user turns it off.
 *
 * That test used to be Gboard's own id-keyed `contains`, derived at patch time because its
 * signature is shared with a sibling answering *"is it currently true?"* rather than *"has the user
 * ever set this?"* — a moved letter would have turned this into something that forced flick keys
 * back on at every start. It is now `SharedPreferences.contains` in the extension, which has no
 * sibling. See [GboardSettings][dev.jz6.flexboard.extension.prefs.GboardSettings].
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

    // Carries GboardSettings, which does the write.
    extendWith("extensions/extension.mpe")

    execute {
        ApplyPreferenceValuesFingerprint.method.callAtAppStart(DEFAULT_FLICK_SYMBOLS_ON)
    }
}

private const val GBOARD_SETTINGS = "Ldev/jz6/flexboard/extension/prefs/GboardSettings;"

private const val DEFAULT_FLICK_SYMBOLS_ON =
    "$GBOARD_SETTINGS->defaultFlickSymbolsOn(Landroid/content/Context;)V"

