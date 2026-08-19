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
 * seeded" marker, so someone who has set only the swipe length still gets the toolbar numbers when
 * those arrive. The framework's own {@link SharedPreferences#contains} is the test, which is also
 * why nothing here names anything of Gboard's.
 *
 * <p><b>Where this runs.</b> {@code seedDefaultsPatch} calls it from Gboard's Application start,
 * before any keyboard is built and so before any of these is read. The patched call passes the
 * {@code LatinApp} itself, which is an {@code Application} and therefore a {@link Context}; nothing
 * obfuscated crosses the boundary.
 *
 * <p>All three keys are seeded whichever patches were applied. A key belonging to a patch the user
 * did not pick is inert — read by nothing — and the alternative is threading patch selection into
 * the extension, which is the same wart the settings screen already documents for its sections.
 */
public final class Defaults {

    // Every constant below is named exactly as its counterpart in FlexboardSettingsActivity, which
    // is not cosmetic: `check_shared_constants.py` collects constants across the whole extension
    // and fails when one name carries two different values. So the number written here and the
    // number that screen shows cannot drift apart without the build saying so — and this is the one
    // that decides what a keyboard actually does.

    /** Must match STEP_SCALE_KEY / STEP_SCALE_DEFAULT in ScrubTuningPatch.kt. */
    private static final String KEY_STEP_SCALE = "flexboard_scrub_step_scale";

    private static final int STEP_SCALE_DEFAULT = 60;

    /** Must match TOOLBAR_COUNT_KEY in ToolbarCountPatch.kt. */
    private static final String KEY_TOOLBAR_COUNT = "flexboard_toolbar_count";

    private static final int TOOLBAR_COUNT_DEFAULT = 6;

    /**
     * Must match TOOLBAR_COUNT_UNFOLDED_KEY in ToolbarCountPatch.kt.
     *
     * <p>Twelve rather than six because the inner screen of a fold is wide enough for them, and
     * because Gboard itself keeps a separate count per device class — the two screens differing is
     * stock behaviour, not something Flexboard introduces. Each slider owns its screen: this one is
     * not a fallback to the other.
     */
    private static final String KEY_TOOLBAR_COUNT_UNFOLDED = "flexboard_toolbar_count_unfolded";

    private static final int TOOLBAR_COUNT_UNFOLDED_DEFAULT = 12;

    private Defaults() {}

    /** Called from patched bytecode at Gboard's Application start. */
    public static void seed(Context context) {
        SharedPreferences preferences = Preferences.of(context);

        SharedPreferences.Editor editor = preferences.edit();
        boolean wrote = false;

        if (!preferences.contains(KEY_STEP_SCALE)) {
            editor.putInt(KEY_STEP_SCALE, STEP_SCALE_DEFAULT);
            wrote = true;
        }
        if (!preferences.contains(KEY_TOOLBAR_COUNT)) {
            editor.putInt(KEY_TOOLBAR_COUNT, TOOLBAR_COUNT_DEFAULT);
            wrote = true;
        }
        if (!preferences.contains(KEY_TOOLBAR_COUNT_UNFOLDED)) {
            editor.putInt(KEY_TOOLBAR_COUNT_UNFOLDED, TOOLBAR_COUNT_UNFOLDED_DEFAULT);
            wrote = true;
        }

        // Nothing to commit on every start but the first, which is the common case by a very long
        // way — this runs on each cold start for the life of the install.
        if (wrote) {
            editor.apply();
        }
    }
}
