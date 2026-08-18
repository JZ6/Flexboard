package dev.jz6.flexboard.extension.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
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
import android.widget.TextView;

/**
 * Flexboard's settings screen.
 *
 * <p>This class is merged into Gboard's APK rather than shipped as its own app, so it cannot rely
 * on any resources of its own resolving at runtime. Every view is built in code, every string is a
 * constant, and only framework widgets are used — no AndroidX, no layout inflation, nothing that
 * assumes anything about the host.
 *
 * <p><b>It is meant to look like a Gboard settings screen.</b> It cannot be one: androidx's
 * preference rows are unavailable to a class that ships no resources, so the rows here are
 * hand-built to approximately androidx's metrics — 16dp insets, 16sp titles, 14sp summaries,
 * sentence-case category headers.
 *
 * <p>What it does not approximate is the palette. {@code SettingsScreenPatch} copies Gboard's own
 * settings theme onto this Activity's manifest entry, so the colours are read from theme attributes
 * and come out identical to the screens either side of it, Material You included. Only if an
 * attribute fails to resolve does it fall back to a fixed pair of palettes chosen by
 * {@link Configuration#uiMode}, which is what every build before this one used and why the screen
 * looked foreign on a themed device.
 *
 * <p><b>Chrome depends on what that theme provides.</b> An earlier attempt at
 * {@code Theme.DeviceDefault.Settings} clipped its top row: Gboard targets SDK 37, so on Android 15
 * and up the window is edge-to-edge and content starts underneath the status bar unless something
 * insets it — and if an action bar has already absorbed that inset, applying it again is just as
 * wrong. So both cases are handled rather than assumed: the title comes from the action bar when
 * there is one and from {@link #addHeading} when there is not, and the top inset is applied only in
 * the latter case. See {@link #hasActionBar()} for why a theme with a bar does not guarantee this
 * Activity gets one.
 *
 * <p><b>It writes to Gboard's own preference file, deliberately.</b> Gboard's store
 * (<code>Lqhy;</code>) is constructed with a null name, which resolves to
 * <code>PreferenceManager.getDefaultSharedPreferences</code> — that is
 * <code>&lt;packageName&gt;_preferences</code> in <code>MODE_PRIVATE</code>, on a
 * <b>device-protected</b> context. See {@link #preferenceContext()} — that last part is not a
 * detail but a different file on disk, and getting it wrong is why every slider on this screen did
 * nothing at all before <code>v0.1.0-dev.7</code>. Deriving the name from
 * {@link #getPackageName()} is what keeps it correct after the package-rename patch, since both
 * sides resolve the same running package.
 *
 * <p>The keys must match the ones the bytecode patches read. They are duplicated as literals in
 * <code>ScrubTuningPatch.kt</code> and <code>ToolbarCountPatch.kt</code>, because a patch-added
 * resource has no id until aapt2 recompiles and so cannot be addressed from bytecode.
 * <code>check_shared_constants.py</code> fails the build when the two sides disagree.
 *
 * <p><b>Every section is shown whether or not its patch was applied.</b> The screen is one merged
 * class and cannot know which patches the user ticked, so a slider for a patch that is not installed
 * moves and stores and does nothing. Grouping by feature is what makes that legible; making it
 * conditional would need a marker preference written at app start, which is another bytecode
 * insertion for a cosmetic gain.
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
    /** Must match STEP_SCALE_DEFAULT in ScrubTuningPatch.kt. */
    private static final int STEP_SCALE_DEFAULT = 100;

    private static final int MAX_WORDS_MIN = 1;
    /**
     * The slider's top position, and "no limit" — the clamp is skipped at or above it. Must match
     * MAX_WORDS_NO_LIMIT in ScrubTuningPatch.kt. Kept separate from the default: they were one
     * constant until the default moved to 1, and sharing them would put the sentinel at 1 and so
     * disable the cap at every setting.
     */
    private static final int MAX_WORDS_MAX = 10;
    /** Must match MAX_WORDS_DEFAULT in ScrubTuningPatch.kt. */
    private static final int MAX_WORDS_DEFAULT = 1;

    private static final int HOLD_DELAY_MIN = 0;
    private static final int HOLD_DELAY_MAX = 300;
    private static final int HOLD_DELAY_DEFAULT = 0;

    /** Must match TOOLBAR_COUNT_KEY in ToolbarCountPatch.kt. */
    private static final String KEY_TOOLBAR_COUNT = "flexboard_toolbar_count";

    /** Must match TOOLBAR_COUNT_MIN / TOOLBAR_COUNT_MAX in ToolbarCountPatch.kt. */
    private static final int TOOLBAR_COUNT_MIN = 3;

    private static final int TOOLBAR_COUNT_MAX = 10;

    /**
     * Gboard's own stock count, shown while the preference is unset.
     *
     * <p>Only ever displayed. The patch does not use it: it reads the preference with whatever
     * Gboard itself computed as the default, so an untouched slider leaves the ceiling exactly where
     * Gboard put it even if that is not this number. It is checked against the literal in
     * `AccessPointsBar.<init>` by `tools/apk/preflight.py`, so what the slider shows stays truthful.
     */
    private static final int TOOLBAR_COUNT_DEFAULT = 5;

    private static final String TITLE = "Flexboard";
    private static final String SUBTITLE = "Swipe anywhere to delete the previous word.";
    private static final String SECTION = "Swipe to delete";
    private static final String SECTION_TOOLBAR = "Toolbar";

    private static final String TAKES_EFFECT =
            "Changes apply the next time the keyboard is opened.";

    /**
     * Fallbacks only.
     *
     * <p>The palette used to be picked from these by {@link Configuration#uiMode}, which is why the
     * screen looked foreign next to Gboard's own: Gboard follows its theme, and on Android 12+ that
     * includes Material You, so a fixed pair of palettes is the wrong shade on any themed device
     * rather than merely a different one. The colours now come from the theme the manifest entry
     * carries — Gboard's own settings theme — and these are what is used for any attribute that
     * does not resolve.
     */
    private static final int COLOR_DARK_BACKGROUND = 0xFF202124;

    private static final int COLOR_DARK_TITLE = 0xFFE8EAED;
    private static final int COLOR_DARK_SUMMARY = 0xFF9AA0A6;
    private static final int COLOR_DARK_ACCENT = 0xFF8AB4F8;

    private static final int COLOR_LIGHT_BACKGROUND = 0xFFFFFFFF;
    private static final int COLOR_LIGHT_TITLE = 0xFF1F1F1F;
    private static final int COLOR_LIGHT_SUMMARY = 0xFF5F6368;
    private static final int COLOR_LIGHT_ACCENT = 0xFF0B57D0;

    /** Above this, the background is light enough to need dark status-bar icons. */
    private static final double LIGHT_BACKGROUND_LUMINANCE = 0.5d;

    /**
     * Metrics taken from androidx's preference rows, which is what every other screen in Gboard's
     * settings is built from.
     *
     * <p>They are approximated rather than inherited: an extension merges DEX and not resources, so
     * nothing here can reference {@code preference_material} or any dimension from it. 16dp is the
     * horizontal inset an androidx preference row uses, and the vertical rhythm is chosen to sit at
     * about the same density.
     */
    private static final int EDGE_DP = 16;

    private static final int ROW_TOP_DP = 16;
    private static final int TIGHT_DP = 2;
    private static final int LOOSE_DP = 8;

    /** Preference rows: 16sp titles, 14sp summaries, and category headers at the summary size. */
    private static final int TITLE_SP = 16;

    private static final int SUMMARY_SP = 14;
    private static final int SECTION_SP = 14;
    private static final int VALUE_SP = 14;

    /** Only used when the theme gives no action bar to put the title in. */
    private static final int HEADING_SP = 28;


    /** Renders the stored int as the value shown beside a row's title. */
    private interface Label {
        String of(int value);
    }

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
        colorBackground =
                themeColor(
                        android.R.attr.colorBackground,
                        night ? COLOR_DARK_BACKGROUND : COLOR_LIGHT_BACKGROUND);
        colorTitle =
                themeColor(
                        android.R.attr.textColorPrimary,
                        night ? COLOR_DARK_TITLE : COLOR_LIGHT_TITLE);
        colorSummary =
                themeColor(
                        android.R.attr.textColorSecondary,
                        night ? COLOR_DARK_SUMMARY : COLOR_LIGHT_SUMMARY);
        colorAccent =
                themeColor(
                        android.R.attr.colorAccent, night ? COLOR_DARK_ACCENT : COLOR_LIGHT_ACCENT);

        // Driven by the colour actually resolved rather than by uiMode, because the two can
        // disagree: the theme is Gboard's, and nothing guarantees it follows the system night
        // setting. Asking the background how light it is cannot be wrong in the way asking the
        // configuration can.
        if (isLight(colorBackground)) {
            requestDarkSystemBarIcons();
        }

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP), dp(EDGE_DP * 2));

        addSectionHeader(column, SECTION);

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
                MAX_WORDS_MAX,
                MAX_WORDS_DEFAULT,
                value -> value >= MAX_WORDS_MAX ? "No limit" : Integer.toString(value));

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

        // WITHHELD alongside the patch that reads it — see the note at the top of
        // ToolbarCountPatch.kt. A slider whose value nothing acts on is worse than no slider, so it
        // is not rendered. The constants stay declared so check_shared_constants.py keeps both
        // sides in step while the patch is being fixed.
        //
        // addSectionHeader(column, SECTION_TOOLBAR);
        //
        // addSlider(
        //         column,
        //         KEY_TOOLBAR_COUNT,
        //         "Icons on the toolbar",
        //         "How many icons fit on the toolbar above the keyboard. The rest stay in the "
        //                 + "overflow menu behind the chevron. More icons means narrower ones.",
        //         TOOLBAR_COUNT_MIN,
        //         TOOLBAR_COUNT_MAX,
        //         TOOLBAR_COUNT_DEFAULT,
        //         value -> Integer.toString(value));

        TextView footnote = new TextView(this);
        footnote.setText(TAKES_EFFECT);
        footnote.setTextColor(colorSummary);
        footnote.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
        column.addView(footnote, marginTop(dp(EDGE_DP)));

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

        setContentView(scroll);

        // Only now. `getActionBar()` is null until the decor view exists, which `setContentView`
        // is what creates — asking any earlier reports "no action bar" on a theme that has one,
        // and the screen would draw its own heading underneath a real title bar.
        //
        // The title is rendered exactly once either way: by the bar when there is one, by
        // `addHeading` inserted at the top of the column when there is not.
        if (hasActionBar()) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        } else {
            addHeading(column, 0);
        }

        // After the action bar is known, because whether the top inset has already been consumed
        // depends on it.
        padBySystemBars(scroll);
    }

    /**
     * Whether the theme gave this Activity an action bar.
     *
     * <p><b>Not something to assume either way.</b> The manifest entry now carries Gboard's own
     * settings theme rather than {@code NoActionBar}, so there may be one — but this is a plain
     * {@link Activity}, and a theme deriving from AppCompat or Material provides its action bar
     * through {@code AppCompatActivity} rather than the framework. So Gboard's screen can have a bar
     * while this one, under the identical theme, has none. Asking is cheap; guessing shows up as
     * either a missing title or two of them.
     */
    private boolean hasActionBar() {
        return getActionBar() != null;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Insets the view by the system bars.
     *
     * <p>This is the whole fix for the clipped first row. Gboard targets SDK 37, so on Android 15+
     * the window is edge-to-edge and content starts under the status bar unless something insets it.
     *
     * <p><b>The top is skipped when there is an action bar</b>, because the bar has already consumed
     * it — applying it again pushes the first row down by the height of the status bar a second
     * time. Which of the two is inset is exactly the arithmetic that made the original
     * {@code Theme.DeviceDefault.Settings} attempt clip its first row.
     */
    private void padBySystemBars(final View view) {
        final boolean topAlreadyConsumed = hasActionBar();
        view.setOnApplyWindowInsetsListener(
                (target, windowInsets) -> {
                    int[] bars =
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                    ? systemBarsApi30(windowInsets)
                                    : systemBarsLegacy(windowInsets);
                    target.setPadding(
                            bars[0], topAlreadyConsumed ? 0 : bars[1], bars[2], bars[3]);
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

    /**
     * Stands in for the action bar, for themes that do not provide one.
     *
     * <p>Only called when {@link #hasActionBar()} is false — see the note there for why both cases
     * have to be handled rather than one assumed.
     */
    private void addHeading(LinearLayout parent, int index) {
        TextView subtitle = new TextView(this);
        subtitle.setText(SUBTITLE);
        subtitle.setTextColor(colorSummary);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
        parent.addView(subtitle, index, marginTop(dp(TIGHT_DP)));

        TextView heading = new TextView(this);
        heading.setText(TITLE);
        heading.setTextColor(colorTitle);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADING_SP);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        // Inserted ahead of the subtitle, so the pair reads title-then-subtitle. Both go in at
        // `index` because this runs after the column is already populated.
        parent.addView(heading, index);
    }

    /**
     * A category header, matching how androidx renders one.
     *
     * <p>Sentence case and regular weight, not the ALL-CAPS bold this used to draw. Of everything on
     * the screen this was the most recognisable tell: no other header in Gboard's settings is
     * capitalised that way, so it read as belonging to a different app at a glance.
     */
    private void addSectionHeader(LinearLayout parent, String label) {
        TextView section = new TextView(this);
        section.setText(label);
        section.setTextColor(colorAccent);
        section.setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_SP);
        parent.addView(section, marginTop(dp(EDGE_DP + LOOSE_DP)));
    }

    /** Run when a switch changes, for the one that also drives something on screen. */
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
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
        titleRow.addView(
                titleView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final int current = preferences.getInt(key, fallback);

        final TextView valueView = new TextView(this);
        valueView.setText(label.of(current));
        valueView.setTextColor(colorAccent);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_SP);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(valueView);

        parent.addView(titleRow, marginTop(dp(ROW_TOP_DP)));

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(colorSummary);
        summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
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
     * `v0.1.0-dev.7`: the file name was right and the file was the wrong one. `Lqhy;-><init>` does
     * this before asking for the default preferences:
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

    /**
     * A colour from the activity's theme, or {@code fallback} if the attribute does not resolve.
     *
     * <p>Only <b>framework</b> attributes are read. Gboard's settings theme is whatever its manifest
     * points at and may derive from AppCompat or Material rather than a platform theme, so nothing
     * here can assume a library attribute exists — but {@code android.R.attr} is present whatever
     * the parent is.
     *
     * <p>Both forms are handled because the text colours are {@link ColorStateList}s while the
     * background and accent are plain colours, and a theme is free to supply either.
     */
    private int themeColor(int attribute, int fallback) {
        TypedArray values = getTheme().obtainStyledAttributes(new int[] {attribute});
        try {
            ColorStateList stateList = values.getColorStateList(0);
            if (stateList != null) {
                return stateList.getDefaultColor();
            }
            return values.getColor(0, fallback);
        } catch (RuntimeException resolutionFailed) {
            // A theme can point an attribute at something that is not a colour at all. Falling
            // back beats taking the screen down over a palette.
            return fallback;
        } finally {
            values.recycle();
        }
    }

    /**
     * Whether {@code color} is light enough to need dark icons drawn over it.
     *
     * <p>Rec. 601 luma, which is what {@code Color.luminance} would give but without requiring the
     * API level that added it.
     */
    private static boolean isLight(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        double luma = (0.299d * red + 0.587d * green + 0.114d * blue) / 255d;
        return luma > LIGHT_BACKGROUND_LUMINANCE;
    }

    private int dp(int value) {
        return Math.round(
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
