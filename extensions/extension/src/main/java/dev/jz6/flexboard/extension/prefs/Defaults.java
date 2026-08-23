package dev.jz6.flexboard.extension.prefs;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Flexboard's starting values, written into Gboard's preference file the first time the app runs.
 *
 * <p><b>Why written rather than defaulted.</b> Every other value in this project takes effect as
 * the fallback operand of a preference read emitted into Gboard's bytecode — the number lives in
 * the patch, and an unset preference picks it up. That has one property worth avoiding here: the
 * default follows the code, so changing it in a later release moves every user who never touched
 * the slider. Someone who has spent a month with a keyboard should not find it different after an
 * update they did not ask for.
 *
 * <p>Writing once inverts that. The first run after installing decides, the value is then an
 * ordinary stored preference indistinguishable from one the user set, and later releases can pick
 * different starting numbers for new installs without disturbing anyone.
 *
 * <p><b>Only when unset.</b> Each key is guarded separately rather than behind a single "have we
 * seeded" marker, so a partial grant still fills in whatever arrived after it. The framework's own
 * {@link SharedPreferences#contains} is the test, which is also why nothing here names anything of
 * Gboard's.
 *
 * <p><b>Where this runs.</b> {@code seedDefaultsPatch} calls it from Gboard's Application start,
 * before any keyboard is built and so before any of these is read. The patched call passes the
 * {@code LatinApp} itself, which is an {@code Application} and therefore a {@link Context}; nothing
 * obfuscated crosses the boundary.
 *
 * <p>All keys are seeded whichever patches were applied. A key belonging to a patch the user
 * did not pick is inert — read by nothing — and the alternative is threading patch selection into
 * the extension, which is the same wart the settings screen already documents for its sections.
 */
public final class Defaults {

    // `check_shared_constants.py` collects constants across the whole extension and fails when one
    // name carries two different values, so the seed cannot drift from the engine's fallback
    // without the build saying so — and this is the one that decides what a keyboard actually
    // does on first run.

    /** Must match STEP_SCALE_KEY / STEP_SCALE_DEFAULT in ScrubTuningPatch.kt. */
    private static final String KEY_STEP_SCALE = "flexboard_scrub_step_scale";

    private static final int STEP_SCALE_DEFAULT = 60;

    private Defaults() {}

    /** The slot fan-out — mirrors HOTKEY_SLOTS on the patch side. */
    private static final int HOTKEY_SLOTS = 12;

    /** Called from patched bytecode at Gboard's Application start. */
    public static void seed(Context context) {
        SharedPreferences preferences = Preferences.of(context);

        if (!preferences.contains(KEY_STEP_SCALE)) {
            preferences.edit().putInt(KEY_STEP_SCALE, STEP_SCALE_DEFAULT).apply();
        }

        // Hotkey placeholders: twelve numbered buttons out of the box, so the feature is
        // discoverable without reading anything. "Delete the text" is the documented way to
        // hide a slot, which is also why the seed only ever fills an unset key.
        SharedPreferences.Editor editor = null;
        for (int slot = 1; slot <= HOTKEY_SLOTS; slot++) {
            String key = "flexboard_hotkey_" + slot + "_text";
            if (!preferences.contains(key)) {
                if (editor == null) {
                    editor = preferences.edit();
                }
                editor.putString(key, Integer.toString(slot));
            }
        }
        if (editor != null) {
            editor.apply();
        }
    }
}
