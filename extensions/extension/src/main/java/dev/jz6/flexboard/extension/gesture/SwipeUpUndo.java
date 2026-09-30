package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;
import android.view.MotionEvent;

/**
 * Decides, event by event, whether a swipe up should be taken over and turned into an undo.
 *
 * <p>Called from the scrub engine's {@code g(MotionEvent)} — the motion-event-handler layer, which
 * sees DOWN, every MOVE and UP for the keyboard whether or not the key pipeline still considers the
 * finger to be on a key. The diagnostic ({@link FlickProbe}) measured real flicks there on a device:
 * most came back {@code 6m}, meaning the 24dp threshold and the corridor were met <em>during</em> the
 * swipe. That is what makes this possible. On release the key handler types the letter before the
 * scrub handler sees the event, so the only way to stop the letter is to take the swipe over while it
 * is still moving.
 *
 * <p>This class only decides. The takeover itself, and sending the undo, are done by the emission in
 * Gboard's own terms, so no obfuscated Gboard type is compiled into the extension — a Gboard update
 * that renames them becomes a refused patch rather than a keyboard that crashes on load.
 *
 * <p>Returns:
 * <ul>
 *   <li>{@link #PASS}: not ours; the scrub engine handles the event as it always has.</li>
 *   <li>{@link #CLAIM}: this move completed a swipe up. Take the gesture over and send the undo.</li>
 *   <li>{@link #SWALLOW}: the gesture is already ours; keep the scrub engine's own logic out of it.
 *       Its end-of-gesture reset still runs, because the emission jumps to it rather than
 *       returning.</li>
 * </ul>
 *
 * <p>Releasing the takeover is not this class's job and does not depend on it: Gboard's dispatcher
 * clears the owner after every UP and CANCEL, whatever the handler did with the event.
 */
public final class SwipeUpUndo {

    public static final int PASS = 0;
    public static final int CLAIM = 1;
    public static final int SWALLOW = 2;

    /** Every scrub subclass shares {@code g}; only one may act, or one swipe would undo twice. */
    private static final String ACTING_HANDLER = "ScrubDeleteMotionEventHandler";

    private static final int SLOTS = 16;

    /** The values the diagnostic measured against on a device, and found to work. */
    private static final float FLICK_DP = 24f;
    private static final float CORRIDOR_RATIO = 2f;

    private static final boolean[] active = new boolean[SLOTS];
    private static final boolean[] claimed = new boolean[SLOTS];
    private static final float[] lowestY = new float[SLOTS];
    private static final float[] xAtLowest = new float[SLOTS];

    private SwipeUpUndo() {
    }

    /** Called from the scrub engine's {@code g(MotionEvent)} with the handler and the event. */
    public static int onEvent(Object handler, MotionEvent event) {
        try {
            if (handler == null || event == null
                    || !handler.getClass().getName().endsWith(ACTING_HANDLER)) {
                return PASS;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (anyClaimed()) {
                        return SWALLOW;
                    }
                    int index = event.getActionIndex();
                    begin(event.getPointerId(index), event.getX(index), event.getY(index));
                    return PASS;
                }
                case MotionEvent.ACTION_MOVE:
                    return onMove(event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    boolean ours = anyClaimed();
                    int id = event.getPointerId(event.getActionIndex());
                    if (id >= 0 && id < SLOTS) {
                        active[id] = false;
                        claimed[id] = false;
                    }
                    return ours ? SWALLOW : PASS;
                }
                case MotionEvent.ACTION_CANCEL: {
                    boolean ours = anyClaimed();
                    for (int i = 0; i < SLOTS; i++) {
                        active[i] = false;
                        claimed[i] = false;
                    }
                    return ours ? SWALLOW : PASS;
                }
                default:
                    return anyClaimed() ? SWALLOW : PASS;
            }
        } catch (Throwable oops) {
            // Runs on every motion event of the keyboard. Failing here must mean "not ours",
            // never a broken keyboard.
            return PASS;
        }
    }

    private static int onMove(MotionEvent event) {
        if (anyClaimed()) {
            return SWALLOW;
        }
        float flickPx = FLICK_DP * Resources.getSystem().getDisplayMetrics().density;
        int history = event.getHistorySize();
        for (int p = 0; p < event.getPointerCount(); p++) {
            int id = event.getPointerId(p);
            if (id < 0 || id >= SLOTS || !active[id]) {
                continue;
            }
            // History first, oldest to newest, so the decision is made at the first position that
            // qualifies rather than at whatever position the batch happened to end on.
            for (int h = 0; h < history; h++) {
                if (qualifies(id, event.getHistoricalX(p, h), event.getHistoricalY(p, h), flickPx)) {
                    claimed[id] = true;
                    return CLAIM;
                }
            }
            if (qualifies(id, event.getX(p), event.getY(p), flickPx)) {
                claimed[id] = true;
                return CLAIM;
            }
        }
        return PASS;
    }

    private static void begin(int id, float x, float y) {
        if (id < 0 || id >= SLOTS) {
            return;
        }
        active[id] = true;
        claimed[id] = false;
        lowestY[id] = y;
        xAtLowest[id] = x;
    }

    /**
     * One position; true once the swipe has risen far enough and straight enough.
     *
     * <p>Screen y grows downward. The low point follows the finger down, so a swipe that dips
     * before rising is measured from the bottom of the dip. The corridor is checked at the same
     * moment as the distance: at least twice as far up as sideways, which is what keeps a slightly
     * rising swipe left or right from being taken as a swipe up.
     */
    private static boolean qualifies(int id, float x, float y, float flickPx) {
        if (y > lowestY[id]) {
            lowestY[id] = y;
            xAtLowest[id] = x;
            return false;
        }
        float rise = lowestY[id] - y;
        return rise >= flickPx && CORRIDOR_RATIO * Math.abs(x - xAtLowest[id]) <= rise;
    }

    private static boolean anyClaimed() {
        for (int i = 0; i < SLOTS; i++) {
            if (claimed[i]) {
                return true;
            }
        }
        return false;
    }
}
