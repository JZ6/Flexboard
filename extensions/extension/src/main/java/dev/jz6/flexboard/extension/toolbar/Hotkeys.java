package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.content.SharedPreferences;

import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * The preference contract Flexboard's hotkeys live by, read from Gboard's own store.
 *
 * <p>The store is written by the native settings screens ({@code flexboard_settings.xml} plus
 * one generated {@code flexboard_hotkey_N.xml} per slot) through Gboard's own preference
 * datastore, and read here from the same file the patches' other readers use — {@link
 * Preferences#of(Context)} mirrors the device-protected context Gboard itself resolves.
 *
 * <p>Sliders persist their value as a base-10 integer in a String; rows like the icon list
 * likewise store strings. This class is the one place both are parsed.
 *
 * <p>Nothing about a hotkey is knowable at patch time — the text is the user's, the label
 * derives from it, the icon is a preference, and whether the button exists at all is decided by
 * {@link #shown(Context, int)} at the moment the toolbar is built. All of it flows over the
 * Java boundary so the emitted smali stays free of the store's shapes.
 */
public final class Hotkeys {

    /** Must match {@code flexboard_hotkey_count} in the settings XML. */
    private static final String PREF_COUNT = "flexboard_hotkey_count";

    /** One key per slot, as {@code hotkey_<slot>_text.xml} generates. */
    private static final String PREF_TEXT_PREFIX = "flexboard_hotkey_";
    private static final String PREF_TEXT_SUFFIX = "_text";
    private static final String PREF_ICON_SUFFIX = "_icon";

    /** Landing count after the slider — MAX consistent with the emitted twelve button slots. */
    private static final int SLOT_COUNT = 12;

    /** The count slider's initial and out-of-box position: no hotkeys. */
    private static final int COUNT_DEFAULT = 0;

    /**
     * The toolbar-bar sanity cap on how many letters a button label consumes.
     *
     * <p>The label shows only in Gboard's customise list; on the bar the icon does the work.
     * A user's sentence-long hotkey still needs a handle in the list, so cut it there.
     */
    private static final int LABEL_MAX = 12;

    /** Cartesian-simple defaults: the first slot is a star, the second sparkles, and so on. */
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

    /**
     * Whether the slot earns a button on the bar right now.
     *
     * <p>Two conditions meet here: the slot is within the user's chosen count, and it carries
     * non-blank text. The count half is the user-facing slider; the blank-text half is the off
     * switch a user reaches for to drop a single slot without renumbering the rest.
     */
    public static boolean shown(Context context, int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            return false;
        }
        return slot <= count(context) && !textOf(context, slot).trim().isEmpty();
    }

    /**
     * The toolbar-build read of "how many slots does the user show"?
     *
     * <p>Stored by the settings screen's slider as a String; absent before the screen has ever
     * opened. Zero is the identity here: a missing or malformed value shows nothing.
     */
    public static int count(Context context) {
        String raw = Preferences.of(context).getString(PREF_COUNT, Integer.toString(COUNT_DEFAULT));
        try {
            int value = Integer.parseInt(raw);
            return Math.min(Math.max(value, 0), SLOT_COUNT);
        } catch (NumberFormatException ignored) {
            return COUNT_DEFAULT;
        }
    }

    /**
     * What the button types when its slot is tapped. Read fresh on each tap, not cached, so the
     * new text is live the moment the settings screen saves it.
     */
    public static String textOf(Context context, int slot) {
        return Preferences.of(context).getString(textKey(slot), "");
    }

    /**
     * The icon to draw on the slot's button.
     *
     * <p>Stored as the decimal string of a Gboard-bundled drawable id. Gboard's own icons are
     * travelled-light — every drawable the picker offers is one that's already in the APK, so
     * the hotkey's appearance works in any Gboard build. Falls back to a per-slot default when
     * the preference is absent, so the first boot needs no settings visit.
     */
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
     * What the customise list and screen readers call the button — the slot's text, clamped.
     * Also what the user reads it by on the bar in long-press.
     */
    public static String labelOf(Context context, int slot) {
        String text = textOf(context, slot).trim();
        return text.length() <= LABEL_MAX ? text : text.substring(0, LABEL_MAX - 1) + "…";
    }

    private static String textKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_TEXT_SUFFIX;
    }

    private static String iconKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_ICON_SUFFIX;
    }
}
