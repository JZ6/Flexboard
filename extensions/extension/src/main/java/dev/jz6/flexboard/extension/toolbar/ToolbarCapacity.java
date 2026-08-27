package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.content.SharedPreferences;

import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * The answer to "how many buttons can the bar hold?", read by the emitted smali at the bar's
 * constructor seam (plan and chain-trace: {@code docs/toolbar-capacity.md}).
 *
 * <p>Gboard's own stock chain is {@code min(pref, capacity, order-length)}: the user preference
 * unset on stock, the capacity a per-layout/phenotype window. We don't replace any of it —
 * the seam only ever raises {@code capacity}, and the slider's number is staged into Gboard's
 * own count preferences (both phone and foldable keys) so the min() stops seeing the unset
 * path: with a slider above stock, {@code min(slider, raised, order)} resolves to the slider —
 * pinnably. With the slider at 0 we change nothing at all, stock verbatim.
 *
 * <p>Values arrive as a persisted String (the settings rows store text through the bridge), so
 * the read is getString-and-parse — the same shape the swipe keys needed, for the same reason.
 */
public final class ToolbarCapacity {

    /** The slider's key, paired with the patch's TOOLBAR_MAX_KEY by the constants checker. */
    private static final String KEY = "flexboard_toolbar_max";

    /**
     * The ids under which Gboard's own count-preference strings live (the read in
     * {@code Lmku.b(I)I} picks between them by device class — the foldable branch is theirs,
     * not ours). Pinned by *value* in preflight: a bump that renumbers the string resources
     * moves the ids and the guard there fails, instead of us silently staging keys nobody reads.
     */
    private static final int COUNT_ON_BAR_ID = 0x7f1409af;
    private static final int FOLDABLE_COUNT_ON_BAR_ID = 0x7f140a43;

    private ToolbarCapacity() {}

    /**
     * What the bar should allow: {@code stock} when the slider is unset-or-0, else the slider's
     * larger number. Stages the stock count prefs only when actually raising.
     */
    public static int maxFor(Context context, int stock) {
        SharedPreferences prefs = Preferences.of(context);
        String raw = prefs.getString(KEY, null);
        int value = 0;
        if (raw != null) {
            try {
                value = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // the store only ever writes digits; a junk value read as 0 = leave stock alone
            }
        }
        if (value <= stock) {
            return stock;
        }
        stageCountPreference(prefs, context, COUNT_ON_BAR_ID, value);
        stageCountPreference(prefs, context, FOLDABLE_COUNT_ON_BAR_ID, value);
        return value;
    }

    /**
     * Writes one of Gboard's own keys by its resource-id name — the same recipe as the
     * force-prefs writers: the key STRING is a resource's value, resolved at runtime, so it
     * follows the package rename for free. Only written when the value isn't already right,
     * so a drag in Customize between reads isn't clobbered unless the slider's number changed.
     */
    private static void stageCountPreference(SharedPreferences prefs, Context context,
            int stringId, int value) {
        String key = context.getString(stringId);
        if (prefs.contains(key) && prefs.getInt(key, -1) == value) {
            return;
        }
        prefs.edit().putInt(key, value).apply();
    }
}
