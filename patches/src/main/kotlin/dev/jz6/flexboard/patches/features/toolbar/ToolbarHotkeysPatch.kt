package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.HOTKEY_ID_PREFIX  // const visible to the checker
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.emitNativeHotkeys
import dev.jz6.flexboard.patches.shared.resolveAccessPointBuilder

/** The hotkey count slider's store key; the extension reads this back by hand. */
internal const val HOTKEY_COUNT_KEY = "flexboard_hotkey_count"

/** Never show hotkeys out of the box; the slider is the opt-in. */
internal const val HOTKEY_COUNT_DEFAULT = 0

/** The slider's floor. (The ceiling is HOTKEY_SLOTS in the shared registry.) */
internal const val HOTKEY_COUNT_MIN = 0

/**
 * Twelve toolbar buttons whose label, icon and action all come from settings — the patch emits
 * one conditional registration block per slot and the extension computes everything at
 * toolbar-build time. Registration, reorder and persistence are Gboard's own; the ids are
 * admitted natively by [toolbarSlotsPatch] widening the allowed-set array.
 */
@Suppress("unused")
val toolbarHotkeysPatch = bytecodePatch(
    name = "Toolbar Hotkeys",
    description = "Adds twelve configurable hotkey slots to Gboard's toolbar — each commits a " +
        "text of your choice on tap. Configured from the Flexboard settings row; off until the " +
        "slot count slider moves above zero.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)
    // The ids must be in the allowed set or the register call logs "Invalid access point" and
    // the buttons never reach the shown order.
    dependsOn(toolbarSlotsPatch)

    execute {
        emitNativeHotkeys(resolveAccessPointBuilder())
    }
}
