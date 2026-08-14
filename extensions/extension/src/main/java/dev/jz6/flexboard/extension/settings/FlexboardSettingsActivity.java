package dev.jz6.flexboard.extension.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Flexboard's settings screen.
 *
 * <p>This class is merged into Gboard's APK rather than shipped as its own app, so it cannot rely
 * on any resources of its own resolving at runtime. Every view is built in code, every string is a
 * constant, and only framework widgets are used — no AndroidX, no layout inflation, nothing that
 * assumes anything about the host. The row structure, the section header and the palette are
 * carried over from the screen v0.3 shipped, which is the shape this is meant to look like.
 *
 * <p><b>It draws its own chrome, deliberately.</b> The first version used
 * {@code Theme.DeviceDefault.Settings} and let the theme supply the bar and the colours, and the
 * top row came out clipped. Gboard targets SDK 37, so on Android 15 and up the window is
 * edge-to-edge and content starts underneath the status bar unless something insets it. Working out
 * how much of that a themed action bar had already absorbed is not something this can test against
 * every device, so the theme is now {@code NoActionBar} and this class is the only thing that
 * consumes insets: it pads the scroll container by whatever the system bars report and lets the
 * background run full-bleed behind them.
 *
 * <p>Colours follow {@link Configuration#uiMode} rather than theme attributes, for the same reason
 * — a merged class cannot know what the host's attributes resolve to. v0.3 hardcoded a dark
 * palette; this picks between two.
 *
 * <p><b>It writes to Gboard's own preference file, deliberately.</b> Gboard's store
 * (<code>Lpnp;</code>) is constructed with a null name, which resolves to
 * <code>PreferenceManager.getDefaultSharedPreferences</code> — that is
 * <code>&lt;packageName&gt;_preferences</code> in <code>MODE_PRIVATE</code>, on a
 * <b>device-protected</b> context. See {@link #preferenceContext()} — that last part is not a
 * detail but a different file on disk, and getting it wrong is why every slider on this screen did
 * nothing at all before <code>v0.1.0-dev.7</code>. Deriving the name from
 * {@link #getPackageName()} is what keeps it correct after the package-rename patch, since both
 * sides resolve the same running package.
 *
 * <p>The keys must match the ones the bytecode patch reads. They are duplicated as literals in
 * <code>ScrubTuningPatch.kt</code>, because a patch-added resource has no id until aapt2 recompiles
 * and so cannot be addressed from bytecode.
 */
public final class FlexboardSettingsActivity extends Activity {

    /** Must match SCRUB_ENABLED_KEY in ScrubDeleteAnywherePatch.kt. */
    private static final String KEY_ENABLED = "flexboard_enabled";
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
    private static final String SUBTITLE = "Swipe anywhere to delete the previous word.";
    private static final String SECTION = "Swipe to delete";

    private static final String ENABLED_TITLE = "Swipe anywhere";
    private static final String ENABLED_SUMMARY =
            "Off puts Gboard back as it shipped: the delete swipe works on the backspace key and "
                    + "nowhere else, at Gboard's own distance and hold. Glide typing does not come "
                    + "back on its own — turn it on in Gboard's settings if you want it.";
    private static final String TAKES_EFFECT =
            "Changes apply the next time the keyboard is opened.";

    private static final int COLOR_DARK_BACKGROUND = 0xFF202124;
    private static final int COLOR_DARK_TITLE = 0xFFE8EAED;
    private static final int COLOR_DARK_SUMMARY = 0xFF9AA0A6;
    private static final int COLOR_DARK_ACCENT = 0xFF8AB4F8;

    private static final int COLOR_LIGHT_BACKGROUND = 0xFFFFFFFF;
    private static final int COLOR_LIGHT_TITLE = 0xFF1F1F1F;
    private static final int COLOR_LIGHT_SUMMARY = 0xFF5F6368;
    private static final int COLOR_LIGHT_ACCENT = 0xFF0B57D0;

    private static final int EDGE_DP = 20;
    private static final int ROW_TOP_DP = 22;
    private static final int TIGHT_DP = 3;
    private static final int LOOSE_DP = 8;

    private static final float DISABLED_ALPHA = 0.4f;

    /** Renders the stored int as the value shown beside a row's title. */
    private interface Label {
        String of(int value);
    }

    /** Everything the switch greys out. Collected as the rows are built. */
    private final List<View> tunables = new ArrayList<>();

    private SharedPreferences preferences;
    private int colorBackground;
    private int colorTitle;
    private int colorSummary;
    private int colorAccent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(TITLE);

        preferences =
                preferenceContext()
                        .getSharedPreferences(
                                getPackageName() + "_preferences", Context.MODE_PRIVATE);

        boolean night =
                (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES;
        colorBackground = night ? COLOR_DARK_BACKGROUND : COLOR_LIGHT_BACKGROUND;
        colorTitle = night ? COLOR_DARK_TITLE : COLOR_LIGHT_TITLE;
        colorSummary = night ? COLOR_DARK_SUMMARY : COLOR_LIGHT_SUMMARY;
        colorAccent = night ? COLOR_DARK_ACCENT : COLOR_LIGHT_ACCENT;
        if (!night) {
            requestDarkSystemBarIcons();
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP * 2));

        addHeading(column);
        addSectionHeader(column);
        addEnabledSwitch(column);

        addSlider(
                column,
                KEY_STEP_SCALE,
                "Swipe length",
                "How far to swipe per deleted word, as a percent of Gboard's own distance. "
                        + "Lower deletes more words for the same swipe.",
                STEP_SCALE_MIN,
                STEP_SCALE_MAX,
                STEP_SCALE_DEFAULT,
                value -> value + "%");

        addSlider(
                column,
                KEY_MAX_WORDS,
                "Max words per swipe",
                "The most words one swipe can delete. Set it to 1 to delete a single word however "
                        + "far you swipe. Swiping back still restores.",
                MAX_WORDS_MIN,
                MAX_WORDS_DEFAULT,
                MAX_WORDS_DEFAULT,
                value -> value >= MAX_WORDS_DEFAULT ? "No limit" : Integer.toString(value));

        addSlider(
                column,
                KEY_HOLD_DELAY,
                "Hold delay",
                "How long the swipe must be held before it starts deleting. Gboard's own delete "
                        + "swipe uses 200 ms, which is what makes it a press-and-drag rather than "
                        + "a flick.",
                HOLD_DELAY_MIN,
                HOLD_DELAY_MAX,
                HOLD_DELAY_DEFAULT,
                value -> value == 0 ? "Off" : value + " ms");

        TextView footnote = new TextView(this);
        footnote.setText(TAKES_EFFECT);
        footnote.setTextColor(colorSummary);
        footnote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        column.addView(footnote, marginTop(dp(EDGE_DP)));

        setTunablesEnabled(preferences.getBoolean(KEY_ENABLED, true));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBackground);
        scroll.setFillViewport(true);
        // The insets become padding, so the background still runs behind the system bars and the
        // content simply scrolls under them.
        scroll.setClipToPadding(false);
        scroll.addView(
                column,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        padBySystemBars(scroll);

        setContentView(scroll);
    }

    /**
     * Insets the view by the system bars.
     *
     * <p>This is the whole fix for the clipped first row. Nothing else in the window consumes
     * insets, so whatever arrives here is the full amount and can be applied as-is.
     */
    private void padBySystemBars(final View view) {
        view.setOnApplyWindowInsetsListener(
                (target, windowInsets) -> {
                    int[] bars =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                    ? systemBarsApi30(windowInsets)
                                    : systemBarsLegacy(windowInsets);
                    target.setPadding(bars[0], bars[1], bars[2], bars[3]);
                    return windowInsets;
                });
        view.requestApplyInsets();
    }

    /**
     * Kept in its own method, and every other API 30 reference likewise, so that
     * {@link Insets} and {@link WindowInsetsController} are only resolved on a device that has
     * them. Naming a missing class inside a version-guarded branch of a larger method makes the
     * whole method fail verification on older releases.
     */
    @SuppressLint("NewApi") // Guarded by the caller; lint does not follow across methods.
    private static int[] systemBarsApi30(WindowInsets windowInsets) {
        Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars());
        return new int[] {bars.left, bars.top, bars.right, bars.bottom};
    }

    @SuppressWarnings("deprecation")
    private static int[] systemBarsLegacy(WindowInsets windowInsets) {
        return new int[] {
            windowInsets.getSystemWindowInsetLeft(),
            windowInsets.getSystemWindowInsetTop(),
            windowInsets.getSystemWindowInsetRight(),
            windowInsets.getSystemWindowInsetBottom(),
        };
    }

    /** Dark icons in the status and navigation bars, so they stay legible on a light background. */
    @SuppressWarnings("deprecation")
    private void requestDarkSystemBarIcons() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            darkSystemBarIconsApi30(getWindow());
        } else {
            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @SuppressLint("NewApi") // Guarded by the caller; lint does not follow across methods.
    private static void darkSystemBarIconsApi30(android.view.Window window) {
        WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            int appearance =
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(appearance, appearance);
        }
    }

    /** Stands in for the action bar the theme no longer provides. */
    private void addHeading(LinearLayout parent) {
        TextView heading = new TextView(this);
        heading.setText(TITLE);
        heading.setTextColor(colorTitle);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(heading);

        TextView subtitle = new TextView(this);
        subtitle.setText(SUBTITLE);
        subtitle.setTextColor(colorSummary);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        parent.addView(subtitle, marginTop(dp(TIGHT_DP)));
    }

    private void addSectionHeader(LinearLayout parent) {
        TextView section = new TextView(this);
        section.setText(SECTION);
        section.setTextColor(colorAccent);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        section.setAllCaps(true);
        section.setTypeface(Typeface.DEFAULT_BOLD);
        parent.addView(section, marginTop(dp(EDGE_DP + LOOSE_DP)));
    }

    /**
     * The master switch. Everything below it is Flexboard's; turning it off hands the gesture back
     * to Gboard unchanged.
     *
     * <p>The switch is deliberately not in {@link #tunables} — it is the thing doing the disabling.
     */
    private void addEnabledSwitch(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(ENABLED_TITLE);
        titleView.setTextColor(colorTitle);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(
                titleView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(KEY_ENABLED, true));
        // Tinted for the same reason the sliders are: the widget follows the theme, the palette
        // follows the system night setting, and untinted they disagree in light mode.
        ColorStateList checkedAccent =
                new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                        new int[] {colorAccent, colorSummary});
        toggle.setThumbTintList(checkedAccent);
        toggle.setTrackTintList(checkedAccent);
        toggle.setOnCheckedChangeListener(
                (button, isChecked) -> {
                    preferences.edit().putBoolean(KEY_ENABLED, isChecked).apply();
                    setTunablesEnabled(isChecked);
                });
        row.addView(toggle);

        parent.addView(row, marginTop(dp(ROW_TOP_DP)));

        TextView summaryView = new TextView(this);
        summaryView.setText(ENABLED_SUMMARY);
        summaryView.setTextColor(colorSummary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        parent.addView(summaryView, marginTop(dp(TIGHT_DP)));
    }

    private void setTunablesEnabled(boolean enabled) {
        for (View view : tunables) {
            view.setEnabled(enabled);
            view.setAlpha(enabled ? 1f : DISABLED_ALPHA);
        }
    }

    /**
     * One row: title with its current value on the right, summary beneath, slider under that.
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
            final Label label) {

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(colorTitle);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleRow.addView(
                titleView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int current = preferences.getInt(key, fallback);

        final TextView valueView = new TextView(this);
        valueView.setText(label.of(current));
        valueView.setTextColor(colorAccent);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(valueView);

        parent.addView(titleRow, marginTop(dp(ROW_TOP_DP)));

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(colorSummary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        parent.addView(summaryView, marginTop(dp(TIGHT_DP)));

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(current - min);
        ColorStateList accent = ColorStateList.valueOf(colorAccent);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        int value = progress + min;
                        valueView.setText(label.of(value));
                        if (fromUser) {
                            preferences.edit().putInt(key, value).apply();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {}
                });
        parent.addView(bar, marginTop(dp(LOOSE_DP)));

        // The whole row dims together. The SeekBar is the only one that also has to stop
        // responding, which setEnabled(false) handles.
        tunables.add(titleView);
        tunables.add(valueView);
        tunables.add(summaryView);
        tunables.add(bar);
    }

    private LinearLayout.LayoutParams marginTop(int margin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
    }

    /**
     * The context whose SharedPreferences Gboard's store actually reads.
     *
     * <p><b>Not this Activity's.</b> Getting this wrong is why the sliders did nothing at all until
     * `v0.1.0-dev.7`: the file name was right and the file was the wrong one. `Lpnp;-><init>` ends
     * up in `Lpns;`, which does this before asking for the default preferences:
     *
     * <pre>
     *   v5 = context.getApplicationContext()
     *   if (!v5.isDeviceProtectedStorage()) v5 = v5.createDeviceProtectedStorageContext()
     *   PreferenceManager.getDefaultSharedPreferences(v5)
     * </pre>
     *
     * A device-protected context stores under {@code /data/user_de/…}, while an ordinary Activity
     * context stores under {@code /data/user/…} — same {@code <packageName>_preferences} name, two
     * unrelated files. Gboard needs the keyboard to work before the device is unlocked, which is
     * why it keeps its preferences in direct-boot storage.
     *
     * <p>Mirrored line for line rather than paraphrased, including the {@code getApplicationContext}
     * call, so the two sides cannot drift. There is no version guard because there is nothing to
     * guard against: both methods are API 24 and Gboard's manifest declares {@code minSdkVersion}
     * 26, so they are below the floor this code can ever run on. A guard would also be worse than
     * useless — falling back would silently return to reading the wrong file.
     */
    @SuppressLint("NewApi")
    private Context preferenceContext() {
        Context context = getApplicationContext();
        if (context.isDeviceProtectedStorage()) {
            return context;
        }
        Context deviceProtected = context.createDeviceProtectedStorageContext();
        return deviceProtected != null ? deviceProtected : context;
    }

    private int dp(int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
