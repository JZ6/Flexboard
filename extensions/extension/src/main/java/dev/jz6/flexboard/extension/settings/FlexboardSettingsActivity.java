package dev.jz6.flexboard.extension.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import dev.jz6.flexboard.extension.hotkey.Hotkey;
import dev.jz6.flexboard.extension.prefs.Preferences;

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
 * <p><b>It writes to Gboard's own preference file, deliberately</b>, so the bytecode patches can
 * read every value back with Gboard's own accessor. Which file that is turns out to be the subtle
 * part — it is not this Activity's — and the reasoning lives in {@link Preferences}, which the
 * keyboard-side hotkey actions share.
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

    /**
     * Must match STEP_SCALE_DEFAULT in ScrubTuningPatch.kt, and STEP_SCALE in {@link
     * dev.jz6.flexboard.extension.prefs.Defaults}, which writes it on first run.
     *
     * <p>Only shown, in practice: the seed means the preference is set before this screen can be
     * opened, so the slider reads a stored value rather than falling back to this one.
     */
    private static final int STEP_SCALE_DEFAULT = 60;

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

    /**
     * Must match TOOLBAR_COUNT_UNFOLDED_KEY in ToolbarCountPatch.kt.
     *
     * <p>Applies only while the device reports itself as a foldable, which in practice means the
     * large inner screen of an open fold. Gboard keeps its own count per device class for the same
     * reason, so a single value for both screens would be a change from stock rather than a
     * feature.
     *
     * <p>The patch falls back to {@link #KEY_TOOLBAR_COUNT} when this is unset, which is now only
     * reachable if the first-run seed did not happen: {@code Defaults} writes both, so in practice
     * each slider owns its screen.
     */
    private static final String KEY_TOOLBAR_COUNT_UNFOLDED = "flexboard_toolbar_count_unfolded";

    /** Must match TOOLBAR_COUNT_MIN / TOOLBAR_COUNT_MAX in ToolbarCountPatch.kt. */
    private static final int TOOLBAR_COUNT_MIN = 3;

    private static final int TOOLBAR_COUNT_MAX = 12;

    /**
     * The starting counts, seeded on first run by {@link dev.jz6.flexboard.extension.prefs.Defaults}
     * — these must match the values it writes.
     *
     * <p>They used to be one number, 5, which was Gboard's own and was <i>only</i> displayed:
     * neither of the toolbar patch's insertions used it, so an untouched slider left the count
     * wherever Gboard put it. Now the value is written, so what the slider shows and what the
     * keyboard does are the same thing by construction.
     *
     * <p>Twelve unfolded because the inner screen of a fold fits them, and because Gboard already
     * keeps a count per device class — the two screens differing is stock behaviour. Each slider
     * owns its screen; neither falls back to the other.
     */
    private static final int TOOLBAR_COUNT_DEFAULT = 6;

    private static final int TOOLBAR_COUNT_UNFOLDED_DEFAULT = 12;

    /** Must match HOTKEY_SLOT_COUNT in TextActionsPatch.kt. */
    private static final int HOTKEY_SLOT_COUNT = 6;

    /**
     * The icon each hotkey slot wears on the toolbar, drawn here beside the field that fills it.
     *
     * <p>These are Gboard's own drawable ids, and they resolve because this Activity is merged into
     * Gboard's APK rather than shipped as its own app — {@code getResources()} is Gboard's. Nothing
     * numbered is available (Gboard bundles 29 Material shapes and none of them is a digit), so the
     * icons are arbitrary markers and the preview is what makes them learnable: the user picks the
     * star while looking at the star.
     *
     * <p>Must match HOTKEY_ICON_1 through HOTKEY_ICON_6 in TextActionsPatch.kt.
     */
    private static final int HOTKEY_ICON_1 = 0x7f080239;

    private static final int HOTKEY_ICON_2 = 0x7f0806fc;
    private static final int HOTKEY_ICON_3 = 0x7f080215;
    private static final int HOTKEY_ICON_4 = 0x7f08074e;
    private static final int HOTKEY_ICON_5 = 0x7f080733;
    private static final int HOTKEY_ICON_6 = 0x7f080219;

    private static final int[] HOTKEY_ICONS = {
        HOTKEY_ICON_1, HOTKEY_ICON_2, HOTKEY_ICON_3, HOTKEY_ICON_4, HOTKEY_ICON_5, HOTKEY_ICON_6,
    };

    private static final String TITLE = "Flexboard";
    private static final String SUBTITLE = "Swipe anywhere to delete the previous word.";
    private static final String SECTION = "Swipe to delete";
    private static final String SECTION_TOOLBAR = "Toolbar";
    private static final String SECTION_HOTKEYS = "Hotkeys";

    private static final String HOTKEYS_SUMMARY =
            "Buttons that type a string you choose. Fill one in and its icon appears on the "
                    + "toolbar; clear it and the button goes away again. The icon is how you tell "
                    + "them apart, so the one shown here is the one you will be tapping.";

    private static final String HOTKEY_TITLE = "Hotkey";

    private static final String HOTKEY_HINT = "Empty — no button";

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

    /** Matches the 24dp Material icons are drawn at, which is what these are. */
    private static final int ICON_DP = 24;


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

        preferences = Preferences.of(this);

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

        // Swipe length slider disabled — the scaling code is commented out in ScrubTuningPatch.
        //addSlider(
        //        column,
        //        KEY_STEP_SCALE,
        //        "Swipe length",
        //        "How far to swipe per deleted word, as a percent of Gboard's own distance. "
        //                + "Lower deletes more words for the same swipe.",
        //        STEP_SCALE_MIN,
        //        STEP_SCALE_MAX,
        //        STEP_SCALE_DEFAULT,
        //        value -> value + "%");

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

        addSectionHeader(column, SECTION_TOOLBAR);

        addSlider(
                column,
                KEY_TOOLBAR_COUNT,
                "Icons on the toolbar",
                "How many icons fit on the toolbar above the keyboard. The rest stay in the "
                        + "overflow menu behind the chevron. More icons means narrower ones.",
                TOOLBAR_COUNT_MIN,
                TOOLBAR_COUNT_MAX,
                TOOLBAR_COUNT_DEFAULT,
                value -> Integer.toString(value));

        addSlider(
                column,
                KEY_TOOLBAR_COUNT_UNFOLDED,
                "Icons when unfolded",
                "Foldables only. Overrides the setting above while the phone is open, because the "
                        + "inner screen is wider and fits more. It has its own value rather than "
                        + "following the setting above.",
                TOOLBAR_COUNT_MIN,
                TOOLBAR_COUNT_MAX,
                TOOLBAR_COUNT_UNFOLDED_DEFAULT,
                value -> Integer.toString(value));

        addSectionHeader(column, SECTION_HOTKEYS);

        TextView hotkeys = new TextView(this);
        hotkeys.setText(HOTKEYS_SUMMARY);
        hotkeys.setTextColor(colorSummary);
        hotkeys.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
        column.addView(hotkeys, marginTop(dp(LOOSE_DP)));

        for (int slot = 1; slot <= HOTKEY_SLOT_COUNT; slot++) {
            addHotkeyField(column, slot, HOTKEY_ICONS[slot - 1]);
        }

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

    /**
     * One hotkey row: the slot's toolbar icon, its name, and the string it types.
     *
     * <p><b>The icon is the point of the row.</b> Six hotkeys are six identical buttons on the bar
     * unless the user can remember which shape does what, and the reliable moment to learn that is
     * while typing the string. So the row shows the actual drawable the button will wear, loaded
     * out of the host APK by id.
     *
     * <p>Written on every keystroke rather than on focus loss, matching the sliders: the value is
     * already stored if the screen is dismissed mid-edit, and the keyboard rereads it either way.
     */
    private void addHotkeyField(LinearLayout parent, final int slot, int iconResource) {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        Drawable glyph = drawable(iconResource);
        if (glyph != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(glyph);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP));
            iconParams.rightMargin = dp(LOOSE_DP + TIGHT_DP);
            titleRow.addView(icon, iconParams);
        }

        TextView titleView = new TextView(this);
        titleView.setText(HOTKEY_TITLE + " " + slot);
        titleView.setTextColor(colorTitle);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP);
        titleRow.addView(
                titleView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(titleRow, marginTop(dp(ROW_TOP_DP)));

        final String key = Hotkey.keyFor(slot);
        final EditText field = new EditText(this);
        field.setText(preferences.getString(key, ""));
        field.setHint(HOTKEY_HINT);
        field.setTextColor(colorTitle);
        field.setHintTextColor(colorSummary);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, SUMMARY_SP);
        // Multi-line, because a signature or an address is a perfectly reasonable thing to want on
        // a button. Only the first line becomes the button's name; the whole of it gets typed.
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setBackgroundTintList(ColorStateList.valueOf(colorAccent));
        field.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(
                            CharSequence text, int start, int count, int after) {}

                    @Override
                    public void onTextChanged(CharSequence text, int start, int before, int count) {}

                    @Override
                    public void afterTextChanged(Editable text) {
                        preferences.edit().putString(key, text.toString()).apply();
                    }
                });
        parent.addView(field, marginTop(dp(TIGHT_DP)));
    }

    /**
     * One of Gboard's own drawables, or {@code null} if that id no longer names one.
     *
     * <p>The ids are pinned to a single Gboard build, the same way the buttons themselves are, and
     * {@code tools/apk/preflight.py} fails the build when one stops drawing the expected glyph. If
     * one slips through anyway, a hotkey row should lose its picture rather than the settings
     * screen losing its ability to open.
     *
     * <p><b>Mutated before tinting.</b> These drawables are Gboard's, shared by constant state with
     * wherever else Gboard draws them, so tinting the original would recolour them across the app.
     */
    private Drawable drawable(int id) {
        try {
            Drawable glyph = getResources().getDrawable(id, getTheme());
            if (glyph == null) {
                return null;
            }
            glyph = glyph.mutate();
            glyph.setTint(colorTitle);
            return glyph;
        } catch (RuntimeException notFound) {
            return null;
        }
    }

    private LinearLayout.LayoutParams marginTop(int margin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
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
