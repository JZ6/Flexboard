package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.HOTKEY_SLOTS
import dev.jz6.flexboard.patches.shared.assertWellFormedXml
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.spliceValues

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
 */
@Suppress("unused")
val toolbarSlotsPatch = resourcePatch(
    name = "Toolbar Slots",
    description = "Admit Flexboard's toolbar button ids natively, widening Gboard's own " +
        "allowed-set array. No other change; reorder and persistence stay stock.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    finalize { widenAllowedIdSet() }
}

/** The slot count lives in shared/ToolbarRegistry.kt — the registry emits per-slot blocks and owns it. */

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
