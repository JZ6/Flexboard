package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val GBOARD_PACKAGE_NAME = "com.google.android.inputmethod.latin"

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
     * Signatures and target carried over verbatim from the build this was developed against.
     * The hooks are pinned to exactly one Gboard build; anything else must fail to match rather
     * than patch something it does not understand.
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
