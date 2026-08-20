package dev.jz6.flexboard.patches.features.settings

import app.morphe.patcher.patch.ResourcePatchContext
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

        // Write the Flexboard settings icon as a new drawable resource. aapt2 assigns it an id
        // during recompilation, and the preference XML references it by name.
        writeDrawableResource("flexboard_settings_icon.xml")

        // Write custom hotkey icon drawables. These are resolved by name at runtime via
        // getIdentifier, so the patch does not need to know the resource IDs aapt2 assigns.
        writeDrawableResource("flexboard_hotkey_icon_1.xml")
        writeDrawableResource("flexboard_hotkey_icon_2.xml")
        writeDrawableResource("flexboard_hotkey_icon_3.xml")
        writeDrawableResource("flexboard_hotkey_icon_4.xml")
        writeDrawableResource("flexboard_hotkey_icon_5.xml")
        writeDrawableResource("flexboard_hotkey_icon_6.xml")
        writeDrawableResource("flexboard_hotkey_icon_7.xml")
        writeDrawableResource("flexboard_hotkey_icon_8.xml")
        writeDrawableResource("flexboard_hotkey_icon_9.xml")
        writeDrawableResource("flexboard_hotkey_icon_10.xml")
        writeDrawableResource("flexboard_hotkey_icon_11.xml")
        writeDrawableResource("flexboard_hotkey_icon_12.xml")
        writeDrawableResource("flexboard_icon_snowflake.xml")
        writeDrawableResource("flexboard_icon_token.xml")
        for (n in 1..9) {
            writeDrawableResource("flexboard_icon_counter_$n.xml")
        }

        document(GBOARD_SETTINGS_XML).use { settings ->
            settings.addFlexboardEntry(packageName)
        }
    }
}

private const val PREFERENCE_SCREEN_TAG = "PreferenceScreen"

/**
 * Writes a vector drawable from `patches/src/main/resources/drawable/` into the APK's
 * `res/drawable/` so aapt2 compiles it and assigns it a resource ID.
 *
 * The drawable is then resolved by name at runtime via `getIdentifier`, so neither the patch
 * nor the extension needs to know the ID aapt2 assigned — only the name.
 */
context(context: ResourcePatchContext)
private fun writeDrawableResource(name: String) {
    val xml = {}.javaClass.classLoader
        ?.getResourceAsStream("drawable/$name")
        ?.bufferedReader()?.use { it.readText() }
        ?: error("$name not found in patch resources")
    context.get("res/drawable/$name", true).writeText(xml)
}
private const val PREFERENCE_CATEGORY_TAG = "androidx.preference.PreferenceCategory"
private const val FOOTER_PREFERENCE_TAG = "com.android.settingslib.widget.FooterPreference"
/**
 * The row whose icon Flexboard uses.
 *
 * The icon is a patch-added drawable resource, written to `res/drawable/` from
 * `patches/src/main/resources/drawable/` and referenced by name
 * (`@drawable/flexboard_settings_icon`). aapt2 assigns it an id during recompilation. Its fill
 * colour is fixed; `HeaderPreference` tints it to the row's title colour at bind time.
 */
private const val FLEXBOARD_ICON_REF = "@drawable/flexboard_settings_icon"

/**
 * Gboard's own settings row widget, used in place of the androidx default so our entry is
 * rendered by the same row layout — same icon inset, same text metrics, same tint — as every
 * row around it.
 *
 * The class name is a plain unobfuscated string here because preference XML instantiates views
 * by name, so R8 cannot rename it; `res/B_o.xml` (the settings root) uses it for its own rows.
 * It extends `ExtendedPreference` which extends androidx `Preference`, and its constructor is
 * the standard `(Context, AttributeSet)` the framework reflects on, so no attribute it reads is
 * new to it.
 */
private const val PREFERENCE_TAG =
    "com.google.android.libraries.inputmethod.settings.widget.HeaderPreference"
private const val INTENT_TAG = "intent"

