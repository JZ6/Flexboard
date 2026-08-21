package dev.jz6.flexboard.extension.toolbar;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import dev.jz6.flexboard.extension.hotkey.Hotkey;

/**
 * Merges Flexboard's toolbar buttons into Gboard's access-point list.
 *
 * ## Why it does not persist placement
 *
 * Gboard persists the customized order as a semicolon-joined string of provider id fragments.
 * When the customize flow rebuilds the order, it rebuilds from the ids Gboard knows — the id
 * registry that backs its providers. Ours never come from a provider, they are injected by a
 * patch, so they never make it into the persisted string and no drag on the customize screen
 * would persist for them anyway. Living with that is the whole design: our buttons sit at the
 * front in registration order, on every rebuild, like bookmarks before the page list.
 *
 * The toolbar is one of the paths Gboard rebuilds the most aggressively (on any keyboard-open,
 * rotate or config change), so a deterministic placement is what the user counts on.
 *
 * ## Failure paths
 *
 * A wrong-typed preference, a bad registration, any of it must not surface as a crash. The
 * merge degrades to nothing beyond logging.
 */
public final class ToolbarMerge {

    private static final String TAG = "Flexboard";

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
                Log.w(TAG, "merge failed; passing through unchanged", error);
                return new ArrayList<>(incoming);
            } finally {
                cohort.clear();
            }
        }
    }

    /**
     * Returns the list the bar should be built from: the registered buttons at the front, in
     * registration order, followed by Gboard's own entries untouched.
     */
    private static List mergeOrdered(List incoming) {
        List<Object> out = new ArrayList<>(incoming);
        // Insert backwards so index-0 drops end up canonical after the loop.
        for (int i = cohort.size() - 2; i >= 0; i -= 2) {
            out.add(0, cohort.get(i + 1));
        }
        return out;
    }
}
