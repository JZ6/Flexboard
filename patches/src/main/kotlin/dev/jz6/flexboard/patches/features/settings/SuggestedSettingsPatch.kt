package dev.jz6.flexboard.patches.features.settings

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.applyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.callAtAppStart

/**
 * Gboard's suggested settings, written once as defaults on the first run after installing.
 *
 * Each of these is a Gboard preference Gboard already ships off or in a state that makes the
 * keyboard worse for a gesture-first user. Writing them once — guarded by `contains`, not forced —
 * means they behave as genuine defaults: on out of the box, and turning any of them off in
 * Gboard's own settings sticks.
 *
 * The settings:
 *
 *  - **Flick keys for symbols** on — pull down on a key to enter the symbol hinted in its corner.
 *  - **Touch & hold keys for numbers** on — un-greys the flick row, which carries
 *    `dependency="enable_secondary_digits"` in Gboard's preference XML.
 *  - **Block offensive words** off — stops Gboard from suppressing words it considers offensive.
 *  - **Word suggestions** off — removes word suggestions from the strip. The strip itself stays
 *    so feature buttons (grammar check, proofread, etc.) still render.
 *  - **Suggestion strip** on — the strip that carries both suggestions and feature buttons.
 *    Explicitly on so that turning word suggestions off does not take the strip with it.
 *  - **Grammar check** on — underlines grammatical errors in blue.
 *  - **Smart replies** on — shows reply suggestions in supported apps.
 *
 * ## Why a write and not a hook
 *
 * Writing the setting rather than intercepting the read is the pattern the whole project uses for
 * Gboard preferences, and the reasons are the same: a read hook needs a derived accessor on an
 * obfuscated class, and Gboard's own id-keyed `contains` shares its signature with a sibling that
 * answers *"is it currently true?"* rather than *"has the user ever set this?"*. A moved letter
 * would turn a default into something that forces itself back on every start.
 *
 * None of that is necessary. A Gboard preference key is a string resource's value, the file is an
 * ordinary `SharedPreferences`, and `SharedPreferences.contains` has no sibling. See
 * [GboardSettings][dev.jz6.flexboard.extension.prefs.GboardSettings].
 *
 * ## The resource ids stay pinned
 *
 * `COMPATIBILITY_GBOARD` ties the bundle to one Gboard build, and `tools/apk/preflight.py` checks
 * that each id still names the preference it is supposed to.
 */
@Suppress("unused")
val suggestedSettingsPatch = bytecodePatch(
    name = "Suggested Settings",
    description = "Turns on flick keys for symbols, touch & hold keys for numbers, suggestion " +
        "strip, grammar check and smart replies, and turns off block offensive words and word " +
        "suggestions. Written once as defaults, so each can still be changed in Gboard's own " +
        "settings. Grammar check is the switch, not the feature: the row only exists on a " +
        "resigned build if Hidden Features is applied too.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        applyPreferenceValuesFingerprint().method.callAtAppStart(DEFAULT_SUGGESTED_SETTINGS)
    }
}

private const val GBOARD_SETTINGS = "Ldev/jz6/flexboard/extension/prefs/GboardSettings;"

private const val DEFAULT_SUGGESTED_SETTINGS =
    "$GBOARD_SETTINGS->defaultSuggestedSettings(Landroid/content/Context;)V"
