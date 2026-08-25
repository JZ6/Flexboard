package dev.jz6.flexboard.patches.features.settings

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_SETTINGS_XML
import dev.jz6.flexboard.patches.shared.Constants.SETTINGS_FRAGMENT_CLASS
import dev.jz6.flexboard.patches.shared.androidAttribute
import dev.jz6.flexboard.patches.shared.childElements
import dev.jz6.flexboard.patches.shared.descendants
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.w3c.dom.Document

/**
 * Adds a **Flexboard** entry to Gboard's settings that opens a real preferences screen hosted by
 * Gboard itself.
 *
 * ## How the row navigates
 *
 * Gboard's settings is androidx preferences end to end: the root screen's rows carry an
 * `android:fragment` attribute naming a fragment class, the host's preference-click path (its
 * port of `PreferenceFragmentCompat.onPreferenceTreeClick`) reads it, and `Fragment.instantiate`
 * turns the name into an instance via `Class.forName` with a public no-arg constructor. There is
 * no router, no allow-set and no registry — the class name on the row is the whole wiring, which
 * is why this patch only has to write XML.
 *
 * The fragment the row names, `dev.jz6.flexboard.extension.settings.FlexboardSettingsFragment`, is
 * carried in the extension DEX and subclasses Gboard's own `CommonPreferenceFragment` through a
 * compile-time stub (see `stubs/`). It reports its screen by name — aapt2 assigns the id after
 * this patch runs — and the host takes it from there: app bar, back stack, RecyclerView rows,
 * icon tinting, dark mode and Material You are whichever Gboard's own screens get, because they
 * are the same rendering path.
 *
 * ## Where the values live
 *
 * A fragment-lifecycle hook inside Gboard installs a `PreferenceDataStore` bridge onto every
 * hosted preference fragment, so the sliders in `res/xml/flexboard_settings.xml` persist straight
 * into `Lqhy;` — the store the swipe patches already read mid-gesture. The whole
 * extension-side storage-mirror (custom Activity, custom seek bars, context surgery to find the
 * device-protected file) is gone.
 *
 * ## How this got here
 *
 * The first version appended a nested `<PreferenceScreen>` to the root — androidx renders one as a
 * row but only navigates to it when the host implements `OnPreferenceStartScreenCallback`, which
 * Gboard's host does not, so the tap went nowhere (v0.1.0-dev.3). The next launched an extension
 * Activity, first by implicit action (v0.1.0-dev.4: exported-component rules on `targetSdk` 34+
 * mean an implicit intent matches nothing), then by explicit component, which worked but shipped
 * an entire hand-pixeled screen beside Gboard's. The `fragment=` route was missed until dex
 * research on 18.0.3 established that the click path has no registry in front of it.
 *
 * The filename this writes to derives its runtime id by name (`getIdentifier`), so the patch
 * side only ever names it; and the row's target is a class name, not a package, so the rename
 * patch has nothing to correct.
 *
 * Both files this touches keep their real names through `aapt2 --collapse-resource-names` — 33 of
 * Gboard's 33,287 entries do, and the settings screens are among them. That is the only reason
 * this patch can address them; see the addressability note in `docs/development.md`.
 */
/** The default symbol per hotkey slot, in slot order. Kept as one list with the drawables loop
 * below; the Java side (Hotkeys.DEFAULT_ICON_NAMES) carries the same order — the constants
 * checker verifies both stay in step. */
internal val HOTKEY_DEFAULT_SYMBOLS = listOf(
    "alternate_email", "password", "phone_enabled", "local_post_office",
    "home_pin", "work", "favorite", "kid_star", "credit_card",
    "hexagon", "hive", "sports_soccer",
)

/** The pack's remainder beyond the slot defaults — the back half of the per-slot icon picker's
 * cycle, in tap order. Java mirrors it as Hotkeys.EXTRA_ICON_NAMES, same checker. */
internal val HOTKEY_EXTRA_SYMBOLS = listOf(
    "snowflake", "token",
    "counter_1", "counter_2", "counter_3", "counter_4", "counter_5",
    "counter_6", "counter_7", "counter_8", "counter_9",
)

internal val settingsScreenPatch = resourcePatch(
    description = "Adds a Flexboard entry to Gboard's settings that opens Flexboard's own screen.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        // The icon as a new drawable resource. aapt2 assigns its id during recompilation, and the
        // preference XML below references it by name.
        writePatchResource("flexboard_settings_icon.xml", "res/drawable")

        // The hotkey icon pack: the twelve per-slot defaults plus the picker's extras, both
        // symbol lists above. All resolve by name at runtime through getIdentifier, so aapt2's
        // numbering never leaks into preferences — a blob exported on one device round-trips on
        // another, and the picker's cycle table needs no resource ids of its own.
        for (symbol in HOTKEY_DEFAULT_SYMBOLS + HOTKEY_EXTRA_SYMBOLS) {
            writePatchResource("flexboard_icon_$symbol.xml", "res/drawable")
        }

        // The screen itself. Written under res/xml so aapt2 compiles it into the table; the
        // fragment resolves the id by name at runtime. The version placeholder in the footer
        // row is filled from flexboard_version.txt, which :patches:processResources has already
        // baked to the bundle's version, so the settings screen and Morphe Manager's about text
        // can never disagree.
        writePatchResource("flexboard_settings.xml", "res/xml", mapOf(
            "FLEXBOARD_VERSION" to readVersion(),
        ))

        document(GBOARD_SETTINGS_XML).use { settings ->
            settings.addFlexboardEntry()
        }
    }
}

