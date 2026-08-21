package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.NativeToolbarButton
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.emitNativeToolbarButtons
import dev.jz6.flexboard.patches.shared.resolveAccessPointBuilder

/**
 * Registers a single <b>Test</b> button through Gboard's own access-point registry.
 *
 * A canary for the native-registration shape. If this button appears, draggable through
 * Customize and persists across a rebuild, the registration path is healthy; if it does not,
 * the *shared* helper behind every toolbar patch is the thing to look at. Keeping it as a
 * separate off-by-default patch means a user can isolate that layer without dragging in the
 * user-facing buttons at the same time.
 *
 * The full mechanism is documented on `NativeToolbarButton` in `shared/ToolbarRegistry.kt`.
 * This patch is a 15-line call into it.
 */
@Suppress("unused")
val toolbarNativeTestPatch = bytecodePatch(
    name = "Toolbar Native Test",
    description = "Add a 'Test' button to the toolbar through Gboard's own access-point " +
        "registry so drag reorder and persistence work natively. Writes 'test' at the cursor " +
        "on tap. Architectural proof-of-concept; off by default.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(basePatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeToolbarButtons(
            builder,
            listOf(
                NativeToolbarButton(
                    id = "flag_editor",
                    // Material auto_awesome — bundled and never drawn by Gboard itself, so the
                    // canary is visually distinct from Select all (which uses `select_all`).
                    icon = "0x7f0806fc",
                    labelLiteral = "Test",
                    actionCtor = TEST_ACTION_CTOR,
                ),
            ),
        )
    }
}

/**
 * The descriptor shared across patch and extension. `check_shared_constants.py` asserts that
 * the constructor it names exists on the extension class — a stale reference here compiles
 * clean and breaks on the phone, so the assertion is the whole point.
 */
private const val TEST_ACTION_CTOR = "Ldev/jz6/flexboard/extension/toolbar/TestAction;-><init>()V"
