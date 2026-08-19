package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.features.settings.scrubSettingsScreenPatch
import dev.jz6.flexboard.patches.features.swipetodelete.seedDefaultsPatch
import dev.jz6.flexboard.patches.features.settings.suggestedSettingsPatch
import dev.jz6.flexboard.patches.shared.ApplyPreferenceValuesFingerprint
import dev.jz6.flexboard.patches.shared.callAtAppStart

/**
 * The foundation every Flexboard patch needs.
 *
 * Merges the extension DEX, seeds the default preferences (Flexboard's own and the toolbar icon
 * count), and adds the Flexboard entry to Gboard's settings screen. Every public patch depends on
 * this, so a user cannot select a feature without the foundation it rests on — the extension is
 * always merged, the defaults are always seeded, and the settings row is always there.
 *
 * Internal so it never appears in Morphe's patch list. It runs as a dependency of every public
 * patch and is always selected when any of them is.
 */
internal val basePatch = bytecodePatch(
    description = "Merges the extension, seeds default preferences, and adds the Flexboard " +
        "settings entry to Gboard.",
) {
    extendWith("extensions/extension.mpe")

    dependsOn(scrubSettingsScreenPatch)
    dependsOn(seedDefaultsPatch)

    execute {
        ApplyPreferenceValuesFingerprint.method.callAtAppStart(SEED_TOOLBAR_COUNT)
    }
}

private const val GBOARD_SETTINGS = "Ldev/jz6/flexboard/extension/prefs/GboardSettings;"

private const val SEED_TOOLBAR_COUNT =
    "$GBOARD_SETTINGS->seedToolbarCount(Landroid/content/Context;)V"
