package dev.jz6.flexboard.patches.shared

/**
 * Every text value file this bundle writes or splices is DOM-parsed before it lands on disk.
 * A malformed file otherwise surfaces thousands of lines inside Morphe's resource build —
 * "expected: END_TAG {}resources (position:END_DOCUMENT null@7141:1)" is a real line from a
 * real phone — naming neither the writer nor the file. Parsing at write time costs
 * milliseconds and names both.
 */
internal fun assertWellFormedXml(xml: String, where: String) {
    try {
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(xml.byteInputStream())
    } catch (e: Exception) {
        throw IllegalStateException("$where is not well-formed XML: ${e.message}", e)
    }
}

/**
 * Splices `fragment` (the entries of a patch-side values file — its `<resources>` wrapper is
 * expected and stripped) into the decoded body of `existingValues`, above the closing tag.
 * Writes nothing if `marker` (one of the fragment's names) is already present, so a second
 * run over an already-patched tree is a no-op.
 *
 * Extracting the interior with substringAfter/substringBeforeLast rather than removePrefix
 * bookkeeping: a wrapper that survives stripping shows up as a second `<resources>` in the
 * middle of the file, which is exactly the dev.4 crash (`arrays.xml @7141`). Asserting the
 * fragment is non-empty and wrapper-free errs at patch time instead.
 */
internal fun spliceValues(fragment: String, existingValues: String, marker: String): String {
    val inner = fragment
        .substringAfter("<resources>", missingDelimiterValue = "")
        .substringBeforeLast("</resources>")
        .trim()
    require(inner.isNotEmpty()) { "the values fragment has no entries to merge" }
    require(!inner.contains("<resources")) { "the fragment still carries its <resources> wrapper" }

    require("</resources>" in existingValues) {
        "the decoded values file has no closing </resources> — shape moved"
    }
    if (marker in existingValues) return existingValues
    return existingValues.trimEnd().removeSuffix("</resources>") + "\n" + inner + "\n</resources>\n"
}
