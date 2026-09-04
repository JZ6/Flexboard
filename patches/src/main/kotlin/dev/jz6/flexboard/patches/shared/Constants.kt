package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val GBOARD_PACKAGE_NAME = "com.google.android.inputmethod.latin"

    /**
     * The project's own URL, shown in the settings screen's About section and opened when the
     * Source row is tapped. Declared here *and* in `patches/build.gradle.kts`'s `about { }` block,
     * which is what Morphe Manager shows; check_shared_constants holds the two together, because
     * a settings screen pointing somewhere the bundle metadata does not is worse than either
     * alone.
     */
    const val SOURCE_URL = "https://github.com/JZ6/Flexboard"

    /** The same link without its scheme. Shown, not followed -- a summary row is not a browser. */
    const val SOURCE_URL_SHORT = "github.com/JZ6/Flexboard"

    /** Renaming to this is what lets a patched build sit alongside the official Gboard. */
    const val GBOARD_PATCHED_PACKAGE_NAME = "dev.jz6.com.google.android.inputmethod.latin"

    /**
     * Flexboard's settings screen: the fragment class carried in the extension DEX, named by the
     * settings row's `android:fragment` attribute. Gboard's settings host instantiates it by
     * class-name lookup, so the name is the whole registration — no manifest entry, no router.
     * It names no package, so the rename patch has nothing to re-target.
     *
     * Checked by `check_shared_constants.py`: the class name carries a dotted extension reference,
     * and that lane fails the build if the class it names is gone from the extension.
     */
    const val SETTINGS_FRAGMENT_CLASS =
        "dev.jz6.flexboard.extension.settings.FlexboardSettingsFragment"

    /** Gboard's top-level settings screen. One of the few resources that keeps its real name. */
    const val GBOARD_SETTINGS_XML = "res/xml/settings.xml"

    /**
     * Gboard's legacy top-level settings screen. `SettingsActivity.t()` inflates this one instead
     * of [GBOARD_SETTINGS_XML] whenever the "expressive design" gate is off — which it is on
     * `SDK_INT < 36` and on Android 16 devices whose OEM does not set `is_expressive_design_enabled`
     * (ColorOS/OxygenOS among them). The row must be added to both screens or it goes missing on
     * exactly those devices. Keeps its real name like the modern screen does.
     */
    const val GBOARD_SETTINGS_LEGACY_XML = "res/xml/settings_legacy.xml"

    /**
     * Signatures and target carried over verbatim from the build this was developed against.
     *
     * **This is metadata, not a gate.** `Patcher` never reads `compatiblePackages` — only the host
     * UI does — so it records which build the hooks were derived against and may warn a user, but
     * it does not stop the patches running against a different one. Anything that must not be
     * applied to the wrong build has to establish that itself, by matching on shape rather than on
     * an R8 letter. Justifications elsewhere in this bundle of the form "what makes this
     * positional match acceptable is the pin" are relying on a guarantee that does not exist.
     */
    val COMPATIBILITY_GBOARD = Compatibility(
        name = "Gboard",
        packageName = GBOARD_PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x1A73E8,
        signatures = setOf(
            "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053",
            "f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83"
        ),
        targets = listOf(
            AppTarget(
                version = "18.0.3.954559732-release-arm64-v8a",
                isExperimental = false
            )
        )
    )
}
