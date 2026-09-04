package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Adds **Select all**, **Copy** and **Paste** buttons to Gboard's toolbar.
 *
 * ## How this patch went native
 *
 * This used to splice the three buttons into the split method's list — the patch that hooked
 * `Lmlh;->C(Ljava/util/List;)V` (the bar-vs-overflow split) and added our access points in
 * front of the list at entry. Visually right; persistent never. `Customize` writes the toolbar
 * order back from what it sees in the registered-provider list, which is the `ArrayMap` on the
 * bar controller — ours weren't in it, so any drag-through-customize was forgotten the moment
 * the bar rebuilt itself (theme change, folding a foldable, screen rotation, the next IME
 * session).
 *
 * What makes a toolbar button real is registration:
 *
 *  - its id sits in an allowed set (a `string-array` the order manager reads on startup), and
 *  - its definition (icon/label/action) sits in the controller's registry at
 *    `Lmlh;->g(mic, true)` time.
 *
 * The three below are registered with dormant member ids from the allowed set — built dex-side
 * never, listed resource-side always. Customize sees them natively, the pref-write keeps their
 * position, drag-to-drawer persists.
 *
 * ## The builder is derived, not named
 *
 * The access-point builder is R8-renamed every Gboard build; nothing about it is named anywhere
 * in dex. The derivation reads it the way Gboard names itself — the completeness-mask strings
 * that `build()` fails with — and falls out of that into the specific setter / pass-through
 * fields this emission needs. See `shared/ToolbarBuilder.kt` for the whole walk.
 *
 * The buttons' own click runs through the existing `TextAction` in the extension: the builder's
 * Runnable setter wraps it as an `ACCESS_POINT_ACTION` key-data, which the IME dispatcher runs
 * natively on tap.
 */
@Suppress("unused")
val toolbarButtonsPatch = bytecodePatch(
    name = "Text Action Buttons",
    description = "Add Select all, Copy and Paste buttons to the toolbar above the keyboard, " +
        "so each is one tap instead of opening Gboard's text editing panel first. Registered " +
        "natively, so drag-to-reorder through the toolbar customize page persists. These " +
        "three compete for the five icons Gboard's toolbar holds; Bigger Toolbar raises " +
        "that ceiling if you run the hotkeys as well.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(basePatch)

    // Admits this patch's three ids into Gboard's allowed-set array. Without it the register call
    // logs "Invalid access point" and drops them — the same dependency, for the same reason, that
    // Toolbar Hotkeys carries.
    dependsOn(toolbarIdAdmissionPatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitNativeToolbarButtons(builder, BUTTONS)
    }
}

// -------------------------------------------------------------------------------------------
// The buttons
// -------------------------------------------------------------------------------------------

/**
 * Flexboard's ordinals for the three actions.
 *
 * **Duplicated in `TextAction`**, which maps them to the framework's context-menu ids. They cannot
 * be shared: that class is compiled into the extension DEX, a separate Gradle module with no
 * dependency on the patches. `check_shared_constants.py` is what keeps the two sides honest.
 */
internal const val TEXT_ACTION_SELECT_ALL = 0
internal const val TEXT_ACTION_COPY = 1
internal const val TEXT_ACTION_PASTE = 2

private const val TEXT_ACTION_CTOR = "Ldev/jz6/flexboard/extension/textaction/TextAction;-><init>(I)V"

/**
 * The three buttons, in the order they appear on a first-run bar.
 *
 * Resource ids (labels and icons) are Gboard's own strings and drawables, already present
 * because its text editing panel shows them — same labels, so already translated into every
 * language Gboard ships. The icons (Material's `select_all`, `content_copy` and
 * `content_paste`) come from the icon audit in `tools/apk/glyphs.py`: Gboard bundles them and
 * draws none of them.
 *
 * The toolbar ids are Flexboard's own, admitted into Gboard's allowed-set array by
 * [toolbarIdAdmissionPatch] — the same mechanism the hotkey slots use. See `ToolbarCanvas.kt` for why
 * the id has to be in that set at all.
 *
 * These three used to squat on ids Gboard ships in the allowed set but never registers a handler
 * for: `editor_info` for select-all, `undo_cooperative` for copy, `muse_toggle_playground_ap` for
 * paste. That worked, and it was a bet on Google leaving all three dormant in a build nobody here
 * controls. The failure mode was the bad kind — the day one of them becomes a real access point,
 * our registration clobbers its entry in the controller's map rather than failing a patch
 * assertion, and the symptom is somebody else's button quietly doing our thing. Minting our own
 * costs three members of an array we were already widening for hotkeys.
 */
private val BUTTONS = listOf(
    NativeToolbarButton(
        id = "flexboard_select_all",
        icon = "0x7f080218",
        labelRes = "0x7f140576",
        actionCtor = TEXT_ACTION_CTOR,
        actionArgs = listOf(TEXT_ACTION_SELECT_ALL),
    ),
    NativeToolbarButton(
        id = "flexboard_copy",
        icon = "0x7f080214",
        labelRes = "0x7f140560",
        actionCtor = TEXT_ACTION_CTOR,
        actionArgs = listOf(TEXT_ACTION_COPY),
    ),
    NativeToolbarButton(
        id = "flexboard_paste",
        icon = "0x7f080217",
        labelRes = "0x7f140570",
        actionCtor = TEXT_ACTION_CTOR,
        actionArgs = listOf(TEXT_ACTION_PASTE),
    ),
)
