package dev.jz6.flexboard.patches.features.swipetodelete

import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.androidAttribute
import dev.jz6.flexboard.patches.shared.childElements
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Greys out the two rows [forceScrubPreferencesPatch] writes — **Glide typing** and **Glide
 * delete** — and puts a note directly above saying why.
 *
 * That patch writes `enable_gesture_input=false` and `enable_scrub_delete=true` at every app start:
 * a leftward drag across the letters is also a glide input so the two cannot both be live, and the
 * delete handler is attached declaratively so it is never built at all while its own preference is
 * off. Both rows stayed fully tappable throughout, so changing either appeared to work and then
 * silently reverted at the next app start. Greying them makes the constraint visible instead of
 * fought.
 *
 * ## Statically disabled, not a dependency
 *
 * This used to add a `SwitchPreferenceCompat` keyed `flexboard_enabled` with
 * `android:disableDependentsState="true"`, and make both rows `android:dependency` on it — so that
 * turning Flexboard off un-greyed them and handed glide typing back. That switch is gone; see
 * [swipeToDeletePatch].
 *
 * The two mechanisms could not be mixed. androidx requires a preference carrying the dependency's
 * key to exist **in the same hierarchy**, and throws `IllegalStateException` from
 * `registerDependency` otherwise — which would take out the whole gesture settings screen rather
 * than fail quietly. Removing the switch therefore meant removing both `dependency` attributes in
 * the same edit.
 *
 * What replaces them is simpler than what it replaces: `android:enabled="false"` written straight
 * onto each row. A framework attribute, no key, no hierarchy requirement, and nothing that can
 * throw. The rows are greyed for as long as the patch is applied, which is now exactly as long as
 * the preferences are being forced — the two can no longer disagree, because neither is conditional.
 *
 * ## The note is there on purpose
 *
 * A row that greys out with no explanation is worse than the row being tappable, and that argument
 * did not go away with the switch. So a non-selectable, non-persistent `Preference` sits above them
 * saying what is doing it and how to undo it. It carries a key only so this patch can recognise its
 * own work and stay idempotent when a bundle is applied over an already-patched APK.
 */
internal val glideTypingRowPatch = resourcePatch(
    description = "Greys out the Gboard glide settings that Flexboard writes for itself, and " +
        "explains what is doing it.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        document(GESTURE_SETTINGS_XML).use { gestureSettings ->
            gestureSettings.disableGlideRows()
        }
    }
}

/** Gboard's Glide typing screen. One of the few resources that keeps its real name. */
private const val GESTURE_SETTINGS_XML = "res/xml/setting_gesture.xml"

private const val PREFERENCE_SCREEN_TAG = "PreferenceScreen"
private const val PREFERENCE_TAG = "Preference"

/**
 * Not a Flexboard setting, and deliberately not one the user can change. It exists so the greyed
 * rows below it have a stated cause, and so this patch can recognise its own work.
 */
private const val NOTE_KEY = "flexboard_glide_note"
private const val NOTE_TITLE = "Managed by Flexboard"
private const val NOTE_SUMMARY =
    "Swipe-anywhere-to-delete needs Glide delete on and glide typing off, so it sets both and " +
        "the two settings below are locked. To use glide typing again, re-patch Gboard without " +
        "Flexboard's Swipe to Delete."

/** Glide typing, Glide trail, Glide delete, Glide cursor control. */
private const val GESTURE_ROW_COUNT = 4

/** Glide delete and Glide cursor control, in that order. */
private const val UNIDENTIFIED_ROW_COUNT = 2

private fun Document.disableGlideRows() {
    val root = documentElement
    check(root.tagName == PREFERENCE_SCREEN_TAG) {
        "$GESTURE_SETTINGS_XML has root <${root.tagName}>, expected <$PREFERENCE_SCREEN_TAG>"
    }

    // Idempotent: applying a bundle over an already-patched APK must not add the note twice.
    if (root.childElements().any { it.androidAttribute("key") == NOTE_KEY }) return

    // Both resolved before anything is inserted or disabled, since both searches read the very
    // attributes this then writes.
    val glideRow = root.findGlideTypingRow()
    val deleteRow = root.findGlideDeleteRow(glideRow)

    val note = createElement(PREFERENCE_TAG).apply {
        // A key only so the idempotence test above has something to match. Nothing reads it, and
        // `persistent="false"` keeps it out of the preference file entirely.
        setAndroidAttribute("key", NOTE_KEY)
        setAndroidAttribute("title", NOTE_TITLE)
        setAndroidAttribute("summary", NOTE_SUMMARY)
        setAndroidAttribute("persistent", "false")
        setAndroidAttribute("selectable", "false")
    }
    root.insertBefore(note, root.firstChild)

    glideRow.setAndroidAttribute("enabled", "false")
    deleteRow.setAndroidAttribute("enabled", "false")
}

