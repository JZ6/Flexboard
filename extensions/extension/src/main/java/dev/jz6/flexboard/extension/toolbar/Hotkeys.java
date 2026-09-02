package dev.jz6.flexboard.extension.toolbar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;

import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * The hotkeys' store contract, read by the emitted smali on every toolbar build.
 *
 * <p>Every slot has two keys — {@code flexboard_hotkey_N_text} (the string the tap commits) and
 * {@code flexboard_hotkey_N_icon} (a bundled vector's drawable <i>name</i>; decimal ids from
 * dev.7-and-earlier exports still parse, validated against this build before use). A slot
 * registers only when its text is set — an unused slot draws nothing — and clearing the text
 * takes the button away at the next bar-controller rebuild (rotation, an IME switch, a restart):
 * the registry has no mid-session un-register, while text and icon edits go live on the next
 * keyboard open through the start-input re-registration. Slot N hiding skips registration, so
 * reordering stays Gboard's (docs/toolbar-access-points.md).
 *
 * <p>All values are strings because the screen's rows persist through Gboard's androidx port,
 * which writes them as text; readers here parse defensively and fall back to the default the
 * patch itself would have staged.
 *
 * <p>Each slot's default icon is a fixed member of the bundled Flexboard pack, so an untouched
 * install already shows six distinguishable buttons. The settings screen's single row per
 * slot opens a composite editor (text field + grid over the same pack, chosen by name), so a
 * preference written by the dialog, by an import, or by neither resolves through one path.
 */
public final class Hotkeys {

    /** One key per slot, as the settings XML rows generate. */
    private static final String PREF_TEXT_PREFIX = "flexboard_hotkey_";
    private static final String PREF_TEXT_SUFFIX = "_text";
    private static final String PREF_ICON_SUFFIX = "_icon";

    /** Must match HOTKEY_SLOTS in the patch's ToolbarSlotsPatch and the XML's count maximum. */
    private static final int SLOT_COUNT = 6;

    /** The slot count, for screens that iterate every row. */
    public static int slotCount() {
        return SLOT_COUNT;
    }

    /** Toolbar labels cap out visually at about this many characters; clamp by code point. */
    private static final int LABEL_MAX = 12;

    /**
     * The Flexboard vector pack's front half, resolved by NAME at runtime (getIdentifier) so
     * aapt2's numbering never leaves the device it was baked on. Mirrors HOTKEY_DEFAULT_SYMBOLS in
     * SettingsScreenPatch.kt, locked in step by the constants checker.
     *
     * Read two ways, so it is deliberately longer than SLOTS: entries [0, SLOTS) are the per-slot
     * defaults reached by DEFAULT_ICON_NAMES[slot - 1], while the array in full is the first part
     * of ICON_CHOICES, the picker grid. Shortening it below SLOTS would index out of bounds.
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

    /**
     * The rest of the bundled pack, after the slot defaults — the back half of the picker's
     * grid. The patch side mirrors this list as {@code HOTKEY_EXTRA_SYMBOLS} in
     * {@code SettingsScreenPatch.kt}; the constants checker holds the two in step.
     */
    private static final String[] EXTRA_ICON_NAMES = new String[] {
        "flexboard_icon_snowflake",
        "flexboard_icon_token",
        "flexboard_icon_counter_0",
        "flexboard_icon_counter_1",
        "flexboard_icon_counter_2",
        "flexboard_icon_counter_3",
        "flexboard_icon_counter_4",
        "flexboard_icon_counter_5",
        "flexboard_icon_counter_6",
        "flexboard_icon_counter_7",
        "flexboard_icon_counter_8",
        "flexboard_icon_counter_9",
    };

