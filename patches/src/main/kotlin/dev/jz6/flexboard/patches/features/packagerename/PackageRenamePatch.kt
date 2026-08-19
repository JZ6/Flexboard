package dev.jz6.flexboard.patches.features.packagerename

import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.ANDROID_NS
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_PACKAGE_NAME
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_PATCHED_PACKAGE_NAME
import dev.jz6.flexboard.patches.shared.Constants.GBOARD_SETTINGS_XML
import dev.jz6.flexboard.patches.shared.Constants.SETTINGS_ACTIVITY_CLASS
import dev.jz6.flexboard.patches.shared.androidAttribute
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.NodeList

/**
 * Renames the application package so a patched build installs beside the official Gboard rather
 * than replacing it. Both keyboards stay available in the keyboard picker.
 *
 * Fifteen manifest values embed the package name — the root `package`, two permissions, two
 * phenotype registration keys, a deeplink host, a receiver permission and eight provider
 * authorities. Every one has to move together: a provider authority left on the original value
 * collides with the installed official Gboard and the install fails outright.
 *
 * So this is deliberately strict. Each value is expected exactly once, and after rewriting the
 * known ones it sweeps the whole manifest for anything else still mentioning the package. A Gboard
 * update that adds a sixteenth authority therefore fails the patch rather than shipping a
 * half-renamed app.
 *
 * Idempotent: an already-renamed manifest is validated and left alone rather than double-prefixed.
 */
@Suppress("unused")
val installAsGboardClonePatch = resourcePatch(
    name = "Install as Gboard Clone",
    description = "Rename the package to $GBOARD_PATCHED_PACKAGE_NAME so the patched build " +
        "installs alongside the official Gboard instead of replacing it.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        document("AndroidManifest.xml").use { manifest ->
            manifest.renameGboardPackage()
        }
        document(GBOARD_SETTINGS_XML).use { settings ->
            settings.retargetFlexboardSettings()
        }
    }
}

/**
 * Points the Flexboard settings row at the renamed package.
 *
 * The row launches its Activity by explicit component — an implicit intent cannot reach a
 * non-exported one on Android 14+, which is how `v0.1.0-dev.4` broke — so it names a package, and
 * renaming the app without updating it would leave a row that resolves to nothing.
 *
 * Both patches write in `finalize` with no ordering guarantee between them, so this cannot assume
 * the row is there yet. If the settings patch ran first this corrects what it wrote; if it runs
 * second it reads the already-renamed manifest and writes the right value itself. Writing a value
 * that is already correct is a no-op, which is what makes either order safe — and why this asserts
 * nothing about finding the row. Not finding it is the ordinary case where the settings patch was
 * not selected at all.
 */
private fun Document.retargetFlexboardSettings() {
    getElementsByTagName("*")
        .elements()
        .filter { it.localElementName() == "intent" }
        .filter { it.androidAttribute("targetClass") == SETTINGS_ACTIVITY_CLASS }
        .toList()
        .forEach { it.setAndroidAttribute("targetPackage", GBOARD_PATCHED_PACKAGE_NAME) }
}

