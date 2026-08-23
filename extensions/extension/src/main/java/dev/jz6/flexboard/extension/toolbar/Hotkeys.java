package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.content.SharedPreferences;

import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * The hotkeys' store contract, read by the emitted smali on every toolbar build.
 *
 * <p>Every slot has two keys — {@code flexboard_hotkey_N_text} (the string the tap commits) and
 * {@code flexboard_hotkey_N_icon} (a Gboard-bundled drawable id as a decimal string) — plus the
 * category-wide {@code flexboard_hotkey_count} slider: slots above the count never register, so
 * the bar never shows more than asked for. Slot N hiding also skips registration, so reordering
 * stays Gboard's (docs/toolbar-access-points.md).
 *
 * <p>All values are strings because the screen's rows persist through Gboard's androidx port,
 * which writes them as text; readers here parse defensively and fall back to the default the
 * patch itself would have staged.
 *
 * <p>Icons rotate through the bundled-icon table by slot, so an untouched install already has
 * twelve distinguishable buttons; the table ids come from the glyphs audit and are pinned in
 * preflight by path signature.
 */
public final class Hotkeys {

    /** Must match HOTKEY_COUNT_KEY in the patch. */
    private static final String PREF_COUNT = "flexboard_hotkey_count";

    /** One key per slot, as the settings XML rows generate. */
    private static final String PREF_TEXT_PREFIX = "flexboard_hotkey_";
    private static final String PREF_TEXT_SUFFIX = "_text";
    private static final String PREF_ICON_SUFFIX = "_icon";

    /** Must match HOTKEY_SLOTS in the patch's ToolbarSlotsPatch and the XML's count maximum. */
    private static final int SLOT_COUNT = 12;

    /** The count slider's out-of-box position: no hotkeys. */
    private static final int COUNT_DEFAULT = 0;

    /** Toolbar labels cap out visually at about this many characters; clamp by code point. */
    private static final int LABEL_MAX = 12;

    /**
     * Per-slot icons when the user has never picked one: the first slot is a star, the second
     * sparkles, and so on. Gboard-bundled Material drawable ids, chosen from the twenty the
     * glyphs audit catalogued.
     */
    private static final int[] DEFAULT_ICONS = new int[] {
        2131231289, // star
        2131232508, // sparkles
        2131232290, // check circle
        2131232291, // done
        2131231252, // copy
        2131231255, // paste
        2131231257, // share
        2131232548, // open-in-new
        2131232578, // spellcheck
        2131231744, // keyboard
        2131232531, // help-outline
        2131231690, // visibility off
    };

    private Hotkeys() {}

    /** The toolbar-built gate: slot in range, within the count, text set. */
    public static boolean shown(Context context, int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            return false;
        }
        return slot <= count(context) && !textOf(context, slot).trim().isEmpty();
    }

    /** The count slider, clamped to the emitted slot range. */
    public static int count(Context context) {
        String raw = Preferences.of(context).getString(PREF_COUNT, Integer.toString(COUNT_DEFAULT));
        try {
            int value = Integer.parseInt(raw);
            return Math.min(Math.max(value, 0), SLOT_COUNT);
        } catch (NumberFormatException ignored) {
            return COUNT_DEFAULT;
        }
    }

    /** The text the slot's tap commits. */
    public static String textOf(Context context, int slot) {
        return Preferences.of(context).getString(textKey(slot), "");
    }

    /** The drawable id the slot renders with; the bundled default when unset or unparsable. */
    public static int iconOf(Context context, int slot) {
        String fallback = Integer.toString(DEFAULT_ICONS[slot - 1]);
        String raw = Preferences.of(context).getString(iconKey(slot), fallback);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return DEFAULT_ICONS[slot - 1];
        }
    }

    /**
     * What sits on the button. Empty means the icon carries the button; longer text is clamped
     * at {@link #LABEL_MAX} code points with an ellipsis — a label that overflows the bar's
     * measure pass shifts every row next to it.
     */
    public static String labelOf(Context context, int slot) {
        String text = textOf(context, slot).trim();
        if (text.codePointCount(0, text.length()) <= LABEL_MAX) {
            return text;
        }
        int cut = text.offsetByCodePoints(0, LABEL_MAX - 1);
        return text.substring(0, cut) + "…";
    }

    private static String textKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_TEXT_SUFFIX;
    }

    private static String iconKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_ICON_SUFFIX;
    }
}
