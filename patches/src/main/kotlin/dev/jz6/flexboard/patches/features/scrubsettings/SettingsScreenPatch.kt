package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_PACKAGE_NAME
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_PATCHED_PACKAGE_NAME
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_SETTINGS_XML
import dev.jz6.flexboard.patches.shared.Constants.SETTINGS_ACTIVITY_CLASS
import dev.jz6.flexboard.patches.shared.androidAttribute
import dev.jz6.flexboard.patches.shared.childElements
import dev.jz6.flexboard.patches.shared.descendants
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.w3c.dom.Document

/**
 * Adds a **Flexboard** entry to Gboard's settings that opens Flexboard's own screen.
 *
 * ## Why an Activity and not a nested screen
 *
 * The first version of this patch appended a nested `<PreferenceScreen>` to
 * `res/xml/settings.xml`, on the theory that androidx renders one as a row that opens a sub-screen.
 * It shipped in `v0.1.0-dev.3` and the row rendered correctly but **did nothing when tapped**.
 *
 * androidx only navigates to a nested screen when the host implements
 * `OnPreferenceStartScreenCallback`:
 *
 * ```java
 * if (getCallbackFragment() instanceof OnPreferenceStartScreenCallback) { … }
 * if (!handled && getActivity() instanceof OnPreferenceStartScreenCallback) { … }
 * // no else — the tap is swallowed
 * ```
 *
 * Gboard's `CommonPreferenceFragment` declares no interfaces and `SettingsActivity` declares none,
 * so the tap went nowhere. Giving a screen its own fragment is no better: Gboard's fragments choose
 * their XML by overriding `CommonPreferenceFragment.aB()I`, so ours would have to subclass a Gboard
 * type, which an extension cannot do without stubbing it.
 *
 * So the entry launches an Activity carried in the extension DEX — the route v0.3 proved works.
 *
 * ## The row names a component, not an action
 *
 * `v0.1.0-dev.4` launched the Activity with an **action**, on the reasoning that an implicit intent
 * resolves in whatever package the rename patch produces and so cannot depend on which `finalize`
 * block ran first. On device it failed with *no application found to handle the action*.
 *
 * Gboard 18.0.3 declares `targetSdkVersion="37"`, and since Android 14 an app targeting 34+ may
 * only deliver an implicit intent to an **exported** component — including to its own components.
 * The intent filter written beside the Activity therefore matched nothing, `startActivity` found no
 * candidate, and the tap raised `ActivityNotFoundException`.
 *
 * So the row names the component outright, which is what v0.3 did and why v0.3 worked. Both
 * `android:targetPackage` and `android:targetClass` are required: `Intent.parseIntent` only calls
 * `setComponent` when it has the pair. An explicit same-app intent reaches a non-exported Activity
 * regardless of Android version, so `exported` stays `false` and the intent filter is gone.
 *
 * Naming the package brings back the ordering problem the action was meant to avoid, and it is
 * solved the way v0.3 solved it: this patch writes whatever the manifest currently says, and the
 * rename patch re-targets the row if it renames afterwards. See `retargetFlexboardSettings` in
 * `PackageRenamePatch.kt` — between them the two orders converge on the same result, and unticking
 * the rename patch leaves the row pointing at the un-renamed package, which is then correct.
 *
 * Both files this touches keep their real names through `aapt2 --collapse-resource-names` — 33 of
 * Gboard's 33,287 entries do, and the settings screens are among them. That is the only reason this
 * patch can address them; see the addressability note in `docs/development.md`.
 */
internal val scrubSettingsScreenPatch = resourcePatch(
    description = "Adds a Flexboard entry to Gboard's settings that opens Flexboard's own screen.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        val packageName = document("AndroidManifest.xml").use { manifest ->
            manifest.registerSettingsActivity()
            manifest.documentElement.getAttribute("package")
        }
        // The rename patch may not have run yet, so both values are legitimate here; anything else
        // means the manifest is not the one this patch was written against.
        check(packageName == GBOARD_PACKAGE_NAME || packageName == GBOARD_PATCHED_PACKAGE_NAME) {
            "Unexpected manifest package '$packageName' — cannot target the settings Activity"
        }

        document(GBOARD_SETTINGS_XML).use { settings ->
            settings.addFlexboardEntry(packageName)
        }
    }
}

