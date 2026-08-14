package dev.jz6.flexboard.patches.features.scrubsettings

import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.androidAttribute
import dev.jz6.flexboard.patches.shared.childElements
import dev.jz6.flexboard.patches.shared.descendants
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Adds a **Flexboard** screen to Gboard's settings.
 *
 * Gboard's settings are plain androidx. `res/xml/settings.xml` is a `PreferenceScreen` of
 * `androidx.preference.PreferenceCategory` groups, and `res/xml/setting_gesture.xml` — where
 * Gboard's own "Glide delete" switch lives — is nothing more exotic than `SwitchPreferenceCompat`
 * rows:
 *
 * ```xml
 * <SwitchPreferenceCompat persistent="true" key="@string/enable_scrub_delete"
 *                         title="…Glide delete" summary="…"/>
 * ```
 *
 * Both files keep their real names through `aapt2 --collapse-resource-names` — 33 of Gboard's
 * 33,287 resource entries do, and the settings screens are among them. That is the only reason this
 * patch can address them at all; see the addressability note in `docs/development.md`.
 *
 * **Keys and titles are literals, not resource references.** A newly added string resource has no
 * id until aapt2 recompiles, long after the bytecode patch that reads these values has run.
 * Literals sidestep the id problem on both sides, at the cost of no translations for now.
 *
 * ## The one thing that might not work
 *
 * The nested `<PreferenceScreen>` is what makes this a separate screen rather than two rows bolted
 * onto an existing one. androidx renders a nested screen as a row that opens a sub-screen — but
 * only when the host implements `OnPreferenceStartScreenCallback`. Gboard's
 * `CommonPreferenceFragment` declares no interfaces, `SettingsActivity` declares none, and the only
 * `PreferenceScreen`-taking method left on the obfuscated base `Ldgh;` is
 * `az(Landroidx/preference/PreferenceScreen;)V`, i.e. `setPreferenceScreen`. So the navigation may
 * well do nothing when tapped.
 *
 * That is deliberately being found out cheaply here rather than paid for up front. The alternative
 * is our own Activity in an extension DEX, which is what v0.3 did and what this rebuild exists to
 * avoid. **If the row does not open, this file is the only thing that changes** — the bytecode half
 * reads preferences by literal key and does not care what wrote them.
 */
internal val scrubSettingsScreenPatch = resourcePatch(
    description = "Adds a Flexboard screen to Gboard's settings with the swipe tunables.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        document(SETTINGS_XML).use { settings ->
            settings.addFlexboardScreen()
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
private const val SEEK_BAR_PREFERENCE_TAG = "SeekBarPreference"

private const val SCREEN_KEY = "flexboard_settings"
private const val SCREEN_TITLE = "Flexboard"

/**
 * Percent of Gboard's own step distances. Below 100 deletes more words for the same travel; above
 * 100 makes each word a longer swipe. Bounded well away from zero — a collapsed step table would
 * make every pixel of movement another word.
 */
private const val STEP_SCALE_MIN = 25
private const val STEP_SCALE_MAX = 300

/**
 * Milliseconds the gesture must be held before it may activate. Gboard ships 200 for the backspace
 * scrub and 50 for the inline-suggestion one; 0 is what makes a flick register.
 */
private const val HOLD_DELAY_MIN = 0
private const val HOLD_DELAY_MAX = 300

private fun Document.addFlexboardScreen() {
    val root = documentElement
    check(root.tagName == PREFERENCE_SCREEN_TAG) {
        "$SETTINGS_XML has root <${root.tagName}>, expected <$PREFERENCE_SCREEN_TAG> — Gboard's " +
            "settings are no longer the androidx screen this patch appends to"
    }

    // Idempotent: applying a bundle over an already-patched APK must not stack a second screen.
    if (root.descendants().any { it.androidAttribute("key") == SCREEN_KEY }) return

    val screen = createElement(PREFERENCE_SCREEN_TAG).apply {
        setAndroidAttribute("key", SCREEN_KEY)
        setAndroidAttribute("title", SCREEN_TITLE)
        setAndroidAttribute("persistent", "false")
        // Borrowed so the row does not render iconless beside Gboard's own, which all carry one.
        root.descendants()
            .firstOrNull { it.tagName == RATE_US_PREFERENCE_TAG }
            ?.androidAttribute("icon")
            ?.let { setAndroidAttribute("icon", it) }
    }

    screen.appendChild(
        seekBar(
            key = STEP_SCALE_KEY,
            title = "Swipe length",
            summary = "How far to swipe per deleted word, as a percent of Gboard's own distance. " +
                "Lower deletes more words for the same swipe.",
            min = STEP_SCALE_MIN,
            max = STEP_SCALE_MAX,
            default = STEP_SCALE_DEFAULT,
        ),
    )
    screen.appendChild(
        seekBar(
            key = HOLD_DELAY_KEY,
            title = "Hold delay",
            summary = "Milliseconds the swipe must be held before it starts deleting. 0 lets a " +
                "quick flick register; Gboard's own delete swipe uses 200.",
            min = HOLD_DELAY_MIN,
            max = HOLD_DELAY_MAX,
            default = HOLD_DELAY_DEFAULT,
        ),
    )

    // Placement matters: appending to the root would land the row *after* the footer, which reads
    // as a stray control rather than a settings entry.
    val category = root.childElements(PREFERENCE_CATEGORY_TAG).lastOrNull()
    val footer = root.childElements(FOOTER_PREFERENCE_TAG).firstOrNull()
    when {
        category != null -> category.appendChild(screen)
        footer != null -> root.insertBefore(screen, footer)
        else -> root.appendChild(screen)
    }
}

private fun Document.seekBar(
    key: String,
    title: String,
    summary: String,
    min: Int,
    max: Int,
    default: Int,
): Element = createElement(SEEK_BAR_PREFERENCE_TAG).apply {
    setAndroidAttribute("key", key)
    setAndroidAttribute("title", title)
    setAndroidAttribute("summary", summary)
    setAndroidAttribute("min", min.toString())
    setAndroidAttribute("max", max.toString())
    setAndroidAttribute("defaultValue", default.toString())
    setAndroidAttribute("persistent", "true")
    // Without this the slider shows no number, which makes a percentage meaningless.
    setAndroidAttribute("showSeekBarValue", "true")
}
