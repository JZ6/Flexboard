package dev.jz6.flexboard.extension.hotkey;

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
     * The button name for a slot, or {@code null} if the slot is empty.
     *
     * <p>Called from patched bytecode while the toolbar is being built, and <b>null is what makes
     * an empty slot vanish</b>: the emitted block branches past the whole button on null, so an
     * untouched Flexboard puts no hotkeys on the bar at all and each one appears as it is filled
     * in. That is the off switch for this feature, and it is per-button.
     *
     * <p>Whitespace counts as empty. A slot holding a space would otherwise be a button with an
     * invisible name that types nothing anyone can see.
     */
    public static String labelAt(int slot) {
        String text = textAt(slot);
        if (text == null) {
            return null;
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

    /** The stored snippet for a slot, or {@code null} when unset or blank. */
    private static String textAt(int slot) {
        SharedPreferences preferences = ImeService.preferences();
        if (preferences == null) {
            return null;
        }
        String text = preferences.getString(keyFor(slot), null);
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        return text;
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
