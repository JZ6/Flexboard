package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.HOTKEY_SLOTS
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Widens Gboard's toolbar allowed-id set with Flexboard's button ids.
 *
 * Gboard reads exactly one resource — the string array at `0x7f0300dc` — into the immutable id
 * set both halves of admission consult: the bar controller folds newly-registered ids into the
 * shown list only if the set contains them (`Lmlh.g`), and the saved order keeps only ids the
 * set contains on read (`Lmjv.c`). Everything about reorder and persistence stays stock because
 * nothing dex-side is touched. The mechanism, the trace and the alternatives are written up in
 * `docs/toolbar-access-points.md`.
 *
 * On its own this patch is inert: the ids admitted here draw nothing unless another patch
 * registers an access point under the same id — names without a registry entry are skipped at
 * render (`Lmlh.w` does a map lookup and drops misses). The consumers arrive with the hotkey
 * patches.
 *
 * Unnamed on purpose: nothing about it is user-meaningful alone, and a "Toolbar Slots" tickbox
 * would invite deselecting a dependency the hotkey patches can never actually exclude
 * (they `dependsOn` it). It runs whenever a consumer is selected, and never on its own.
 */
internal val toolbarSlotsPatch = resourcePatch(
    description = "Admit Flexboard's toolbar button ids natively, widening Gboard's own " +
        "allowed-set array. No other change; reorder and persistence stay stock.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    finalize { widenAllowedIdSet() }
}

/** The slot count lives in shared/ToolbarHotkeys.kt, which emits the per-slot blocks and owns it. */

private const val SLOT_STRINGS = "values/flexboard_toolbar_slots.xml"

/**
 * The member of the allowed set that cannot be renamed: values inside the array are plain text
 * like `editor_info`, which R8 cannot touch — unlike the array's own name, which is obfuscated
 * per build (and is named `array_0x7f0300dc` only in decoded output). Locate the array by its
 * contents, always.
 */
private const val SENTINEL_ID = "editor_info"

context(context: ResourcePatchContext)
private fun widenAllowedIdSet() {
    val fragment = {}.javaClass.classLoader
        ?.getResourceAsStream(SLOT_STRINGS)
        ?.bufferedReader()?.use { it.readText() }
        ?: error("$SLOT_STRINGS not found in patch resources")

    val slotIds = Regex("""name="(flexboard_hotkey_\d+)"""").findAll(fragment)
        .map { it.groupValues[1] }.toList()
    require(slotIds.size == HOTKEY_SLOTS) {
        "$SLOT_STRINGS carries ${slotIds.size} slot ids, expected $HOTKEY_SLOTS"
    }

    // 1. Give every id a string resource. The value is deliberately the id itself — the runtime
    //    set is built from values and the encoder from names, and making them identical keeps
    //    both directions a no-op lookup.
    val stringsFile = context.get("res/values/strings.xml", true)
    val stringsMerged = spliceValues(fragment, stringsFile.readText(), marker = slotIds.first())
    assertWellFormedXml(stringsMerged, stringsFile.name)
    stringsFile.writeText(stringsMerged)

    // 2. Splice the ids into the allowed-set array, located by its sentinel member.
    val arraysFile = context.get("res/values/arrays.xml", true)
    val arrays = arraysFile.readText()

    if ("@string/${slotIds.first()}" in arrays) return  // already widened; repeat finalize is legal

    // Which strings.xml name holds the sentinel id? Gboard's names are obfuscated, so locate it
    // by value: <string name="X">editor_info</string>.
    val sentinelName = Regex("""<string name="([\w.]+)"[^>]*>$SENTINEL_ID</string>""")
        .find(stringsMerged)?.groupValues?.get(1)
        ?: error("\"$SENTINEL_ID\" not among Gboard's strings — the sentinel moved")
    val sentinelRef = "@string/$sentinelName"

    // The array that carries the sentinel is the allowed set. Scan whole array blocks so an
    // unrelated array that happens to name-drop it is still caught by the count assertion below.
    val blocks = Regex("""<array name="([^"]+)">(.*?)</array>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(arrays).toList()
    val holders = blocks.filter { sentinelRef in it.groupValues[2] }
    require(holders.size == 1) {
        "expected exactly one string array containing $sentinelRef, found ${holders.size}"
    }
    val holder = holders.single()
    val itemLines = slotIds.joinToString("\n") { "    <item>@string/$it</item>" }
    val widened = holder.value.replace("</array>", "$itemLines\n  </array>")
    val merged = arrays.replace(holder.value, widened)
    assertWellFormedXml(merged, arraysFile.name)
    arraysFile.writeText(merged)
}

// ---------------------------------------------------------------------------------------------
// Moved here from shared/ValuesMerge.kt. Both were `internal` in `shared/`, and both had exactly
// one caller: this file. A single-consumer helper in a shared package advertises reuse that does
// not exist, and sends the next reader looking for the other users. If a second patch ever needs
// either, promoting them back is a two-line change.
// ---------------------------------------------------------------------------------------------

/**
 * Every text value file this bundle writes or splices is DOM-parsed before it lands on disk.
 * A malformed file otherwise surfaces thousands of lines inside Morphe's resource build —
 * "expected: END_TAG {}resources (position:END_DOCUMENT null@7141:1)" is a real line from a
 * real phone — naming neither the writer nor the file. Parsing at write time costs
 * milliseconds and names both.
 */
private fun assertWellFormedXml(xml: String, where: String) {
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
private fun spliceValues(fragment: String, existingValues: String, marker: String): String {
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