    /**
     * The picker's grid order: the twelve slot defaults first, then the extras.
     *
     * <p>Sized and filled by the two source tables' own lengths, not SLOT_COUNT: a drifted table
     * degrades to a short grid instead of killing the whole class in its static initializer
     * (and the count drift is the constants checker's red, not the phone's).
     */
    private static final String[] ICON_CHOICES;
    static {
        ICON_CHOICES = new String[DEFAULT_ICON_NAMES.length + EXTRA_ICON_NAMES.length];
        System.arraycopy(DEFAULT_ICON_NAMES, 0, ICON_CHOICES, 0, DEFAULT_ICON_NAMES.length);
        System.arraycopy(EXTRA_ICON_NAMES, 0, ICON_CHOICES,
            DEFAULT_ICON_NAMES.length, EXTRA_ICON_NAMES.length);
    }

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
        // Bounds-checked like shown() above. Both readers index DEFAULT_ICON_NAMES[slot - 1]
        // directly, and are safe today only because the emitted smali gates them behind shown()
        // and the fragment loops 1..slotCount(). The array being longer than SLOT_COUNT is what
        // has been hiding the omission; SLOT_COUNT is documented as a number someone may raise.
        if (slot < 1 || slot > SLOT_COUNT) {
            return 0;
        }
        String raw = Preferences.of(context).getString(iconKey(slot), "");
        int resolved = resolveIcon(context, raw);
        if (resolved != 0) {
            return resolved;
        }
        return resolveIcon(context, DEFAULT_ICON_NAMES[slot - 1]);
    }

    /**
     * name → getIdentifier, decimal → as-is, anything else → 0 (caller falls back).
     *
     * <p>A decimal token is a build-local resource id, kept only so dev.7-and-earlier exports
     * still parse — but the number must be validated on <i>this</i> build before use: a renumbered
     * Gboard turns the same value into a different resource or none at all, and
     * {@code getDrawable(badId)} throws rather than returning null. Any doubt answers 0 and the
     * caller's default takes over — the toolbar build and the settings sync both ride this path.
     */
    private static int resolveIcon(Context context, String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            int id = Integer.parseInt(token.trim());
            if (id <= 0) {
                return 0;
            }
            try {
                return "drawable".equals(context.getResources().getResourceTypeName(id))
                        ? id : 0;
            } catch (android.content.res.Resources.NotFoundException stale) {
                return 0;
            }
        } catch (NumberFormatException ignored) {
            // not decimal: treat as a drawable name
        }
        return context.getResources().getIdentifier(token.trim(), "drawable", context.getPackageName());
    }

    /**
     * The slot's effective icon token: the stored override when set, the slot's default name
     * otherwise. This is what the export blob carries, and what the settings screen redraws a
     * row from after a pick or an import.
     */
    public static String currentIconToken(Context context, int slot) {
        // Bounds-checked like shown() above. Both readers index DEFAULT_ICON_NAMES[slot - 1]
        // directly, and are safe today only because the emitted smali gates them behind shown()
        // and the fragment loops 1..slotCount(). The array being longer than SLOT_COUNT is what
        // has been hiding the omission; SLOT_COUNT is documented as a number someone may raise.
        if (slot < 1 || slot > SLOT_COUNT) {
            return "";
        }
        String raw = Preferences.of(context).getString(iconKey(slot), "");
        return raw.isEmpty() ? DEFAULT_ICON_NAMES[slot - 1] : raw;
    }

    /**
     * The drawable a settings row should show for a token, or {@code null} when it resolves to
     * nothing on this build — the row keeps its XML icon rather than blanking.
     */
    public static Drawable drawableOf(Context context, String token) {
        int id = resolveIcon(context, token);
        return id != 0 ? context.getDrawable(id) : null;
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

    /**
     * The full store key of a slot's text/icon. The settings screen addresses its rows with
     * these exact strings, so they are public to keep the key format single-sourced — a typo
     * here would be a silently dead row, and nothing else would say so.
     */
    public static String textKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_TEXT_SUFFIX;
    }

    public static String iconKey(int slot) {
        return PREF_TEXT_PREFIX + slot + PREF_ICON_SUFFIX;
    }

    /** The picker's full table, in grid order. A copy, so a caller can't reorder the pack. */
    public static String[] choices() {
        return ICON_CHOICES.clone();
    }

    /** Writes a slot's committed text straight to the store (the composite dialog's half). */
    public static void setText(Context context, int slot, String text) {
        Preferences.of(context).edit().putString(textKey(slot), text).apply();
    }

    /** Writes a slot's icon choice — one of the {@link #choices()} names. */
    public static void setIconToken(Context context, int slot, String token) {
        Preferences.of(context).edit().putString(iconKey(slot), token).apply();
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
     * Reads the clipboard and, if it carries a blob, applies it. The no-dialog fallback for the
     * Import row; something that is not one of our exports is refused without touching the store.
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
        return importFromText(context, text.toString());
    }

    /**
     * Applies a blob held in hand — the import popup's Apply button hands the box's contents
     * straight here. Malformed lines abort-all, but a slot number past this build's count is
     * *skipped* rather than fatal, so an export made on a wider build imports its overlapping
     * slots instead of being refused wholesale; the outcome string says when that happened.
     */
    public static String importFromText(Context context, String blob) {
        if (blob == null || blob.trim().isEmpty()) {
            return "nothing to apply";
        }
        if (!blob.startsWith(BLOB_VERSION)) {
            return "not a Flexboard export";
        }
        int[] outcome = applyBlob(context, blob);
        if (outcome == null) {
            return "export is malformed — nothing changed";
        }
        String result = "imported " + countOccupied(context) + " slots";
        if (outcome[1] > 0) {
            result += " (" + outcome[1] + " beyond this build's slot count skipped)";
        }
        return result;
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

    /** The blob as text — what Export copies, and what the export popup shows. */
    public static String exportText(Context context) {
        return serialize(context);
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
                .append(currentIconToken(context, slot))
                .append('\n');
        }
        return out.toString();
    }

    /**
     * Strict-any-bad-line parse of a pasted blob: a wrong field count, a non-number, a duplicate
     * slot, or a slot that's not ≥ 1 rejects the whole paste without touching the store. The one
     * tolerated divergence is slot *beyond this build's slot count* — those lines are skipped
     * (counted for the outcome string) rather than fatal, so a blob travels cleanly from a wider
     * build into a trimmed one.
     *
     * @return {@code null} when the blob was rejected, else {written lines, skipped lines}.
     */
    private static int[] applyBlob(Context context, String blob) {
        String[] lines = blob.split("\n");
        if (lines.length < 1 || !lines[0].trim().equals(BLOB_VERSION)) {
            return null;
        }
        String[] texts = new String[SLOT_COUNT + 1];
        String[] icons = new String[SLOT_COUNT + 1];
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int skipped = 0;
        int written = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) {
                return null;
            }
            int slot;
            try {
                slot = Integer.parseInt(fields[0].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (slot < 1 || !seen.add(slot)) {
                return null;
            }
            if (slot > SLOT_COUNT) {
                skipped++;
                continue;
            }
            String icon = fields[2].trim();
            if (icon.isEmpty()) {
                return null;
            }
            texts[slot] = unescape(fields[1]);
            icons[slot] = icon;
            written++;
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
        return new int[] { written, skipped };
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
