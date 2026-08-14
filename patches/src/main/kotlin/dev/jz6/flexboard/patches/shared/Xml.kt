package dev.jz6.flexboard.patches.shared

import org.w3c.dom.Element

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Child elements, optionally filtered by tag name. Skips text and comment nodes. */
internal fun Element.childElements(tagName: String? = null): Sequence<Element> =
    (0 until childNodes.length)
        .asSequence()
        .map(childNodes::item)
        .filterIsInstance<Element>()
        .filter { tagName == null || it.tagName == tagName }

/**
 * Reads an `android:`-namespaced attribute.
 *
 * Both spellings are checked because decoded resources are not consistently namespace-aware: a
 * document parsed without namespace support exposes the attribute under the literal name
 * `android:foo` and returns nothing for a namespace lookup.
 */
internal fun Element.androidAttribute(localName: String): String? {
    val namespaced = getAttributeNS(ANDROID_NS, localName)
    if (namespaced.isNotEmpty()) return namespaced
    val prefixed = getAttribute("android:$localName")
    return prefixed.ifEmpty { null }
}

internal fun Element.setAndroidAttribute(localName: String, value: String) {
    setAttributeNS(ANDROID_NS, "android:$localName", value)
}

/** Depth-first walk of an element and everything beneath it. */
internal fun Element.descendants(): Sequence<Element> =
    childElements().flatMap { sequenceOf(it) + it.descendants() }
