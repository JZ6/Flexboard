package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;
import android.view.MotionEvent;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * Swipe up to undo autocorrect, built up one step at a time from the diagnostic that measured it.
 *
 * <p><b>Stage 1 of 4: detect, and type a single "6".</b> Nothing in Gboard is called. The previous
 * version went straight to taking the gesture over and sending an undo, and it crashed the keyboard
 * on a swipe up; the build it was based on — this measuring code, as a diagnostic — never did. So it
 * is rebuilt from that base, adding one capability per release, so whatever breaks names itself:
 * <ol>
 *   <li>detect, and type "6" — the measuring code acting at the moment a swipe qualifies;</li>
 *   <li>take the gesture over — "6" alone, with no letter typed;</li>
 *   <li>send an undo in place of the "6";</li>
 *   <li>undo only when an autocorrection is armed, which is what Gboard's own backspace checks.</li>
 * </ol>
 *
 * <p>Fed from the scrub engine's {@code g(MotionEvent)} — the motion-event-handler layer swipe left
 * and swipe right run on — which sees DOWN, every MOVE and UP for the keyboard whether or not the key
 * pipeline still considers the finger to be on a key. Measured there on a device, most real flicks
 * met the 24dp threshold and the 2:1 corridor <em>during</em> the swipe, which is why this acts on a
 * move rather than at release: on release the key handler types the letter before the scrub handler
 * sees the event, so stage 2 has to act mid-swipe or not at all.
 *
 * <p>Measurement, as the diagnostic did it: from touchdown, reading the positions Android batches
 * into each move event, each pointer tracked separately.
 */
public final class SwipeUp {

    /** Every scrub subclass shares {@code g}; only one may act, or one swipe would act twice. */
    private static final String ACTING_HANDLER = "ScrubDeleteMotionEventHandler";

    /** Android pointer ids are small integers; ids beyond this are ignored rather than wrapped. */
    private static final int SLOTS = 16;

    /** The values measured against on a device and found to work: mostly "6m" in testing. */
    private static final float FLICK_DP = 24f;
    private static final float CORRIDOR_RATIO = 2f;

    /** Stage 1's action. Replaced by the takeover in stage 2. */
    private static final String MARKER = "6";

    private static final boolean[] active = new boolean[SLOTS];
    private static final boolean[] fired = new boolean[SLOTS];
    private static final float[] lowestY = new float[SLOTS];
    private static final float[] xAtLowest = new float[SLOTS];

    private SwipeUp() {
    }

    /** Called from the scrub engine's {@code g(MotionEvent)} with the handler and the event. */
    public static void observe(Object handler, MotionEvent event) {
        try {
            if (handler == null || event == null
                    || !handler.getClass().getName().endsWith(ACTING_HANDLER)) {
                return;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int index = event.getActionIndex();
                    begin(event.getPointerId(index), event.getX(index), event.getY(index));
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    onMove(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP: {
                    int id = event.getPointerId(event.getActionIndex());
                    if (id >= 0 && id < SLOTS) {
                        active[id] = false;
                        fired[id] = false;
                    }
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                    for (int i = 0; i < SLOTS; i++) {
                        active[i] = false;
                        fired[i] = false;
                    }
                    break;
                default:
                    break;
            }
        } catch (Throwable oops) {
            // Runs on every motion event of the keyboard; it must never break touch handling.
        }
    }

    private static void onMove(MotionEvent event) {
        float flickPx = FLICK_DP * Resources.getSystem().getDisplayMetrics().density;
        int history = event.getHistorySize();
        for (int p = 0; p < event.getPointerCount(); p++) {
            int id = event.getPointerId(p);
            if (id < 0 || id >= SLOTS || !active[id] || fired[id]) {
                continue;
            }
            // History first, oldest to newest, so the swipe qualifies at the first position that
            // does rather than at wherever the batch happened to end.
            boolean qualified = false;
            for (int h = 0; h < history && !qualified; h++) {
                qualified = qualifies(id, event.getHistoricalX(p, h), event.getHistoricalY(p, h), flickPx);
            }
            if (!qualified) {
                qualified = qualifies(id, event.getX(p), event.getY(p), flickPx);
            }
            if (qualified) {
                act(id);
            }
        }
    }

    /** Stage 1: once per swipe, type the marker. */
    private static void act(int id) {
        fired[id] = true;
        InputConnection connection = ImeService.connection();
        if (connection != null) {
            connection.commitText(MARKER, 1);
        }
    }

    private static void begin(int id, float x, float y) {
        if (id < 0 || id >= SLOTS) {
            return;
        }
        active[id] = true;
        fired[id] = false;
        lowestY[id] = y;
        xAtLowest[id] = x;
    }

    /**
     * One position; true once the swipe has risen far enough and straight enough.
     *
     * <p>Screen y grows downward. The low point follows the finger down, so a swipe that dips
     * before rising is measured from the bottom of the dip. At least twice as far up as sideways —
     * the corridor that kept diagonal swipes out in testing, and that keeps a slightly rising swipe
     * left or right from counting as a swipe up.
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
}
