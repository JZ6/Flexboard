package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
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
 * ## The row does not name a package
 *
 * `<intent>` is given an **action** rather than `targetPackage`/`targetClass`. The package-rename
 * patch changes the application id, so anything naming it here would depend on which of the two
 * `finalize` blocks ran first. An action resolves against the intent filter written below, in
 * whatever package the app ends up as, and same-app implicit intents reach a non-exported Activity
 * fine.
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
        document("AndroidManifest.xml").use { manifest ->
            manifest.registerSettingsActivity()
        }
        document(SETTINGS_XML).use { settings ->
            settings.addFlexboardEntry()
        }
    }
}

/** Gboard's top-level settings screen. One of the few resources that keeps its name. */
private const val SETTINGS_XML = "res/xml/settings.xml"

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

/** Carried in the extension DEX, merged by `scrubTuningPatch`. */
private const val SETTINGS_ACTIVITY =
    "dev.jz6.flexboard.extension.settings.FlexboardSettingsActivity"

/** Unique to this project, so the implicit intent can only resolve to the Activity below. */
private const val SETTINGS_ACTION = "dev.jz6.flexboard.action.SETTINGS"

/** Follows the system light/dark setting without the Activity hardcoding a palette. */
private const val SETTINGS_THEME = "@android:style/Theme.DeviceDefault.Settings"

private fun Document.registerSettingsActivity() {
    val application = documentElement.childElements("application").firstOrNull()
        ?: error("No <application> in AndroidManifest.xml")

    // Idempotent: applying a bundle over an already-patched APK must not declare it twice.
    if (application.childElements("activity")
            .any { it.androidAttribute("name") == SETTINGS_ACTIVITY }
    ) {
        return
    }

    val activity = createElement("activity").apply {
        setAndroidAttribute("name", SETTINGS_ACTIVITY)
        // Reached only from Gboard's own settings, so nothing outside the app needs to start it.
        setAndroidAttribute("exported", "false")
        setAndroidAttribute("label", ENTRY_TITLE)
        setAndroidAttribute("theme", SETTINGS_THEME)
    }

    val filter = createElement("intent-filter").apply {
        appendChild(createElement("action").apply { setAndroidAttribute("name", SETTINGS_ACTION) })
        appendChild(
            createElement("category").apply {
                setAndroidAttribute("name", "android.intent.category.DEFAULT")
            },
        )
    }
    activity.appendChild(filter)
    application.appendChild(activity)
}

private fun Document.addFlexboardEntry() {
    val root = documentElement
    check(root.tagName == PREFERENCE_SCREEN_TAG) {
        "$SETTINGS_XML has root <${root.tagName}>, expected <$PREFERENCE_SCREEN_TAG> — Gboard's " +
            "settings are no longer the androidx screen this patch appends to"
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
        createElement(INTENT_TAG).apply { setAndroidAttribute("action", SETTINGS_ACTION) },
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
