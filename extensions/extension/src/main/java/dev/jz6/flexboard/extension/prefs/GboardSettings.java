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
        Preferences.of(context)
                .edit()
                .putBoolean(context.getString(ENABLE_SCRUB_DELETE), true)
                .putBoolean(context.getString(ENABLE_GESTURE_INPUT), false)
                .apply();
    }

    /**
     * Turns flick keys on, but only if the user has never set it.
     *
     * <p>The difference from {@link #forceScrubPreferences} is the whole feature: this is a default
     * rather than something forced, so turning it off in Gboard's settings sticks.
     */
    public static void defaultFlickSymbolsOn(Context context) {
        SharedPreferences preferences = Preferences.of(context);
        String key = context.getString(ENABLE_FLICK_SYMBOLS);
        if (!preferences.contains(key)) {
            preferences.edit().putBoolean(key, true).apply();
        }
    }
}
