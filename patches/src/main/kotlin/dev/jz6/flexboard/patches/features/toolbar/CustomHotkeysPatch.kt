package dev.jz6.flexboard.patches.features.toolbar

import app.morphe.patcher.patch.bytecodePatch
import dev.jz6.flexboard.patches.shared.Constants.COMPATIBILITY_GBOARD
import dev.jz6.flexboard.patches.shared.basePatch

/**
 * Adds hotkey buttons to Gboard's toolbar, each typing a string the user chose.
 *
 * ## Why a sibling of Toolbar Buttons, not part of it
 *
 * The two features share the same access-point list and the same insertion target, but they
 * ask different questions of the user. Select-all/Copy/Paste have fixed texts cached from
 * Gboard's own strings; hotkeys have none until the user writes them, and each gets an optional
 * icon override too. Splitting lets a user drop the text actions without giving up their
 * snippets, or silence the hotkeys without losing the stock toolbar actions.
 *
 * Both this patch and [toolbarButtonsPatch] insert into the same list-splitting method and each
 * calls `ToolbarMerge.merge` with *its own* pair list. Because the merge counts every id it
 * does not know as an occupied position, the two compositions yield the same final order as if
 * they had been merged as one list.
 *
 * ## Which slots exist at all
 *
 * A slot that has never been filled has no block added to `pairs`, which is what makes it
 * disappear from the bar entirely. {@code labelAt} is called from the emitted block; when it
 * returns null the block branches past the whole button — the insertion index is a compile-time
 * constant shared under [MAX_CONST_4_VALUE] so the 12 slots stay reachable from one nibble.
 */
@Suppress("unused")
val customHotkeysPatch = bytecodePatch(
    name = "Custom Hotkeys",
    description = "Add up to ${HOTKEY_SLOT_COUNT} custom buttons to the toolbar above the " +
        "keyboard, each typing a string you write in Flexboard's settings. A slot stays " +
        "invisible until you fill it in. Reorder them alongside Gboard's own buttons in its " +
        "customise view.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(basePatch)

    execute {
        val builder = resolveAccessPointBuilder()
        emitToolbarButtons(builder, hotkeysEmission(builder))
    }
}

/** The number of hotkey slots. **Duplicated in `FlexboardSettingsActivity`. */
internal const val HOTKEY_SLOT_COUNT = 12

internal const val HOTKEY_ICON_1 = "0x7f080239" // star
internal const val HOTKEY_ICON_2 = "0x7f0806fc" // auto_awesome
internal const val HOTKEY_ICON_3 = "0x7f080215" // content_cut
internal const val HOTKEY_ICON_4 = "0x7f08074e" // check_box
internal const val HOTKEY_ICON_5 = "0x7f080733" // radio_button_unchecked
internal const val HOTKEY_ICON_6 = "0x7f080219" // share
internal const val HOTKEY_ICON_7 = "0x7f080239"
internal const val HOTKEY_ICON_8 = "0x7f0806fc"
internal const val HOTKEY_ICON_9 = "0x7f080215"
internal const val HOTKEY_ICON_10 = "0x7f08074e"
internal const val HOTKEY_ICON_11 = "0x7f080733"
internal const val HOTKEY_ICON_12 = "0x7f080219"

private val HOTKEY_ICONS = listOf(
    HOTKEY_ICON_1, HOTKEY_ICON_2, HOTKEY_ICON_3, HOTKEY_ICON_4, HOTKEY_ICON_5, HOTKEY_ICON_6,
    HOTKEY_ICON_7, HOTKEY_ICON_8, HOTKEY_ICON_9, HOTKEY_ICON_10, HOTKEY_ICON_11, HOTKEY_ICON_12,
)

/** `const/4` encodes a 4-bit signed value, so slot constants for slots 8+ use `const/16`. */
private const val MAX_CONST_4_VALUE = 7

private const val HOTKEY_CLASS = "Ldev/jz6/flexboard/extension/hotkey/Hotkey;"
private const val NEW_HOTKEY = "$HOTKEY_CLASS-><init>(I)V"
private const val HOTKEY_LABEL_AT = "$HOTKEY_CLASS->labelAt(I)Ljava/lang/String;"
private const val HOTKEY_ICON_AT = "$HOTKEY_CLASS->iconAt(II)I"

/**
 * The emission for the twelve hotkey slots. Each slot is guarded on the extension reporting it
 * occupied; an empty one branches past the whole button, which is what makes unfilled slots
 * disappear.
 */
private fun hotkeysEmission(builder: AccessPointBuilder): String {
    check(HOTKEY_ICONS.size == HOTKEY_SLOT_COUNT) {
        "${HOTKEY_ICONS.size} hotkey icons for $HOTKEY_SLOT_COUNT slots"
    }

    return (1..HOTKEY_SLOT_COUNT).joinToString("\n") { slot ->
        val absent = "flexboard_hotkey_${slot}_absent"
        val constSlot = if (slot <= MAX_CONST_4_VALUE) "const/4" else "const/16"
        """
            $constSlot v3, $slot
            invoke-static { v3 }, $HOTKEY_LABEL_AT
            move-result-object v2
            if-eqz v2, :$absent

            invoke-static { }, ${builder.newBuilder}
            move-result-object v1

            const-string v3, "flexboard_hotkey_$slot"
            invoke-virtual { v1, v3 }, ${builder.setId}
            invoke-interface { v0, v3 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
            move-result v3

            const v3, ${HOTKEY_ICONS[slot - 1]}
            $constSlot v4, $slot
            invoke-static { v4, v3 }, $HOTKEY_ICON_AT
            move-result v3
            invoke-virtual { v1, v3 }, ${builder.setIcon}

            const/4 v3, 0x0
            invoke-virtual { v1, v3 }, ${builder.setLabel}
            invoke-virtual { v1, v3 }, ${builder.setContentDescription}
            iput-object v2, v1, ${builder.labelField}
            iput-object v2, v1, ${builder.contentDescriptionField}

            new-instance v3, $HOTKEY_CLASS
            $constSlot v4, $slot
            invoke-direct { v3, v4 }, $NEW_HOTKEY
            invoke-virtual { v1, v3 }, ${builder.setAction}

            const/4 v3, 0x1
            invoke-static { v3 }, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
            move-result-object v3
            const-string v4, "closeAction"
            invoke-virtual { v1, v4, v3 }, ${builder.putExtra}

            invoke-virtual { v1 }, ${builder.build}
            move-result-object v2
            invoke-interface { v0, v2 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
            move-result v3
            :$absent
        """
    }
}
