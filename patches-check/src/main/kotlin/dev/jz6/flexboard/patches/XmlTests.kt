package dev.jz6.flexboard.patches

import dev.jz6.flexboard.patches.shared.ANDROID_NS
import dev.jz6.flexboard.patches.shared.setAndroidAttribute
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/** Morphe's DOM is not namespace-aware; overwriting a parsed attribute must still work. */
internal fun xmlTests() {
    for (namespaced in listOf(false, true)) {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = namespaced }
        val xml = """<Preference xmlns:android="$ANDROID_NS" android:enabled="true"/>"""
        val element = parser.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        element.setAndroidAttribute("enabled", "false")
        equal("overwrites a parsed attribute (namespace-aware=$namespaced)",
            "false", element.getAttribute("android:enabled"))
        equal("no duplicate attribute (namespace-aware=$namespaced)",
            "2", element.attributes.length.toString())
    }
}
