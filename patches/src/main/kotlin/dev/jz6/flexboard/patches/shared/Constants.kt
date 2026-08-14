package dev.jz6.flexboard.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal object Constants {
    const val GBOARD_PACKAGE_NAME = "com.google.android.inputmethod.latin"

    /** Renaming to this is what lets a patched build sit alongside the official Gboard. */
    const val GBOARD_PATCHED_PACKAGE_NAME = "dev.jz6.com.google.android.inputmethod.latin"

    /**
     * Flexboard's settings screen: declared in the manifest and launched from Gboard's settings by
     * the settings patch, carried in the extension DEX, and re-targeted by the rename patch.
     * Shared because those three have to agree on it exactly.
     */
    const val SETTINGS_ACTIVITY_CLASS =
        "dev.jz6.flexboard.extension.settings.FlexboardSettingsActivity"

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
                version = "17.7.7.932364120-release-arm64-v8a",
                isExperimental = false
            )
        )
    )
}