private const val PREFERENCE_SCREEN_TAG = "PreferenceScreen"
private const val PREFERENCE_CATEGORY_TAG = "androidx.preference.PreferenceCategory"
private const val FOOTER_PREFERENCE_TAG = "com.android.settingslib.widget.FooterPreference"

/**
 * The row whose icon Flexboard uses, and the Flexboard screen's row widget on the other side.
 *
 * The icon is a patch-added drawable resource, referenced by name; aapt2 assigns it an id during
 * recompilation. Its fill colour is fixed; `HeaderPreference` tints it to the row's title colour
 * at bind time.
 */
private const val FLEXBOARD_ICON_REF = "@drawable/flexboard_settings_icon"

/**
 * Gboard's own settings row widget, used in place of the androidx default so our entry is
 * rendered by the same row layout — same icon inset, same text metrics, same tint — as every
 * row around it.
 *
 * The class name is a plain unobfuscated string here because preference XML instantiates views
 * by name, so R8 cannot rename it; `res/xml/settings.xml` uses it for its own rows. It extends
 * `ExtendedPreference`, which extends androidx `Preference`, and its constructor is the standard
 * `(Context, AttributeSet)` the framework reflects on, so no attribute it reads is new to it.
 */
private const val PREFERENCE_TAG =
    "com.google.android.libraries.inputmethod.settings.widget.HeaderPreference"

private const val ENTRY_KEY = "flexboard_settings"
private const val ENTRY_TITLE = "Flexboard"

/**
 * Deliberately vague about *which* settings. The screen's contents depend on which patches were
 * ticked, and naming the sliders meant rewriting this line each time a feature landed.
 */
private const val ENTRY_SUMMARY = "Gesture settings"

/**
 * Copies a patch resource from `patches/src/main/resources/` into the APK's `res/` tree so aapt2
 * compiles it and assigns a resource id.
 *
 * `name` is just the filename (`flexboard_settings_icon.xml`); the directory comes from `target`,
 * because the two were accidentally concatenated once before — the source path ended up with
 * `drawable/drawable/...` and the lookup threw, killing the entire finalize block and taking the
 * settings row down with it. Keeping the directory in one place means `name` can never repeat it.
 *
 * Anything written this way is resolved by name at runtime via `getIdentifier`, so neither the
 * patch nor the extension needs to know the id aapt2 assigned — only the name.
 */
context(context: ResourcePatchContext)
private fun writePatchResource(name: String, target: String) =
    writePatchResource(name, target, emptyMap())

context(context: ResourcePatchContext)
private fun writePatchResource(name: String, target: String, placeholders: Map<String, String>) {
    val source = "${target.removePrefix("res/")}/$name"
    var xml = {}.javaClass.classLoader
        ?.getResourceAsStream(source)
        ?.bufferedReader()?.use { it.readText() }
        ?: error("$source not found in patch resources")
    for ((key, value) in placeholders) {
        val token = "@$key@"
        require(token in xml) { "$source lacks the @$key@ placeholder it was supposed to carry" }
        xml = xml.replace(token, value)
    }
    // Parse before write: a malformed file otherwise only surfaces thousands of lines into
    // Morphe's resource-id pass ("expected: END_TAG ... resources @7141:1"), with no hint of
    // which patch resource produced it. Failing here costs milliseconds and names the file.
    try {
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.ByteArrayInputStream(xml.toByteArray()))
    } catch (e: Exception) {
        throw IllegalStateException("$source is not well-formed XML: ${e.message}", e)
    }
    context.get("$target/$name", true).writeText(xml)
}

/** The bundle's own version, baked into a patch resource by the build. */
private fun readVersion(): String {
    val text = {}.javaClass.classLoader
        ?.getResourceAsStream("flexboard_version.txt")
        ?.bufferedReader()?.use { it.readText() }?.trim()
        ?: error("flexboard_version.txt not found in patch resources")
    // `@VERSION@` surviving to here means the resource substitution did not run: the build path
    // is assembler-only, not the Gradle project. Fail loudly rather than ship "Flexboard @VERSION@".
    require(!text.startsWith("@")) { "flexboard_version.txt is still the placeholder itself" }
    return text
}

private fun Document.addFlexboardEntry() {
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
        // Nothing to store: the row is a launcher, and the fragment owns the values.
        setAndroidAttribute("persistent", "false")
        setAndroidAttribute("icon", FLEXBOARD_ICON_REF)
        // How the tap navigates. Gboard's port of androidx's preference-click path reads this
        // attribute, instantiates the fragment by name and transacts it — no intent, no manifest
        // entry and no package name (which is why the rename patch never touches this row).
        setAndroidAttribute("fragment", SETTINGS_FRAGMENT_CLASS)
    }

    // First row of the first category, so it opens at the top of the screen rather than buried
    // down beside About. Inside a category rather than above one, because a row that is a direct
    // child of the screen renders without the inset and grouping every other row has.
    //
    // `insertBefore(entry, firstChild)` is deliberate over building an index: `firstChild` may be
    // a whitespace text node, and inserting ahead of it still lands at position zero — while a
    // null firstChild (an empty category) makes this an append, which is the right answer there
    // too.
    //
    // Appending to the root is what the fallbacks avoid: it would land the row *after* the
    // footer, reading as a stray control rather than a settings entry.
    val category = root.childElements(PREFERENCE_CATEGORY_TAG).firstOrNull()
    val footer = root.childElements(FOOTER_PREFERENCE_TAG).firstOrNull()
    when {
        category != null -> category.insertBefore(entry, category.firstChild)
        footer != null -> root.insertBefore(entry, footer)
        else -> root.appendChild(entry)
    }
}
