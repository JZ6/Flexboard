package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.jz6.flexboard.extension.hotkey.Hotkey;
import dev.jz6.flexboard.extension.ime.ImeService;
import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * Merges Flexboard's toolbar buttons into Gboard's access-point list without destroying the
 * user's custom order.
 *
 * ## The contract
 *
 * Emitted blocks register each freshly built button with {@link #register(String, Object)} as
 * they run, then {@link #merge(List)} is called once at the end of the split method's insertion.
 * Two feature patches do this — the text actions and the custom hotkeys — and either works
 * alone, an empty registry is a no-op. Registration order is the canonical order: on a first
 * run, that is what goes to the front of the bar.
 *
 * ## Placement maths
 *
 * Gboard persists the customized toolbar order as a semicolon-joined string of access-point ids
 * under the {@code access_points_showing_order} preference (plus a fold-specific sibling for a
 * phone that opens into a tablet). The customize screen writes whatever is on the bar when the
 * user finishes, so ours land in the string exactly like Gboard's own.
 *
 * The order rebuild keeps only ids the registered providers know; ours are injected afterwards,
 * so this merge re-inserts them. For each id in the saved string:
 *
 *  - in the registry and not yet placed → insert at the current position, computed from the
 *    number of stock entries that preceded it (they sit in {@code out} in that relative order)
 *    plus however many of ours have already landed (each add shifts the rest down by one);
 *  - {@code flexboard_*} id missing from the registry → an emptied hotkey slot or a patch the
 *    user later deselected. It occupies nothing, so it counts nothing;
 *  - anything else → a stock entry and counts as one.
 *
 * A button never mentioned in the saved string is brand new: the whole unmentioned set goes to
 * the front, which is what a first run looks like and always did.
 *
 * ## Failure paths
 *
 * A preference written in the wrong type, a missing resource, a rearranged string — the merge
 * degrades to the canonical prepend rather than taking the keyboard down with it.
 */
public final class ToolbarMerge {

    /** Resource ids naming the order preference keys, pinned by {@code tools/apk/preflight.py}. */
    private static final int ORDER_KEY_ID = 0x7f1409b0;

    private static final int ORDER_KEY_FOLDABLE_ID = 0x7f140a44;

    private static final String TAG = "Flexboard";

    private static final String FLEXBOARD_PREFIX = "flexboard_";

    /** Ids and their freshly built buttons, in registration order. Cleared after every merge. */
    private static final List<Object> cohort = new ArrayList<>();

    private ToolbarMerge() {}

    /**
     * Called by patch-emitted bytecode once per button, right after the builder produces it.
     * A later call with the same id replaces the last button, so edits and icon overrides take
     * effect on rebuild without a restart.
     */
    public static synchronized void register(String id, Object ap) {
        // Empty hotkey slots never reach the bar. The slot number is derived at patch time so
        // the emission can stay label-free — every slot is built and registered, and the empty
        // ones are filtered here.
        for (int i = 0; i + 1 < cohort.size(); i += 2) {
            if (cohort.get(i).equals(id)) {
                cohort.set(i + 1, ap);
                return;
            }
        }
        int slot = hotkeySlotOf(id);
        if (slot >= 1 && !Hotkey.hasContent(slot)) {
            return;
        }
        cohort.add(id);
        cohort.add(ap);
    }

    /**
     * The slot number a hotkey id names, or {@code -1} for any other entry. Reading the slot lets
     * [register] drop an unfilled hotkey without the patch having to decide at emission time.
     */
    private static int hotkeySlotOf(String id) {
        if (!id.startsWith("flexboard_hotkey_")) {
            return -1;
        }
        String numeral = id.substring("flexboard_hotkey_".length());
        try {
            return Integer.parseInt(numeral);
        } catch (NumberFormatException badId) {
            return -1;
        }
    }

    /** Called from the emitted split-method epilogue, once per toolbar build. */
    public static List merge(List incoming) {
        synchronized (ToolbarMerge.class) {
            try {
                return mergeOrdered(incoming);
            } catch (Throwable error) {
                Log.w(TAG, "merge failed, falling back to canonical order", error);
                List<Object> out = new ArrayList<>(incoming);
                prependAll(out, cohort);
                return out;
            } finally {
                cohort.clear();
            }
        }
    }

    /**
     * Returns the list the bar should be built from: {@code incoming} with the registered
     * buttons placed per the saved custom order, or prepended canonicallly on its absence.
     */
    private static List mergeOrdered(List incoming) {
        List<Object> out = new ArrayList<>(incoming);

        String saved = readOrder();
        if (saved.isEmpty() || cohort.isEmpty()) {
            prependAll(out, cohort);
            return out;
        }

        Set<String> placedIds = new HashSet<>();
        boolean anyPlaced = false;
        int stock = 0;
        int inserted = 0;

        for (String id : saved.split(";")) {
            if (id.isEmpty()) {
                continue;
            }
            int index = indexOf(cohort, id);
            if (index >= 0) {
                if (placedIds.add(id)) {
                    int position = Math.min(stock + inserted, out.size());
                    out.add(position, cohort.get(index + 1));
                    inserted++;
                    anyPlaced = true;
                }
                // A duplicated id in the string counts as occupied once and is ignored.
            } else if (!id.startsWith(FLEXBOARD_PREFIX)) {
                stock++;
            }
        }

        if (!anyPlaced) {
            prependAll(out, cohort);
            return out;
        }

        // Buttons the user never dragged stay at the front, in canonical order — same as a first
        // run — so a freshly filled hotkey is still discoverable, and a slot emptied after a drag
        // leaves no hole.
        for (int i = cohort.size() - 2; i >= 0; i -= 2) {
            if (!placedIds.contains(cohort.get(i))) {
                out.add(0, cohort.get(i + 1));
            }
        }
        return out;
    }

    private static int indexOf(List cohort, Object id) {
        for (int i = 0; i + 1 < cohort.size(); i += 2) {
            if (cohort.get(i).equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The saved toolbar order string, preferring the main one; the fold's is a fallback. */
    private static String readOrder() {
        Context context = ImeService.get();
        if (context == null) {
            return "";
        }
        SharedPreferences preferences = Preferences.of(context);
        String main = preferences.getString(context.getString(ORDER_KEY_ID), null);
        if (main != null && !main.isEmpty()) {
            return main;
        }
        String fold = preferences.getString(context.getString(ORDER_KEY_FOLDABLE_ID), "");
        return fold == null ? "" : fold;
    }

    /** First-run placement: the whole set goes to the front, in registration order. */
    private static void prependAll(List out, List cohort) {
        for (int i = cohort.size() - 2; i >= 0; i -= 2) {
            out.add(0, cohort.get(i + 1));
        }
    }
}
