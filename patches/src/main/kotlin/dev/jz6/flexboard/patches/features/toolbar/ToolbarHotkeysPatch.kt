package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.HOTKEY_ID_PREFIX  // const visible to the checker
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.emitHotkeyRefresh
import dev.jz6.flexboard.patches.shared.emitNativeHotkeys
import dev.jz6.flexboard.patches.shared.resolveAccessPointBuilder

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
        "text of your choice on tap. A slot appears when its text is set; when cleared it hides " +
        "at the next toolbar rebuild (rotate, switch IME, or restart — there's no mid-session " +
        "un-register). Text and icon edits apply on the next keyboard open.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)
    // The ids must be in the allowed set or the register call logs "Invalid access point" and
    // the buttons never reach the shown order.
    dependsOn(toolbarSlotsPatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeHotkeys(builder)
        emitHotkeyRefresh(builder)
    }
}
