package dev.jz6.flexboard.extension.prefs;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Gboard's own settings, written the way Gboard writes them.
 *
 * <p>Two patches need to change a Gboard preference at startup: one forces the settings the widened
 * swipe cannot work without, the other turns on flick keys as a default. Both used to do it in
 * bytecode, through the store's id-keyed setter.
 *
 * <p><b>None of that was necessary.</b> A Gboard preference key is a string resource's value —
 * `Lqhy;` resolves an id through `PreferenceKeyCache`, which is {@link Context#getString} behind a
 * cache — and the file underneath is an ordinary {@link SharedPreferences}, the same one
 * {@link Preferences} already opens for Flexboard's own values. So the key comes from
 * {@code getString(id)} and the write is the framework's.
 *
 * <p>What that removed is worth naming, because it was not merely tidier. The old code derived two
 * accessors on an obfuscated class, and one of them had a documented trap: the id-keyed
 * {@code contains} shares its signature with a sibling that answers <i>"is it currently true?"</i>
 * rather than <i>"has the user ever set this?"</i>. A moved letter would have made flick keys force
 * themselves back on every start instead of behaving as a default, and nothing short of a device
 * would have said so. {@link SharedPreferences#contains} has no sibling.
 *
 * <p><b>The resource ids stay pinned</b>, exactly as they were — {@code COMPATIBILITY_GBOARD} ties
 * the bundle to one Gboard build, and `tools/apk/preflight.py` now checks that each id still names
 * the preference it is supposed to, which nothing did while they lived in Kotlin.
 *
 * <p>Called from patched bytecode at Application start, before any keyboard is built and so before
 * anything reads these.
 */
public final class GboardSettings {

    /**
     * `enable_scrub_delete`. Gates the handler <i>declaratively</i> — the keyboard layout attaches
     * `ScrubDeleteMotionEventHandler` only when it is true, so with Gboard's "Delete swipe" off the
     * patch does nothing at all and looks broken.
     */
    private static final int ENABLE_SCRUB_DELETE = 0x7f140a1f;

    /**
     * `enable_gesture_input`, which is glide typing. A leftward drag across the letters is also a
     * glide input, so the two cannot both be live.
     */
    private static final int ENABLE_GESTURE_INPUT = 0x7f140a05;

    /** `pref_enable_flick_symbols`. */
    private static final int ENABLE_FLICK_SYMBOLS = 0x7f140a01;

    /** `enable_secondary_digits` — "Touch & hold keys for numbers". Un-greys the flick row. */
    private static final int ENABLE_SECONDARY_DIGITS = 0x7f140a21;

    /** `block_offensive_words` — off means "don't suggest offensive words". */
    private static final int BLOCK_OFFENSIVE_WORDS = 0x7f1409c0;

    /** `show_suggestions` — off means no word suggestions in the strip. */
    private static final int SHOW_SUGGESTIONS = 0x7f140b6f;

    /** `show_suggestion_strip` — the strip itself, which carries feature buttons. On by default. */
    private static final int SHOW_SUGGESTION_STRIP = 0x7f140b6e;

    /** `pref_key_enable_grammar_checker` — underlines grammatical errors in blue. */
    private static final int ENABLE_GRAMMAR_CHECKER = 0x7f140a07;

    /** `enable_smart_reply` — shows reply suggestions in supported apps. */
    private static final int ENABLE_SMART_REPLY = 0x7f140a28;

    private GboardSettings() {}

    /**
     * Turns the scrub-delete preference on and glide typing off, on every start.
     *
     * <p><b>Every start, not once.</b> Writing the setting rather than intercepting the read is
     * deliberate: an earlier version of this project hooked the store's boolean getter and it
     * failed on device, because every consumer caches the value and the caches only refresh when
     * the keyboard is shown, so glide typing kept coming back. See `docs/glide-detection.md`.
     *
     * <p>That is also why both rows are greyed out in Gboard's settings while the patch is applied
     * — a switch that will not stay where it is put is worse than one that visibly cannot move.
     */
    public static void forceScrubPreferences(Context context) {
        String scrub = keyOrNull(context, ENABLE_SCRUB_DELETE);
        String glide = keyOrNull(context, ENABLE_GESTURE_INPUT);
        SharedPreferences.Editor editor = Preferences.of(context).edit();
        boolean wrote = false;
        if (scrub != null) {
            editor.putBoolean(scrub, true);
            wrote = true;
        }
        if (glide != null) {
            editor.putBoolean(glide, false);
            wrote = true;
        }
        if (wrote) {
            editor.apply();
        }
    }
    /**
     * {@code context.getString(id)} for one of the pinned Gboard ids, or null when it fails.
     *
     * <p>These nine ids are hardcoded numbers pinned against one build, and both callers run from
     * patched bytecode at Gboard's Application start. A stale id makes {@code getString} throw
     * {@link android.content.res.Resources.NotFoundException} inside {@code onCreate} -- which is
     * not a settings screen failing, it is the whole app failing to start, on a keyboard, with no
     * keyboard left to report it with. Skipping one preference is a far better outcome than a
     * crash loop.
     *
     * <p>{@code tools/apk/preflight.py} checks all nine, and now runs from the pre-push hook, so
     * this is the seatbelt rather than the gate. It does not help against an id that resolves to
     * the <em>wrong</em> string -- nothing at runtime can detect that, which is what the preflight
     * pins are for.
     */
    private static String keyOrNull(Context context, int id) {
        try {
            return context.getString(id);
        } catch (Throwable missing) {
            return null;
        }
    }


    /**
     * Writes Gboard's "suggested" defaults once, on the first run after installing.
     *
     * <p>Each key is guarded by {@link SharedPreferences#contains} so turning any of these off in
     * Gboard's own settings sticks — this is a default, not a force. The guards are per-key rather
     * than behind a single "have we seeded" marker, so someone who has set only one still gets the
     * rest when a later release adds them.
     *
     * <p>The settings and their rationales:
     *
     * <ul>
     *   <li><b>Flick keys for symbols</b> on — pull down on a key to enter the symbol in its corner.
     *   <li><b>Touch &amp; hold keys for numbers</b> on — un-greys the flick row above, which
     *       carries {@code dependency="enable_secondary_digits"} in Gboard's preference XML.
     *   <li><b>Block offensive words</b> off — stops Gboard from suppressing words it considers
     *       offensive from its suggestions.
     *   <li><b>Word suggestions</b> off — removes word suggestions from the strip, but the strip
     *       itself stays so feature buttons (grammar check, proofread, etc.) still render.
     *   <li><b>Suggestion strip</b> on — the strip that carries both suggestions and feature
     *       buttons. Explicitly on so that turning word suggestions off does not take the strip
     *       and everything that rides on it with it.
     *   <li><b>Grammar check</b> on — underlines grammatical errors in blue.
     *   <li><b>Smart replies</b> on — shows reply suggestions in supported apps.
     * </ul>
     */
    public static void defaultSuggestedSettings(Context context) {
        SharedPreferences preferences = Preferences.of(context);
        SharedPreferences.Editor editor = preferences.edit();
        boolean wrote = false;

        String key = keyOrNull(context, ENABLE_FLICK_SYMBOLS);
 if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, true);
            wrote = true;
        }
        key = keyOrNull(context, ENABLE_SECONDARY_DIGITS);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, true);
            wrote = true;
        }
        key = keyOrNull(context, BLOCK_OFFENSIVE_WORDS);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, false);
            wrote = true;
        }
        key = keyOrNull(context, SHOW_SUGGESTIONS);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, false);
            wrote = true;
        }
        key = keyOrNull(context, SHOW_SUGGESTION_STRIP);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, true);
            wrote = true;
        }
        key = keyOrNull(context, ENABLE_GRAMMAR_CHECKER);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, true);
            wrote = true;
        }
        key = keyOrNull(context, ENABLE_SMART_REPLY);
        if (key != null && !preferences.contains(key)) {
            editor.putBoolean(key, true);
            wrote = true;
        }

        if (wrote) {
            editor.apply();
        }
    }
}
