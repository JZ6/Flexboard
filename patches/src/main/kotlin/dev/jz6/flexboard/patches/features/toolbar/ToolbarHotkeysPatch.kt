package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.HOTKEY_SLOTS
import dev.jz6.flexboard.patches.shared.admitFlexboardToolbarIds
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.emitNativeHotkeys
import dev.jz6.flexboard.patches.shared.resolveAccessPointBuilder

/**
 * The hotkey count slider's preference key and bounds.
 *
 * **Duplicated in three places that cannot share**: here (the smali's key, interpolated into the
 * registered id list comment chains), `flexboard_settings.xml` (the slider's attributes), and
 * `Hotkeys.java` (the reader). `check_shared_constants.py` fails the build if any drift apart.
 */
internal const val HOTKEY_COUNT_KEY = "flexboard_hotkey_count"
internal const val HOTKEY_COUNT_DEFAULT = 0
internal const val HOTKEY_COUNT_MIN = 0

/**
 * Twelve user-named hotkey slots on Gboard's toolbar, each typing whatever text the user set.
 *
 * ## Design
 *
 * Each slot is a full Gboard access point — registered through the same builder every stock
 * button uses — whose properties are all read from preferences at toolbar-build time by the
 * extension's `Hotkeys` class:
 *
 *  - the *id* is fixed (`flexboard_hotkey_1`…`flexboard_hotkey_12`), which is what makes the
 *    button's position in the user's chosen order stable across builds and reruns;
 *  - the *icon* is a Gboard-bundled drawable id stored per slot, so it travels with no bundled
 *    art of its own and follows Gboard's own theming;
 *  - the *label* is the slot's text clamped — shown in Gboard's customise list and read by
 *    screen readers;
 *  - the *action* is a `Hotkey` Runnable handing the slot's text to the input connection.
 *
 * Everything else is unchanged from stock: the string-keyed preference writes the settings
 * screens produce, the fold-by-order-id persistence, and the customise editor's drag round-trip
 * — all of it works because the read filter is widened to admit the `flexboard_` prefix (see
 * [admitFlexboardToolbarIds]).
 *
 * A slot with no text earns no button (`Hotkeys.shown` is the gate), and the count slider in
 * the Flexboard settings screen bounds how many of the twelve slots are visible at once.
 */
internal val toolbarHotkeysPatch = bytecodePatch(
    name = "Toolbar Hotkeys",
    description = "Adds up to twelve customisable hotkey buttons to Gboard's toolbar, " +
        "each typing text the user sets in Flexboard's settings.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    dependsOn(basePatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeHotkeys(builder)
        admitFlexboardToolbarIds()
    }
}