private const val ENTRY_KEY = "flexboard_settings"
private const val ENTRY_TITLE = "Flexboard"
/**
 * Deliberately vague about *which* settings. The screen's contents depend on which patches were
 * ticked, and this row is added by every one of them — naming the sliders meant rewriting this line
 * each time a feature landed, and it was already stale once.
 */
private const val ENTRY_SUMMARY = "Gesture, toolbar and hotkey settings"

/** Ignored once the intent carries a component, but it is what v0.3 shipped. */
private const val ENTRY_ACTION = "android.intent.action.MAIN"

/**
 * Gboard's own settings screen, whose theme this one borrows so the two look alike.
 *
 * The class name is a plain unobfuscated string in the manifest — Android instantiates activities
 * by name, so R8 cannot rename it.
 */
private const val GBOARD_SETTINGS_ACTIVITY =
    "com.google.android.apps.inputmethod.latin.preference.SettingsActivity"

/**
 * Used only when Gboard's own settings activity cannot be found.
 *
 * `Theme.DeviceDefault.Settings` came first and clipped the top row: Gboard targets SDK 37, so on
 * Android 15+ the window is edge-to-edge, and how much of the status bar inset a themed action bar
 * had already taken is not something a merged class can determine on every device. With no action
 * bar the Activity is the only inset consumer and the arithmetic is its own — which is why
 * `FlexboardSettingsActivity` still handles the no-action-bar case rather than assuming one.
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

    // Whatever Gboard themes its own settings with, so this screen inherits the same colours,
    // typography and widget styling — including Material You, which a hardcoded palette cannot
    // follow. Copied rather than named: the value is a resource id (`@0x7f15044e` on 18.0.3) and
    // every resource id in this project moved between 17.7.7 and 18. A stale one would still be a
    // valid id, so it would quietly theme the screen as something unrelated instead of failing.
    val gboardTheme = application.childElements("activity")
        .firstOrNull { it.androidAttribute("name") == GBOARD_SETTINGS_ACTIVITY }
        ?.androidAttribute("theme")

    application.appendChild(
        createElement("activity").apply {
            setAndroidAttribute("name", SETTINGS_ACTIVITY_CLASS)
            // Reached only from Gboard's own settings, by explicit component, so nothing outside
            // the app needs to start it and no intent filter has to exist for it to resolve.
            setAndroidAttribute("exported", "false")
            setAndroidAttribute("label", ENTRY_TITLE)
            setAndroidAttribute("theme", gboardTheme ?: SETTINGS_THEME)
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
        // The Flexboard settings icon, written as a new drawable resource above.
        setAndroidAttribute("icon", FLEXBOARD_ICON_REF)
    }
    entry.appendChild(
        createElement(INTENT_TAG).apply {
            setAndroidAttribute("action", ENTRY_ACTION)
            setAndroidAttribute("targetPackage", packageName)
            setAndroidAttribute("targetClass", SETTINGS_ACTIVITY_CLASS)
        },
    )

    // First row of the first category, so it opens at the top of the screen rather than buried
    // down beside About. Inside a category rather than above one, because a row that is a direct
    // child of the screen renders without the inset and grouping every other row has.
    //
    // `insertBefore(entry, firstChild)` is deliberate over building an index: `firstChild` may be a
    // whitespace text node, and inserting ahead of it still lands at position zero — while a null
    // firstChild (an empty category) makes this an append, which is the right answer there too.
    //
    // Appending to the root is what the fallbacks avoid: it would land the row *after* the footer,
    // reading as a stray control rather than a settings entry.
    val category = root.childElements(PREFERENCE_CATEGORY_TAG).firstOrNull()
    val footer = root.childElements(FOOTER_PREFERENCE_TAG).firstOrNull()
    when {
        category != null -> category.insertBefore(entry, category.firstChild)
        footer != null -> root.insertBefore(entry, footer)
        else -> root.appendChild(entry)
    }
}