private const val PREFERENCE_SCREEN_TAG = "PreferenceScreen"
private const val PREFERENCE_CATEGORY_TAG = "androidx.preference.PreferenceCategory"
private const val FOOTER_PREFERENCE_TAG = "com.android.settingslib.widget.FooterPreference"
private const val RATE_US_PREFERENCE_TAG =
    "com.google.android.libraries.inputmethod.rateus.RateUsPreference"
private const val PREFERENCE_TAG = "Preference"
private const val INTENT_TAG = "intent"

private const val ENTRY_KEY = "flexboard_settings"
private const val ENTRY_TITLE = "Flexboard"
private const val ENTRY_SUMMARY = "Swipe length, word limit and hold delay"

/** Ignored once the intent carries a component, but it is what v0.3 shipped. */
private const val ENTRY_ACTION = "android.intent.action.MAIN"

/**
 * The Activity draws its own heading, palette and window insets, so the theme is asked for as
 * little as possible.
 *
 * `Theme.DeviceDefault.Settings` came first and clipped the top row: Gboard targets SDK 37, so on
 * Android 15+ the window is edge-to-edge, and how much of the status bar inset a themed action bar
 * had already taken is not something a merged class can determine on every device. With no action
 * bar the Activity is the only inset consumer and the arithmetic is its own.
 */
private const val SETTINGS_THEME = "@android:style/Theme.DeviceDefault.NoActionBar"

private fun Document.registerSettingsActivity() {
    val application = documentElement.childElements("application").firstOrNull()
        ?: error("No <application> in AndroidManifest.xml")

    // Idempotent: applying a bundle over an already-patched APK must not declare it twice.
    if (application.childElements("activity")
            .any { it.androidAttribute("name") == SETTINGS_ACTIVITY_CLASS }
    ) {
        return
    }

    application.appendChild(
        createElement("activity").apply {
            setAndroidAttribute("name", SETTINGS_ACTIVITY_CLASS)
            // Reached only from Gboard's own settings, by explicit component, so nothing outside
            // the app needs to start it and no intent filter has to exist for it to resolve.
            setAndroidAttribute("exported", "false")
            setAndroidAttribute("label", ENTRY_TITLE)
            setAndroidAttribute("theme", SETTINGS_THEME)
        },
    )
}

private fun Document.addFlexboardEntry(packageName: String) {
    val root = documentElement
    check(root.tagName == PREFERENCE_SCREEN_TAG) {
        "$GBOARD_SETTINGS_XML has root <${root.tagName}>, expected <$PREFERENCE_SCREEN_TAG> — " +
            "Gboard's settings are no longer the androidx screen this patch appends to"
    }

    if (root.descendants().any { it.androidAttribute("key") == ENTRY_KEY }) return

    val entry = createElement(PREFERENCE_TAG).apply {
        setAndroidAttribute("key", ENTRY_KEY)
        setAndroidAttribute("title", ENTRY_TITLE)
        setAndroidAttribute("summary", ENTRY_SUMMARY)
        // Nothing to store: the row is a launcher, and the Activity owns the values.
        setAndroidAttribute("persistent", "false")
        // Borrowed so the row does not render iconless beside Gboard's own, which all carry one.
        root.descendants()
            .firstOrNull { it.tagName == RATE_US_PREFERENCE_TAG }
            ?.androidAttribute("icon")
            ?.let { setAndroidAttribute("icon", it) }
    }
    entry.appendChild(
        createElement(INTENT_TAG).apply {
            setAndroidAttribute("action", ENTRY_ACTION)
            setAndroidAttribute("targetPackage", packageName)
            setAndroidAttribute("targetClass", SETTINGS_ACTIVITY_CLASS)
        },
    )

    // Placement matters: appending to the root would land the row *after* the footer, which reads
    // as a stray control rather than a settings entry.
    val category = root.childElements(PREFERENCE_CATEGORY_TAG).lastOrNull()
    val footer = root.childElements(FOOTER_PREFERENCE_TAG).firstOrNull()
    when {
        category != null -> category.appendChild(entry)
        footer != null -> root.insertBefore(entry, footer)
        else -> root.appendChild(entry)
    }
}
