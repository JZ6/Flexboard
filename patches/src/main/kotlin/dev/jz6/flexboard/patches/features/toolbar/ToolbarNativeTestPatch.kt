package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.NativeToolbarButton
import dev.jz6.flexboard.patches.shared.basePatch
import dev.jz6.flexboard.patches.shared.emitNativeToolbarButtons

/**
 * Registers a single <b>Test</b> button through Gboard's own access-point registry.
 *
 * ## Why this exists
 *
 * Buttons injected by splicing the split method's list — the text actions, the custom hotkeys —
 * work visually but have never persisted through a customize session: the customize-write path
 * reads its commits out of the order manager's registered-provider list, and ours aren't in it.
 * On the next rebuild they snap back to the front regardless of where they were dragged.
 *
 * This patch is the proof the fix works: one button going through the same path native buttons
 * use, so everything Gboard's own customize machinery expects from a valid toolbar entry is
 * exercised, including save, reload, and reorder.
 *
 * ## How it goes native
 *
 * The full mechanism is documented on {@link NativeToolbarButton}. Read end-to-end:
 *
 *  - **Allowed-set:** a `string-array` resource holds every id Gboard accepts on the bar. The
 *    write filter is the only filter we face; the save side keeps whatever is handed to it. So
 *    the win is registering under a *dormant* id that's already allowed but never built by
 *    Gboard. `flag_editor` is the one used here; there are six candidates and any of them
 *    works.
 *  - **The registry:** `Lmlh.h` is an `ArrayMap` keyed by id. Its `g(mic, true)` method writes
 *    the definition *and* folds the id into the shown order in one call.
 *  - **The wiring:** `mhx.q(Runnable)` wraps a plain `java.lang.Runnable` as an
 *    `ACCESS_POINT_ACTION` key-press, which the IME dispatcher runs natively on tap. The
 *    runnable lives in the extension (so nothing obfuscated is named from Java) and is
 *    constructed with no arguments — see {@link TestAction}.
 *
 * Click commits "test" at the cursor — that is the whole point of the button as a test: if
 * the text appears, the click dispatch reached the extension without us having hooked any of
 * Gboard's callbacks.
 *
 * <b>Default-off.</b> Exposed in the patch list so the flow can be verified on device before
 * this shape replaces the split-list splice everywhere else.
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
                    icon = "0x7f080218",
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
