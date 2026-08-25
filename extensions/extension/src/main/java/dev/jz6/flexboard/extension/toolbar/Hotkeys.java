package dev.jz6.flexboard.extension.toolbar;

import android.content.ClipData;
import android.content.ClipboardManager;
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

    /** One key per slot, as the settings XML rows generate. */
    private static final String PREF_TEXT_PREFIX = "flexboard_hotkey_";
    private static final String PREF_TEXT_SUFFIX = "_text";
    private static final String PREF_ICON_SUFFIX = "_icon";

    /** Must match HOTKEY_SLOTS in the patch's ToolbarSlotsPatch and the XML's count maximum. */
    private static final int SLOT_COUNT = 12;

    /** Toolbar labels cap out visually at about this many characters; clamp by code point. */
    private static final int LABEL_MAX = 12;

    /**
     * Default icon per slot: the Flexboard vector pack, resolved by NAME at runtime
     * (getIdentifier), so aapt2's numbering never leaves the device it was baked on. Names match
     * the symbol each holds — slot order mirrors HOTKEY_DEFAULT_SYMBOLS in
     * SettingsScreenPatch.kt, locked in step by the constants checker.
     */
    private static final String[] DEFAULT_ICON_NAMES = new String[] {
        "flexboard_icon_alternate_email",
        "flexboard_icon_password",
        "flexboard_icon_phone_enabled",
        "flexboard_icon_local_post_office",
        "flexboard_icon_home_pin",
        "flexboard_icon_work",
        "flexboard_icon_favorite",
        "flexboard_icon_kid_star",
        "flexboard_icon_credit_card",
        "flexboard_icon_hexagon",
        "flexboard_icon_hive",
        "flexboard_icon_sports_soccer",
    };

    private Hotkeys() {}

    /**
     * The toolbar-built gate: slot in range, text set. Slots start empty and invisible;
     * typing a text is how one appears, clearing it is how it goes away.
     */
    public static boolean shown(Context context, int slot) {
        if (slot < 1 || slot > SLOT_COUNT) {
            return false;
        }
        return !textOf(context, slot).trim().isEmpty();
    }

    /** The text the slot's tap commits. */
    public static String textOf(Context context, int slot) {
        return Preferences.of(context).getString(textKey(slot), "");
    }

    /**
     * The drawable id the slot renders with. The stored token is resolved by name (a
     * flexboard_* vector) first; a plain decimal token still works, which is how blobs from
     * the bundled-id era (dev.7 and earlier) degrade gracefully rather than blanking.
     */
    public static int iconOf(Context context, int slot) {
        String raw = Preferences.of(context).getString(iconKey(slot), "");
        int resolved = resolveIcon(context, raw);
        if (resolved != 0) {
            return resolved;
        }
        return resolveIcon(context, DEFAULT_ICON_NAMES[slot - 1]);
    }

    /** name -> getIdentifier, digits -> as-is, anything else -> 0 (caller falls back). */
    private static int resolveIcon(Context context, String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int id = Integer.parseInt(token.trim());
            return id > 0 ? id : 0;
        } catch (NumberFormatException ignored) {
            // not decimal: treat as a drawable name
        }
        return context.getResources().getIdentifier(token.trim(), "drawable", context.getPackageName());
    }

    /** What the export row carries per slot: the raw token as stored, default name when unset. */
    private static String iconTokenOf(Context context, int slot) {
        String raw = Preferences.of(context).getString(iconKey(slot), "");
        return raw.isEmpty() ? DEFAULT_ICON_NAMES[slot - 1] : raw;
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

    /** First line of a blob; guards against accepting any old clipboard content as config. */
    private static final String BLOB_VERSION = "flexboard-hotkeys v1";

    /**
     * Writes the current hotkey set to the clipboard as a blob and says what happened. Called
     * from the settings screen's Copy row.
     */
    public static String exportToClipboard(Context context) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return "no clipboard service";
        }
        String blob = serialize(context);
        clipboard.setPrimaryClip(ClipData.newPlainText("Flexboard hotkeys", blob));
        int occupied = countOccupied(context);
        return occupied == 0 ? "copied (no slots set)" : "copied " + occupied + " slots";
    }

    /**
     * Reads the clipboard and, if it carries a blob, applies it. Called from the Paste row.
     * Anything that is not one of our exports is refused without touching the store.
     */
    public static String importFromClipboard(Context context) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return "clipboard is empty";
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "clipboard is empty";
        }
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null || !text.toString().startsWith(BLOB_VERSION)) {
            return "clipboard does not hold a Flexboard export";
        }
        boolean applied = applyBlob(context, text.toString());
        if (!applied) {
            return "export is malformed — nothing changed";
        }
        return "imported " + countOccupied(context) + " slots";
    }

    private static int countOccupied(Context context) {
        int occupied = 0;
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            if (!textOf(context, slot).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    /** One line per occupied slot: slot number, tab, escaped text, tab, icon token (a
     * drawable name today, a decimal id on blobs exported by dev.7 and earlier). */
    private static String serialize(Context context) {
        StringBuilder out = new StringBuilder(BLOB_VERSION).append('\n');
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            String text = textOf(context, slot);
            if (text.isEmpty()) {
                continue;
            }
            out.append(slot).append('\t')
                .append(escape(text)).append('\t')
                .append(iconTokenOf(context, slot))
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
        String[] icons = new String[SLOT_COUNT + 1];
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
            String icon = fields[2].trim();
            if (icon.isEmpty()) {
                return false;
            }
            texts[slot] = unescape(fields[1]);
            icons[slot] = icon;
        }
        SharedPreferences.Editor editor = Preferences.of(context).edit();
        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            String text = texts[slot];
            editor.putString(textKey(slot), text != null ? text : "");
            if (texts[slot] != null && icons[slot] != null) {
                editor.putString(iconKey(slot), icons[slot]);
            }
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
