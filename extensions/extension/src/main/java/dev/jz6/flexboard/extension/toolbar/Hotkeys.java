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
     * glyphs audit catalogued. Mirrored as hex literals by {@code hotkey_default_icons} in
     * preflight — the two are edited together.
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
        // The one funnel every accessor passes through on a toolbar rebuild — the only place
        // import/export needs to watch. Cheap: two store reads.
        maybeApplyBlob(context);
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

    // ---------------------------------------------------------------------------------------------
    // Import / export — the whole hotkey state as one pasteable string
    // ---------------------------------------------------------------------------------------------

    /** The single settings row carrying the serialized slots in and out. */
    private static final String PREF_BLOB = "flexboard_hotkey_blob";

    /** Records which blob we already produced/consumed, so a paste is distinguishable. */
    private static final String PREF_BLOB_APPLIED = "flexboard_hotkey_blob_applied";

    /** First line of a blob; guards against accepting any old text field content as config. */
    private static final String BLOB_VERSION = "flexboard-hotkeys v1";

    /**
     * Keeps the blob row in step with the slots and digests a paste. Invoked from {@link #shown}
     * — toolbar builds happen on every keyboard-open, so an import lands before the user can
     * plausibly miss it, and a stale export refreshes before anyone copies an old one.
     */
    public static void maybeApplyBlob(Context context) {
        SharedPreferences preferences = Preferences.of(context);
        String blob = preferences.getString(PREF_BLOB, "");
        String applied = preferences.getString(PREF_BLOB_APPLIED, "");
        if (!blob.equals(applied)) {
            // The blob field changed without going through us: a paste. Only claim it if it's
            // one of ours; a first run or a garbage paste stops being the blob and becomes
            // a fresh export of the current state, which is the clearest feedback we can give.
            if (!blob.startsWith(BLOB_VERSION)) {
                writeBlob(context);
                return;
            }
            if (applyBlob(context, blob)) {
                writeBlob(context);
            }
            return;
        }
        if (blob.isEmpty() || !blob.equals(serialize(context))) {
            // Slots moved since the last export; refresh so a long-press copy keeps up.
            writeBlob(context);
        }
    }

    private static void writeBlob(Context context) {
        String blob = serialize(context);
        Preferences.of(context).edit()
            .putString(PREF_BLOB, blob)
            .putString(PREF_BLOB_APPLIED, blob)
            .apply();
    }

    /** One line per occupied slot: slot number, tab, escaped text, tab, icon resource id. */
    private static String serialize(Context context) {
        StringBuilder out = new StringBuilder(BLOB_VERSION).append('\n');
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            String text = textOf(context, slot);
            if (text.isEmpty()) {
                continue;
            }
            out.append(slot).append('\t')
                .append(escape(text)).append('\t')
                .append(iconOf(context, slot))
                .append('\n');
        }
        return out.toString();
    }

    /**
     * Strict-any-bad-abort parse of a pasted blob: anything wrong — a field count, a number, a
     * slot twice — does not touch the store at all, and the next call replaces the bad paste
     * with a fresh export instead of leaving a hint of it behind.
     */
    private static boolean applyBlob(Context context, String blob) {
        String[] lines = blob.split("\n");
        if (lines.length < 1 || !lines[0].trim().equals(BLOB_VERSION)) {
            return false;
        }
        String[] texts = new String[SLOT_COUNT + 1];
        int[] icons = new int[SLOT_COUNT + 1];
        int highestSlot = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                return false;
            }
            int slot;
            try {
                slot = Integer.parseInt(fields[0].trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (slot < 1 || slot > SLOT_COUNT || texts[slot] != null) {
                return false;
            }
            int icon;
            try {
                icon = Integer.parseInt(fields[2].trim());
            } catch (NumberFormatException e) {
                return false;
            }
            texts[slot] = unescape(fields[1]);
            icons[slot] = icon;
            if (slot > highestSlot) {
                highestSlot = slot;
            }
        }
        SharedPreferences.Editor editor = Preferences.of(context).edit();
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            String text = texts[slot];
            editor.putString(textKey(slot), text != null ? text : "");
            editor.putString(iconKey(slot), Integer.toString(texts[slot] != null ? icons[slot] : 0));
        }
        // Count only ever rises on import — clearing the user's slider because one paste
        // happened is worse than leaving it. Paste-imported slots beyond the count stay hidden
        // the way an untouched slot is.
        if (highestSlot > count(context)) {
            editor.putString(PREF_COUNT, Integer.toString(highestSlot));
        }
        editor.apply();
        return true;
    }

    /** Escape for one scalar field; blobs aren't a binary format, just careful about the delimiters. */
    private static String escape(String text) {
        // Order matters: backslash first, so a literal "\\t" in the text survives as two chars.
        return text
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    /**
     * The inverse of {@link #escape}, tolerant on anything it can't read: an unknown escape
     * comes through as a literal backslash plus its character, so no blob is ever rejected over
     * a user who typed one by hand into a slot before exporting.
     */
    private static String unescape(String field) {
        StringBuilder out = new StringBuilder(field.length());
        int i = 0;
        while (i < field.length()) {
            int c = field.codePointAt(i);
            if (c == '\\' && i + 1 < field.length()) {
                char next = field.charAt(i + 1);
                if (next == 't') { out.append('\t'); i += 2; continue; }
                if (next == 'n') { out.append('\n'); i += 2; continue; }
                if (next == 'r') { out.append('\r'); i += 2; continue; }
                if (next == '\\') { out.append('\\'); i += 2; continue; }
            }
            out.appendCodePoint(c);
            i += Character.charCount(c);
        }
        return out.toString();
    }
}
