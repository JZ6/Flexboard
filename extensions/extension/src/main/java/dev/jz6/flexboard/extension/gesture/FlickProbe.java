package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;
import android.view.MotionEvent;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * Diagnostic: measures every upward flick from the motion-event-handler layer and types what it saw.
 *
 * <p>This replaces a probe that reported from {@code Lpvi;->G}, and the reason is a result a device
 * confirmed: <b>a flick from the top row produced nothing at all, while the same flick from the
 * bottom row produced a number.</b> {@code G} is only reached when the finger is still attached to a
 * key at release. Letter keys declare no upward action, so Gboard treats upward motion as moving to
 * another key — and above the top row there is no key, so the finger is detached from every key and
 * {@code G} never runs. The old probe was therefore blind to exactly the long, clean flicks that
 * should succeed, and what it did report skewed short. It could not answer the question it was built
 * for.
 *
 * <p>The motion-event-handler layer does not have that blind spot. Handlers receive the raw event
 * stream for the view the finger went down on — DOWN, every MOVE, UP — whether or not the key
 * pipeline still considers the finger attached to a key. Swipe left and swipe right live here, which
 * is why they never miss. This is fed from the scrub engine's {@code g(MotionEvent)}, once per event.
 *
 * <p>Three things the old probe got wrong are fixed by construction here:
 * <ul>
 *   <li>It measured from the first MOVE, losing the distance from touchdown. This starts at DOWN.</li>
 *   <li>It took one position per MOVE and dropped the positions Android batches into each event,
 *       under-counting fast flicks. This reads the history.</li>
 *   <li>It kept one static slot that any finger's release could clear, so a second thumb lifting
 *       mid-flick wiped the measurement. This tracks each pointer separately and reports only when
 *       that pointer lifts.</li>
 * </ul>
 *
 * <p>Output, after each release that rose at least a quarter of the flick distance:
 * {@code u<rise>/<drift>s<samples>=<outcome><when> } with rise and drift in dp. Outcome 6 means it
 * met the threshold and the corridor, 2 means too short, 3 means too diagonal. {@code when} is
 * {@code m} if the threshold was met during a move and {@code e} if only at release. It observes only: it does not
 * claim the pointer, does not undo anything, and does not stop the key being typed.
 */
public final class FlickProbe {

    /**
     * Only one scrub handler reports.
     *
     * <p>{@code g(MotionEvent)} belongs to the shared scrub engine, so every scrub subclass attached
     * to a view receives the same events. Without this the same flick could be reported once per
     * handler. Matched by name because the scrub classes are not obfuscated, and a name mismatch fails
     * safe — the probe goes quiet rather than reporting twice.
     */
    private static final String REPORTING_HANDLER = "ScrubDeleteMotionEventHandler";

    /** Android pointer ids are small integers; ids beyond this are ignored rather than wrapped. */
    private static final int SLOTS = 16;

    private static final float FLICK_DP = 24f;
    private static final float CORRIDOR_RATIO = 2f;

    private static final boolean[] active = new boolean[SLOTS];
    private static final float[] lowestY = new float[SLOTS];
    private static final float[] xAtLowest = new float[SLOTS];
    private static final float[] peakRise = new float[SLOTS];
    private static final float[] driftAtPeak = new float[SLOTS];
    private static final int[] samples = new int[SLOTS];

    private FlickProbe() {
    }

    /** Called from the scrub engine's {@code g(MotionEvent)} with the handler and the event. */
    public static void observe(Object handler, MotionEvent event) {
        try {
            if (handler == null || event == null
                    || !handler.getClass().getName().endsWith(REPORTING_HANDLER)) {
                return;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    begin(event.getPointerId(index), event.getX(index), event.getY(index));
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    int history = event.getHistorySize();
                    for (int p = 0; p < event.getPointerCount(); p++) {
                        int id = event.getPointerId(p);
                        if (!tracking(id)) {
                            continue;
                        }
                        for (int h = 0; h < history; h++) {
                            sample(id, event.getHistoricalX(p, h), event.getHistoricalY(p, h));
                        }
                        sample(id, event.getX(p), event.getY(p));
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int index = event.getActionIndex();
                    int id = event.getPointerId(index);
                    if (tracking(id)) {
                        // Decided before the release position is sampled. On UP the key handler
                        // types the letter before the scrub handler sees the event, so the real patch
                        // can only stop the letter by claiming during a MOVE. Whether the threshold
                        // was already met at that point is the question this probe exists to answer.
                        boolean crossedOnMove = peakRise[id] >= flickPx();
                        sample(id, event.getX(index), event.getY(index));
                        report(id, crossedOnMove);
                        active[id] = false;
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    for (int i = 0; i < SLOTS; i++) {
                        active[i] = false;
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable oops) {
            // Runs on every motion event of the keyboard; it must never break touch handling.
        }
    }

    private static boolean tracking(int id) {
        return id >= 0 && id < SLOTS && active[id];
    }

    private static void begin(int id, float x, float y) {
        if (id < 0 || id >= SLOTS) {
            return;
        }
        active[id] = true;
        lowestY[id] = y;
        xAtLowest[id] = x;
        peakRise[id] = 0f;
        driftAtPeak[id] = 0f;
        samples[id] = 1;
    }

    /**
     * One position. Screen y grows downward, so "rise" is how far above the lowest point the finger
     * has been. The low point moves down with the finger, so a flick that dips before rising is
     * measured from the bottom of the dip rather than from where it landed.
     */
    private static void sample(int id, float x, float y) {
        samples[id]++;
        if (y > lowestY[id]) {
            lowestY[id] = y;
            xAtLowest[id] = x;
            return;
        }
        float rise = lowestY[id] - y;
        if (rise > peakRise[id]) {
            peakRise[id] = rise;
            driftAtPeak[id] = x - xAtLowest[id];
        }
    }

    private static float flickPx() {
        return FLICK_DP * Resources.getSystem().getDisplayMetrics().density;
    }

    /**
     * Types the measurement. The trailing letter says when the threshold was met: {@code m} during a
     * move, where a mid-gesture claim would have stopped the letter, or {@code e} only at the end,
     * where it would already have been typed. Omitted for 2, which never met it.
     */
    private static void report(int id, boolean crossedOnMove) {
        float density = Resources.getSystem().getDisplayMetrics().density;
        float flickPx = FLICK_DP * density;
        float rise = peakRise[id];
        if (rise < flickPx / 4f) {
            return; // not an attempt at a gesture; typing stays quiet
        }
        float drift = Math.abs(driftAtPeak[id]);
        int outcome;
        if (rise < flickPx) {
            outcome = 2;
        } else if (CORRIDOR_RATIO * drift > rise) {
            outcome = 3;
        } else {
            outcome = 6;
        }
        InputConnection connection = ImeService.connection();
        if (connection == null) {
            return;
        }
        connection.commitText("u" + Math.round(rise / density)
                + "/" + Math.round(drift / density)
                + "s" + samples[id]
                + "=" + outcome
                + (outcome == 2 ? "" : crossedOnMove ? "m" : "e")
                + " ", 1);
    }
}
