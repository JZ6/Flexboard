package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import dev.jz6.flexboard.extension.ime.ImeService;
import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * Merges Flexboard's toolbar buttons into Gboard's access-point list without destroying the
 * user's custom order.
 *
 * <p>Gboard persists the customized toolbar order as a semicolon-joined string of access-point
 * ids under the {@code access_points_showing_order} preference (and
 * {@code foldable_access_points_showing_order} for a fold's inner screen). Dragging a Flexboard
 * button in Gboard's own customize UI writes its id into that string exactly like a Gboard
 * button — the write side captures the bar as the user arranged it, ours included.
 *
 * <p>The read side is where a blind injector loses the arrangement: the order rebuilder keeps
 * only ids known to the registered providers, and ours were never registered (we inject into
 * the finished list, after the provider machinery). The old insertion compensated for that by
 * pinning every button at a hardcoded index on each rebuild, which on the device reads as
 * <i>the drag never happened</i> — and when the rebuild did see leftover ids, added a second
 * copy beside them.
 *
 * <p>This class decides placement instead. The patch builds its buttons and hands them over
 * interleaved with their ids ({@code pairs}, the same list a {@code switch} on flat registers
 * could emit: {@code id, ap, id, ap, …}). The merge walks the <b>saved order string</b> — which
 * still carries the {@code flexboard_*} ids the user dragged around — and inserts each button
 * where the user gave it. A button never mentioned in the saved order is brand new: the whole
 * set goes to the front, which is what a first run looks like and always did. Because the merge
 * positions by the persisted string rather than the rebuild, the order survives kills, reboots,
 * and reopens.
 *
 * <p><b>Position maths.</b> {@code incoming} holds the Gboard buttons in the saved order's
 * relative sequence, so a Flexboard button belongs after the count of non-Flexboard ids that
 * precede it in the string; counting only what precedes it before the add accounts for the
 * shift each insert exerts on later entries.
 *
 * <p><b>Empty hotkeys.</b> The patch doesn't build buttons for empty slots, so their ids are
 * absent from {@code pairs}; a saved entry for a since-emptied hotkey matches nothing and
 * vanishes, as it should.
 *
 * <p><b>Foldables.</b> The inner screen keeps its own order string, written only once the user
 * has customized the inner bar. When it is empty we fall back to the main one — the same
 * inheritance Gboard's own migration performs.
 *
 * <p><b>When the string is unreadable.</b> No IME service yet, no preference yet, tampered
 * file: the merge reduces to the canonical front prepend, exactly the old first-run behaviour.
 */
public final class ToolbarMerge {

    /** Resource ids naming the order preference keys, pinned by {@code tools/apk/preflight.py}. */
    private static final int ORDER_KEY_ID = 0x7f1409b0;

    private static final int ORDER_KEY_FOLDABLE_ID = 0x7f140a44;

    private static final String FLEXBOARD_PREFIX = "flexboard_";

    private ToolbarMerge() {}

    /**
     * Returns the list the bar should be built from: {@code incoming} with Flexboard's buttons
     * placed per the saved custom order, or prepended in canonical order on its absence.
     *
     * @param incoming Gboard's provider-built access points, already in display order.
     * @param pairs    {@code id, accessPoint, id, accessPoint, …} for every Flexboard button
     *                 built this pass, in canonical order (text actions first, then hotkeys in
     *                 slot order).
     */
    public static List merge(List incoming, List pairs) {
        List<Object> out = new ArrayList<>(incoming);

        String saved = readOrder();
        if (saved.isEmpty()) {
            prependAll(out, pairs);
            return out;
        }

        boolean anyPlaced = false;
        boolean[] placed = new boolean[pairs.size()];
        int gboardSeen = 0;
        int inserted = 0;

        for (String id : saved.split(";")) {
            int index = indexOf(pairs, id);
            if (index >= 0 && !placed[index]) {
                // After every Gboard entry seen so far (they sit in `out` in that relative
                // order) plus every Flexboard button placed before this one (each insert
                // shifted the tail down by one).
                int position = Math.min(gboardSeen + inserted, out.size());
                out.add(position, pairs.get(index + 1));
                placed[index] = true;
                inserted++;
                anyPlaced = true;
            } else if (!id.startsWith(FLEXBOARD_PREFIX)) {
                gboardSeen++;
            }
        }

        if (!anyPlaced) {
            prependAll(out, pairs);
            return out;
        }

        // Slots the user never dragged were not part of their arrangement; newly filled hotkeys
        // and untouched text actions keep showing at the front, same as a first run.
        for (int i = placed.length - 2; i >= 0; i -= 2) {
            if (!placed[i]) {
                out.add(0, pairs.get(i + 1));
            }
        }
        return out;
    }

    private static int indexOf(List pairs, Object id) {
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            if (pairs.get(i).equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The saved toolbar order string, preferring the fold-specific one when written. */
    private static String readOrder() {
        Context context = ImeService.get();
        if (context == null) {
            return "";
        }
        SharedPreferences preferences = Preferences.of(context);
        String fold = preferences.getString(context.getString(ORDER_KEY_FOLDABLE_ID), null);
        if (fold != null && !fold.isEmpty()) {
            return fold;
        }
        String main = preferences.getString(context.getString(ORDER_KEY_ID), "");
        return main == null ? "" : main;
    }

    /** First-run placement: the whole set goes to the front, in canonical order. */
    private static void prependAll(List out, List pairs) {
        for (int i = pairs.size() - 2; i >= 0; i -= 2) {
            out.add(0, pairs.get(i + 1));
        }
    }
}
