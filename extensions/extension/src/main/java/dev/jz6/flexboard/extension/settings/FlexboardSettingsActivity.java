package dev.jz6.flexboard.extension.settings;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Flexboard's settings screen.
 *
 * <p>This class is merged into Gboard's APK rather than shipped as its own app, so it cannot rely
 * on any resources of its own resolving at runtime. Every view is built in code, every string is a
 * constant, and only framework widgets are used — no AndroidX, no layout inflation, nothing that
 * assumes anything about the host. The theme is set on the manifest entry the patch writes, so the
 * screen follows the system light/dark setting without hardcoding a palette.
 *
 * <p><b>It writes to Gboard's own preference file, deliberately.</b> Gboard's store
 * (<code>Lpnp;</code>) is constructed with a null name, which resolves to
 * <code>PreferenceManager.getDefaultSharedPreferences</code> — that is
 * <code>&lt;packageName&gt;_preferences</code> in <code>MODE_PRIVATE</code>. Naming that file
 * explicitly here, rather than going through the deprecated framework
 * <code>PreferenceManager</code>, keeps the two sides provably identical. Deriving it from
 * {@link #getPackageName()} is also what keeps it correct after the package-rename patch, since
 * both sides resolve the same running package.
 *
 * <p>The keys must match the ones the bytecode patch reads. They are duplicated as literals in
 * <code>ScrubTuningPatch.kt</code>, because a patch-added resource has no id until aapt2 recompiles
 * and so cannot be addressed from bytecode.
 */
public final class FlexboardSettingsActivity extends Activity {

    /** Must match STEP_SCALE_KEY in ScrubTuningPatch.kt. */
    private static final String KEY_STEP_SCALE = "flexboard_scrub_step_scale";
    /** Must match MAX_WORDS_KEY in ScrubTuningPatch.kt. */
    private static final String KEY_MAX_WORDS = "flexboard_max_words";
    /** Must match HOLD_DELAY_KEY in ScrubTuningPatch.kt. */
    private static final String KEY_HOLD_DELAY = "flexboard_scrub_hold_ms";

    private static final int STEP_SCALE_MIN = 25;
    private static final int STEP_SCALE_MAX = 300;
    private static final int STEP_SCALE_DEFAULT = 100;

    private static final int MAX_WORDS_MIN = 1;
    /** Doubles as "no limit" — the clamp is skipped at or above it. */
    private static final int MAX_WORDS_DEFAULT = 10;

    private static final int HOLD_DELAY_MIN = 0;
    private static final int HOLD_DELAY_MAX = 300;
    private static final int HOLD_DELAY_DEFAULT = 0;

    private static final String TITLE = "Flexboard";

    private static final int PADDING_DP = 20;
    private static final int ROW_GAP_DP = 28;
    private static final int LABEL_GAP_DP = 4;

    private SharedPreferences preferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(TITLE);

        preferences = getSharedPreferences(getPackageName() + "_preferences", Context.MODE_PRIVATE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(PADDING_DP);
        column.setPadding(padding, padding, padding, padding);

        addSlider(
                column,
                KEY_STEP_SCALE,
                "Swipe length",
                "How far to swipe per deleted word, as a percent of Gboard's own distance. "
                        + "Lower deletes more words for the same swipe.",
                STEP_SCALE_MIN,
                STEP_SCALE_MAX,
                STEP_SCALE_DEFAULT,
                "%");

        addSlider(
                column,
                KEY_MAX_WORDS,
                "Max words per swipe",
                "The most words one swipe can delete. Set to 1 to delete a single word however "
                        + "far you swipe. " + MAX_WORDS_DEFAULT + " means no limit.",
                MAX_WORDS_MIN,
                MAX_WORDS_DEFAULT,
                MAX_WORDS_DEFAULT,
                "");

        addSlider(
                column,
                KEY_HOLD_DELAY,
                "Hold delay",
                "How long the swipe must be held before it starts deleting. 0 lets a quick flick "
                        + "register; Gboard's own delete swipe uses 200.",
                HOLD_DELAY_MIN,
                HOLD_DELAY_MAX,
                HOLD_DELAY_DEFAULT,
                " ms");

        ScrollView scroll = new ScrollView(this);
        scroll.addView(
                column,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /**
     * One labelled slider, persisted on every change.
     *
     * <p>{@link SeekBar} counts from zero, so the stored value is offset by {@code min} on the way
     * in and out. Writing on each change rather than on release means the value is already in the
     * store if the screen is dismissed mid-drag; the keyboard rereads it when the handler is next
     * constructed either way.
     */
    private void addSlider(
            LinearLayout parent,
            final String key,
            String title,
            String summary,
            final int min,
            int max,
            int fallback,
            final String unit) {

        final TextView label = new TextView(this);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        parent.addView(label);

        TextView description = new TextView(this);
        description.setText(summary);
        description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        description.setAlpha(0.7f);
        LinearLayout.LayoutParams descriptionParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.topMargin = dp(LABEL_GAP_DP);
        parent.addView(description, descriptionParams);

        final int current = preferences.getInt(key, fallback);
        label.setText(title + "  —  " + current + unit);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(current - min);
        final String labelPrefix = title;
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int value = progress + min;
                        label.setText(labelPrefix + "  —  " + value + unit);
                        if (fromUser) {
                            preferences.edit().putInt(key, value).apply();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });

        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.bottomMargin = dp(ROW_GAP_DP);
        parent.addView(bar, barParams);
    }

    private int dp(int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