private fun Document.renameGboardPackage() {
    val rootPackage = documentElement.getAttribute("package")
    val alreadyRenamed = when (rootPackage) {
        GBOARD_PACKAGE_NAME -> false
        GBOARD_PATCHED_PACKAGE_NAME -> true
        else -> error(
            "Unexpected Gboard manifest package '$rootPackage'; " +
                "expected '$GBOARD_PACKAGE_NAME' or '$GBOARD_PATCHED_PACKAGE_NAME'",
        )
    }

    val attributes = getElementsByTagName("*")
        .elements()
        .flatMap { element -> element.attributes.attrs() }
        .toList()

    val doublePrefixed = attributes.firstOrNull { DOUBLE_PREFIX in it.value }
    check(doublePrefixed == null) {
        "Double-prefixed package value at ${doublePrefixed?.name}: ${doublePrefixed?.value}"
    }

    val selected = RENAME_MAPPINGS.map { mapping ->
        val expected = if (alreadyRenamed) mapping.renamed else mapping.original
        val opposite = if (alreadyRenamed) mapping.original else mapping.renamed
        val matches = attributes.filter { it.matches(mapping) && it.value == expected }
        val oppositeMatches = attributes.filter { it.matches(mapping) && it.value == opposite }
        check(matches.size == 1 && oppositeMatches.isEmpty()) {
            "Expected exactly one <${mapping.element} ${mapping.attribute}=\"$expected\">, " +
                "found ${matches.size} (and ${oppositeMatches.size} already in the other state)"
        }
        matches.single()
    }

    val unexpected = attributes.firstOrNull { attribute ->
        GBOARD_PACKAGE_NAME in attribute.value && selected.none { it === attribute }
    }
    check(unexpected == null) {
        "Unexpected package-derived manifest value at " +
            "${unexpected?.ownerElement?.localElementName()} ${unexpected?.name}: " +
            "${unexpected?.value} — Gboard has added something this patch does not know about"
    }

    if (alreadyRenamed) return

    selected.zip(RENAME_MAPPINGS).forEach { (attribute, mapping) ->
        attribute.value = mapping.renamed
    }
}

private class RenameMapping(
    val element: String,
    val attribute: String,
    val namespaced: Boolean,
    val original: String,
) {
    val renamed: String = original.replace(GBOARD_PACKAGE_NAME, GBOARD_PATCHED_PACKAGE_NAME)
}

private fun androidMapping(element: String, attribute: String, original: String) =
    RenameMapping(element, attribute, namespaced = true, original = original)

/**
 * `permission name` and `receiver permission` both carry the pixelbundle value, so a mapping is
 * only unambiguous as the triple of element, attribute and value.
 */
private val RENAME_MAPPINGS = listOf(
    RenameMapping("manifest", "package", namespaced = false, original = GBOARD_PACKAGE_NAME),
    androidMapping("permission", "name", "$GBOARD_PACKAGE_NAME.pixelbundle.RECEIVER"),
    androidMapping(
        "permission",
        "name",
        "$GBOARD_PACKAGE_NAME.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    ),
    androidMapping(
        "meta-data",
        "name",
        "com.google.android.gms.phenotype.registration.binarypb:$GBOARD_PACKAGE_NAME",
    ),
    androidMapping(
        "meta-data",
        "name",
        "com.google.android.gms.phenotype.registration.xml:$GBOARD_PACKAGE_NAME",
    ),
    androidMapping("data", "host", "deeplink.$GBOARD_PACKAGE_NAME"),
    androidMapping("receiver", "permission", "$GBOARD_PACKAGE_NAME.pixelbundle.RECEIVER"),
    androidMapping("provider", "authorities", GBOARD_PACKAGE_NAME),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.clipboard_content"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.inputactionprovider"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.swissarmyknifefileprovider"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.fileprovider"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.tracing"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.wdb"),
    androidMapping("provider", "authorities", "$GBOARD_PACKAGE_NAME.mlkitinitprovider"),
)

/** Derived rather than written out, so it cannot drift from the rename itself. */
private val DOUBLE_PREFIX =
    GBOARD_PATCHED_PACKAGE_NAME.replace(GBOARD_PACKAGE_NAME, GBOARD_PATCHED_PACKAGE_NAME)

/**
 * Android attributes appear either namespaced or as a literal `android:` prefix depending on how
 * the document was decoded, so both have to be accepted.
 */
private fun Attr.matches(mapping: RenameMapping): Boolean {
    if (ownerElement?.localElementName() != mapping.element) return false
    return if (mapping.namespaced) {
        (namespaceURI == ANDROID_NS && localName == mapping.attribute) ||
            name == "android:${mapping.attribute}"
    } else {
        namespaceURI == null && name == mapping.attribute
    }
}

private fun Element.localElementName(): String = localName ?: tagName.substringAfter(':')

private fun NodeList.elements(): Sequence<Element> =
    (0 until length).asSequence().mapNotNull { item(it) as? Element }

private fun NamedNodeMap.attrs(): Sequence<Attr> =
    (0 until length).asSequence().mapNotNull { item(it) as? Attr }
