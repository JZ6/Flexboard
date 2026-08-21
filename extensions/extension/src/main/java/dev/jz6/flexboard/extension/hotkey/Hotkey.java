package dev.jz6.flexboard.extension.hotkey;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * The action behind Flexboard's hotkey buttons — one tap types a string the user chose.
 *
 * <p>Structurally this is the same button as {@code TextAction}: Gboard hands an access point an
 * arbitrary {@link Runnable} and runs it on tap. Only the action differs — {@code commitText}
 * instead of a context-menu id — and every type crossing into here is a framework type.
 *
 * <p><b>What is different is that nothing about a hotkey is known at patch time.</b> The text is
 * the user's, the label is derived from it, and whether the button exists at all depends on whether
 * they have filled the slot in. So the patch emits six fixed slots and asks this class, at the
 * moment the toolbar is built, which of them are occupied — see {@link #labelAt(int)}.
 *
 * <p><b>Two different reads, deliberately.</b> {@link #labelAt(int)} runs when Gboard rebuilds the
 * access-point list, so a renamed hotkey shows its new label the next time the keyboard opens.
 * {@link #run()} reads the snippet again at tap time, so an <i>edited</i> snippet types correctly
 * straight away even while the label beside it is still the old one. Reading once and caching would
 * make the text as stale as the label, which is the worse of the two.
 */
public final class Hotkey implements Runnable {

    /**
     * The preference key for a slot.
     *
     * <p>Public because the settings screen writes what this reads. They are the same file and the
     * same key by construction rather than by two literals agreeing — the mistake this whole
     * project has a CI check for.
     */
    public static String keyFor(int slot) {
        return KEY_PREFIX + slot;
    }

    private static final String KEY_PREFIX = "flexboard_hotkey_";

    /** Preference key for a slot's custom icon drawable resource name. */
    private static String iconKey(int slot) {
        return KEY_PREFIX + slot + "_icon";
    }

    /**
     * The resource id for a slot's icon, resolved at toolbar-build time.
     *
     * <p>Each slot has a default — a Gboard-bundled drawable whose id the patch passes as
     * {@code defaultResId}. A slot may override it with a Flexboard-bundled drawable by storing
     * the drawable's resource name in preferences (key: {@code flexboard_hotkey_<slot>_icon}).
     * Slot 1 defaults to {@code flexboard_hotkey_icon_1} when no preference is set.
     *
     * <p>The name is resolved via {@link android.content.res.Resources#getIdentifier}, which is
     * why the drawable is written into the APK at patch time — aapt2 compiles it and enters it in
     * the resource table under that name.
     */
    public static int iconAt(int slot, int defaultResId) {
        SharedPreferences preferences = ImeService.preferences();
        Context context = ImeService.get();
        if (preferences == null || context == null) {
            return defaultResId;
        }
        String name = preferences.getString(iconKey(slot), null);
        if (name == null) {
            name = switch (slot) {
                case 1 -> "flexboard_hotkey_icon_1";
                case 2 -> "flexboard_hotkey_icon_2";
                case 3 -> "flexboard_hotkey_icon_3";
                case 4 -> "flexboard_hotkey_icon_4";
                case 5 -> "flexboard_hotkey_icon_5";
                case 6 -> "flexboard_hotkey_icon_6";
                case 7 -> "flexboard_hotkey_icon_7";
                case 8 -> "flexboard_hotkey_icon_8";
                case 9 -> "flexboard_hotkey_icon_9";
                case 10 -> "flexboard_hotkey_icon_10";
                case 11 -> "flexboard_hotkey_icon_11";
                case 12 -> "flexboard_hotkey_icon_12";
                default -> null;
            };
            if (name == null) {
                return defaultResId;
            }
        }
        int resId = context.getResources().getIdentifier(
                name, "drawable", context.getPackageName());
        return resId != 0 ? resId : defaultResId;
    }

    /**
     * How much of the snippet becomes the button's name.
     *
     * <p>The label is the user's own text, which is what makes six otherwise identical shapes
     * tellable apart in Gboard's customise list. It is also unbounded — a mail signature would
     * otherwise become a mail-signature-long label — so it is cut here rather than trusting
     * whatever the list does with a very long string.
     */
    private static final int LABEL_MAX = 12;

    private static final String ELLIPSIS = "…";

    private final int slot;

    /** Required by the patch, which emits {@code new-instance} plus this constructor. */
    public Hotkey(int slot) {
        this.slot = slot;
    }

    /**
     * The button name for a slot, or an empty string if the slot is untouched.
     *
     * <p>Called from patched bytecode while the toolbar is being built. Empty slots still get
     * constructed and registered — the "no button" logic lives in the merge, which reads the
     * label off the freshly built access point and skips the empty ones. That keeps the patch
     * emission free of labels, which is what lets two toolbar patches insert into the same
     * split method without tripping Morphe's label bookkeeping.
     *
     * <p>Whitespace counts as empty. A slot holding a space would otherwise be a button with an
     * invisible name that types nothing anyone can see.
     *
     * @return the trimmed first-line label, or an empty string when the slot is unset/blank.
     */
    public static String labelAt(int slot) {
        String text = textAt(slot);
        if (text == null) {
            return "";
        }

        // First line only. A multi-line snippet is perfectly reasonable to type and a terrible
        // thing to hand to a single-line label.
        int newline = text.indexOf('\n');
        String line = (newline < 0 ? text : text.substring(0, newline)).trim();
        if (line.isEmpty()) {
            line = text.trim();
        }

        return line.length() <= LABEL_MAX ? line : line.substring(0, LABEL_MAX) + ELLIPSIS;
    }

    /** True when a slot has a non-empty label, as a merge-side filter for empty slots. */
    public static boolean hasContent(int slot) {
        return !labelAt(slot).isEmpty();
    }

    /** The stored snippet for a slot, or {@code null} when unset, blank, or stored as the wrong
     *  type. A backup restore or a hand-edited file can put an int where a String belongs, and
     *  letting that crash the toolbar build is not an option. */
    private static String textAt(int slot) {
        SharedPreferences preferences = ImeService.preferences();
        if (preferences == null) {
            return null;
        }
        try {
            String text = preferences.getString(keyFor(slot), null);
            if (text == null || text.trim().isEmpty()) {
                return null;
            }
            return text;
        } catch (ClassCastException wrongType) {
            return null;
        }
    }

    @Override
    public void run() {
        // Read again rather than reusing whatever the label was built from: the button may have
        // been on the bar since before the user last edited this slot.
        String text = textAt(slot);
        if (text == null) {
            return;
        }

        InputConnection inputConnection = ImeService.connection();
        if (inputConnection == null) {
            return;
        }

        // 1 leaves the cursor after the inserted text, which is what typing does.
        inputConnection.commitText(text, 1);
    }
}
