package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.selectedSettingsSections
import dev.jz6.flexboard.patches.shared.SettingsSection

/**
 * Six toolbar buttons whose label, icon and action all come from settings — the patch emits
 * one conditional registration block per slot and the extension computes everything at
 * toolbar-build time. Registration, reorder and persistence are Gboard's own; the ids are
 * admitted natively by [toolbarIdAdmissionPatch] widening the allowed-set array.
 */
@Suppress("unused")
val toolbarHotkeysPatch = bytecodePatch(
    name = "Toolbar Hotkeys",
    description = "Adds six configurable hotkey slots to Gboard's toolbar — each commits a " +
        "text of your choice on tap. A slot appears when its text is set; when cleared it hides " +
        "at the next toolbar rebuild (rotate, switch IME, or restart — there's no mid-session " +
        "un-register). Text and icon edits apply on the next keyboard open.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    // Redundant on paper -- toolbarIdAdmissionPatch below already pulls basePatch -- and kept anyway,
    // for two reasons. It is declared first, and the patcher recurses in declaration order, which
    // is what puts settingsScreenPatch's clear of the section registry ahead of the registration
    // at the end of this patch's execute. And a reader should not have to trace a transitive edge
    // to see that this patch needs the foundation.
    dependsOn(basePatch)
    // The ids must be in the allowed set or the register call logs "Invalid access point" and
    // the buttons never reach the shown order.
    dependsOn(toolbarIdAdmissionPatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeHotkeys(builder)
        emitHotkeyRefresh(builder)

        // Registered last, on purpose. A failing patch does not abort the run: the patcher records
        // the exception and moves on, and `settingsScreenPatch` — which did not fail — still
        // finalizes and reads this set. Registering before the emission above would ship the
        // Hotkeys rows and their drawables for a build whose bytecode never got the feature.
        selectedSettingsSections += SettingsSection.HOTKEYS
    }
}