/**
 * Gboard's **Glide delete** row — "Delete words by gliding left from the Delete key".
 *
 * This is the row [forceScrubPreferencesPatch] writes `true` to on every start, because the
 * handler is attached declaratively and is never built at all while it is off. Left tappable it has
 * the same problem the glide typing row had, one row down: turning it off appears to work and
 * reverts at the next start, and in the meantime the gesture is simply gone.
 *
 * ## This one is positional, and that is worth being honest about
 *
 * Glide typing can be found by what points at it. This row cannot: it and **Glide cursor control**
 * are identical in every respect the decoded XML exposes — same tag, same attribute set, and keys,
 * titles and summaries that are all string resources collapsed to the single name
 * `0_resource_name_obfuscated`. Nothing distinguishes them but order.
 *
 * So it is found by elimination: of the four rows, one is glide typing and one depends on it,
 * leaving two, and Glide delete is the first of those. The assertions cover the shape — four rows,
 * exactly two left after eliminating — but they cannot catch a reorder.
 *
 * What makes that acceptable is the pin. `COMPATIBILITY_GBOARD` fixes this bundle to one Gboard
 * build by version *and* signature, and a reorder cannot reach us without a new build failing that
 * gate first. It is also a milder bet than several the project already makes: the hardcoded
 * resource ids in [forceScrubPreferencesPatch] and `flickSymbolsPatch` would silently write the
 * *wrong preference* on a changed build, where the worst this can do is grey the cursor-control row
 * instead of this one. Cosmetic, and visible the moment the screen is opened.
 */
private fun Element.findGlideDeleteRow(glideRow: Element): Element {
    val rows = childElements().toList()
    check(rows.size == GESTURE_ROW_COUNT) {
        "$GESTURE_SETTINGS_XML has ${rows.size} rows, expected $GESTURE_ROW_COUNT — Gboard's " +
            "glide settings have changed shape, so the unnamed rows can no longer be told apart " +
            "by elimination"
    }
    val unidentified = rows.filter { it !== glideRow && it.androidAttribute("dependency") == null }
    check(unidentified.size == UNIDENTIFIED_ROW_COUNT) {
        "Expected $UNIDENTIFIED_ROW_COUNT rows left after eliminating glide typing and the row " +
            "depending on it, found ${unidentified.size}"
    }
    return unidentified.first()
}

/**
 * The glide typing row, identified by structure rather than by name.
 *
 * Every preference key in this file is a string resource named `0_resource_name_obfuscated` —
 * `enable_gesture_input`, `pref_gesture_preview_trail`, `enable_scrub_delete` and
 * `enable_scrub_move` all share it — so the row cannot be matched on the face of its decoded
 * `@string/…` reference.
 *
 * It can be matched by what points at it. Gboard's trail row is the file's only `android:dependency`
 * and it depends on glide typing, so the glide row is the one whose key that dependency names. Both
 * sides are the same resource id, so whatever the decoder emits for the collapsed name it emits the
 * same string for both.
 *
 * That relies on the decoder giving colliding names distinct decoded forms, which it must:
 * `res/xml/settings.xml` is decoded and recompiled by every build of this bundle and all sixteen of
 * its rows carry `@string/0_resource_name_obfuscated` keys. Were those collapsed to one name,
 * recompiling would repoint them at a single resource and Gboard's settings would already be broken
 * in every release so far.
 *
 * If that ever stops holding, every row matches and this fails loudly rather than greying the wrong
 * one.
 */
private fun Element.findGlideTypingRow(): Element {
    val dependents = childElements().filter { it.androidAttribute("dependency") != null }.toList()
    check(dependents.size == 1) {
        // Gboard's own Glide trail row, which depends on glide typing. This patch adds no
        // dependency of its own any more, so the only one in the file is Gboard's.
        "Expected exactly one row with android:dependency=… in $GESTURE_SETTINGS_XML, found " +
            "${dependents.size} — the row that identifies glide typing is no longer unique"
    }
    val dependencyKey = dependents.single().androidAttribute("dependency")

    val matches = childElements().filter { it.androidAttribute("key") == dependencyKey }.toList()
    check(matches.size == 1) {
        "Expected exactly one row keyed '$dependencyKey' in $GESTURE_SETTINGS_XML, found " +
            "${matches.size} — obfuscated key names are no longer distinct once decoded, so the " +
            "glide typing row cannot be told apart from its siblings"
    }
    val glideRow = matches.single()
    check(glideRow.androidAttribute("dependency") == null) {
        "The glide typing row already declares a dependency on " +
            "'${glideRow.androidAttribute("dependency")}', which this patch would overwrite"
    }
    return glideRow
}
