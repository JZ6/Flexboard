package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;
import android.view.MotionEvent;

/**
 * Swipe up to undo autocorrect, built up one step at a time from the diagnostic that measured it.
 *
 * <p><b>Stage 3: revert the last autocorrection</b> — confirmed on a device in 2.5.2-dev.0.
 * <ol>
 *   <li>detect, and type "6" — confirmed on a device;</li>
 *   <li>take the gesture over — "6" if it took and the key is not typed, "x" if refused;</li>
 *   <li><b>revert</b> — once the takeover is confirmed, the emission asks Gboard's decoder for the
 *   same autocorrect revert it uses for a physical keyboard's delete-word. The decoder is the
 *   armed check: with no autocorrection to revert, nothing happens. As with Gboard's backspace,
 *   typing anything after the correction clears it.</li>
 * </ol>
 *
 * <p>This class decides and the emission acts. The takeover and the revert have to be done in
 * Gboard's own terms, which are obfuscated, so they stay in the emission where the patcher checks
 * them; nothing obfuscated is compiled in here. {@link #decide} answers pass, claim or swallow, and
 * the emission reports back through {@link #tookOver}.
 *
 * <p>Fed from the scrub engine's {@code g(MotionEvent)} — the motion-event-handler layer swipe left
 * and swipe right run on — which sees DOWN, every MOVE and UP for the keyboard whether or not the key
 * pipeline still considers the finger to be on a key. Measured there on a device, most real flicks
 * met the 24dp threshold and the 2:1 corridor <em>during</em> the swipe, which is why this acts on a
 * move rather than at release: on release the key handler types the letter before the scrub handler
 * sees the event, so the takeover has to act mid-swipe or not at all.
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

    /** The decisions the emission branches on. */
    public static final int PASS = 0;
    public static final int CLAIM = 1;
    public static final int SWALLOW = 2;

    private static final boolean[] active = new boolean[SLOTS];
    private static final boolean[] fired = new boolean[SLOTS];
    private static final boolean[] claimed = new boolean[SLOTS];
    private static int lastClaimed = -1;
    private static final float[] lowestY = new float[SLOTS];
    private static final float[] xAtLowest = new float[SLOTS];

    private SwipeUp() {
    }

    /**
     * Called first in the scrub engine's {@code g(MotionEvent)}: what to do with this event.
     *
     * <p>{@link #PASS}: not ours, the scrub engine handles it as always. {@link #CLAIM}: this move
     * completed a swipe up; take the gesture over. {@link #SWALLOW}: the gesture is ours; keep the
     * scrub engine's own logic out of it (its end-of-gesture reset still runs).
     */
    public static int decide(Object handler, MotionEvent event) {
        try {
            if (handler == null || event == null
                    || !handler.getClass().getName().endsWith(ACTING_HANDLER)) {
                return PASS;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // A new DOWN begins a new gesture even if an old UP/CANCEL went missing.
                    clear();
                    int index = event.getActionIndex();
                    begin(event.getPointerId(index), event.getX(index), event.getY(index));
                    return PASS;
                }
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
                        fired[id] = false;
                        claimed[id] = false;
                    }
                    return ours ? SWALLOW : PASS;
                }
                case MotionEvent.ACTION_CANCEL: {
                    boolean ours = anyClaimed();
                    clear();
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

    /**
     * Called by the emission right after the takeover, with whether it took.
     *
     * <p>The takeover call returns nothing and silently does nothing when the gesture already has an
     * owner, so the emission reads the owner back and reports. A refused takeover releases the
     * claim, so the rest of the gesture is the scrub engine's again; the swipe stays marked as
     * fired, so it is not attempted twice. A takeover that took keeps the claim, and the emission
     * sends the revert request; nothing is typed either way.
     */
    public static void tookOver(boolean took) {
        try {
            if (!took && lastClaimed >= 0 && lastClaimed < SLOTS) {
                claimed[lastClaimed] = false;
            }
        } catch (Throwable oops) {
            // A report must never be the thing that breaks the keyboard.
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
                fired[id] = true;
                claimed[id] = true;
                lastClaimed = id;
                return CLAIM;
            }
        }
        return PASS;
    }

    private static boolean anyClaimed() {
        for (int i = 0; i < SLOTS; i++) {
            if (claimed[i]) {
                return true;
            }
        }
        return false;
    }

    private static void clear() {
        for (int i = 0; i < SLOTS; i++) {
            active[i] = false;
            fired[i] = false;
            claimed[i] = false;
        }
        lastClaimed = -1;
    }

    private static void begin(int id, float x, float y) {
        if (id < 0 || id >= SLOTS) {
            return;
        }
        active[id] = true;
        fired[id] = false;
        claimed[id] = false;
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
