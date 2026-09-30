package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;

/**
 * Records how far a pointer travelled upward, for the key-pipeline swipe-up emission.
 *
 * Fed from {@code TouchActionBundle.handleActionMove} and asked at {@code Lpvi;->G}. Both are in the
 * key pipeline, and that is this class's limit rather than a bug in it: a flick that slides off the
 * top of the keyboard detaches the finger from every key, after which the move path stops writing
 * its position and {@code G} is never reached. The diagnostic that measures the whole gesture is
 * {@link FlickProbe}, which rides on the motion-event-handler layer instead; the real patch moves
 * there once that has answered its question.
 *
 * <p>The origin comes from the touch stream — running maximum y, and the largest rise above it —
 * because the pointer's own start fields are rewritten each time the finger crosses onto another key.
 * A 250 ms gap between samples starts a new gesture, because {@code finish()} runs only when
 * {@code G} does and so cannot be relied on to end one.
 */
public final class UpFlickTracker {

    /**
     * How far up the finger must have gone, in dp.
     *
     * <p>Density-independent and deliberately below Gboard's own slide threshold: measuring the
     * peak means this can be shorter without becoming twitchy, because a tap that wobbles never
     * accumulates travel in one direction. Android's touch slop is 8dp, so this is three times the
     * distance at which the system stops calling something a tap.
     */
    private static final float FLICK_DP = 24f;

    /** The corridor: the motion must be at least twice as vertical as it is horizontal. */
    private static final float CORRIDOR_RATIO = 2f;

    private static int pointerId = -1;

    /** The lowest the finger has been this gesture (largest y), and where it was horizontally. */
    private static float lowestY;
    private static float xAtLowest;

    /** The largest upward excursion from that low point, and the sideways drift at its peak. */
    private static float peakRise;
    private static float driftAtPeak;

    private static boolean seen;


    /**
     * When the last move event arrived, as a gesture boundary.
     *
     * <p>{@link #finish} runs at {@code Lpvi;->G}, which {@code handleActionUp} skips when the
     * release has no SoftKeyDef — exactly what happens when a finger lifts off the top of a key,
     * which is this gesture. So the state survived into the next touch: the following tap updated
     * the low point and returned before recomputing, leaving the *previous* gesture's rise and
     * drift to be classified. A large rise with a large drift is a 3, and on a device that showed
     * up as the first typed letter after a swipe reporting one.
     *
     * <p>Samples within a gesture are milliseconds apart and gestures are separated by at least a
     * tenth of a second, so the gap is an unambiguous boundary — and unlike a DOWN hook it depends
     * on nothing Gboard might redefine.
     */
    private static long lastEventNanos;

    /** Longer than any interval between samples, far shorter than any interval between gestures. */
    private static final long GESTURE_GAP_NANOS = 250L * 1000L * 1000L;

    private UpFlickTracker() {
    }

    /**
     * Called once per pointer per move event.
     *
     * <p><b>The origin comes from the y-stream, not from Gboard.</b> The first version measured
     * displacement from {@code Lpvi;->b/c}, on the reasonable-looking belief that those are the
     * gesture's start. They are re-initialised by {@code Lpvi;->B(SoftKeyView, …)}, which is "this
     * pointer is now on this key" — so a swipe that crosses onto another key silently restarts the
     * measurement and a long flick reports as a short one. On a device that showed up as a flood of
     * {@code 2}s: tracked, but never far enough.
     *
     * <p>So the low point is whatever the stream says it is. Running maximum y, and the largest
     * rise above it. Nothing Gboard owns can reset it mid-gesture, and it needs no notion of where
     * the gesture began.
     *
     * <p>The start parameters are still accepted and ignored. Keeping the signature means the
     * emission does not change, and the emission is the part that has been verified in a patched
     * dex.
     */
    public static void track(int id, float unusedStartX, float unusedStartY, float x, float y) {
        try {
            long nanos = System.nanoTime();
            boolean stale = nanos - lastEventNanos > GESTURE_GAP_NANOS;
            lastEventNanos = nanos;
            if (id != pointerId || !seen || stale) {
                pointerId = id;
                seen = true;
                lowestY = y;
                xAtLowest = x;
                peakRise = 0f;
                driftAtPeak = 0f;
                return;
            }
            if (y > lowestY) {
                // Still descending, or settling. This becomes the point to rise from.
                lowestY = y;
                xAtLowest = x;
                return;
            }
            float rise = lowestY - y;
            if (rise > peakRise) {
                peakRise = rise;
                driftAtPeak = x - xAtLowest;
            }
        } catch (Throwable oops) {
            // Runs on every motion event of every pointer; it must never break touch handling.
        }
    }

    /** Outcomes of {@link #classify}. */
    public static final int NOT_TRACKED = 1;
    public static final int TOO_SHORT = 2;
    public static final int OFF_CORRIDOR = 3;
    public static final int UP_FLICK = 6;

    /**
     * Why the gesture that just ended was, or was not, an upward flick.
     *
     * <p>Three-valued on the failure side rather than a boolean, because the three causes need
     * completely different fixes and look identical from a device. The gesture currently fires in
     * bursts — several in a row, then nothing, then several again — and that is consistent with the
     * tracker never having seen the pointer at all ({@link #NOT_TRACKED}), with the travel being
     * genuinely short ({@link #TOO_SHORT}), and with the corridor rejecting it
     * ({@link #OFF_CORRIDOR}). Guessing between them is what this exists to stop.
     */
    public static int classify(int id, float unusedStartX, float unusedStartY) {
        try {
            long nanos = System.nanoTime();
            boolean stale = nanos - lastEventNanos > GESTURE_GAP_NANOS;
            lastEventNanos = nanos;
            if (id != pointerId || !seen || stale) {
                return NOT_TRACKED;
            }
            if (peakRise < flickDistancePx()) {
                return TOO_SHORT;
            }
            if (CORRIDOR_RATIO * Math.abs(driftAtPeak) > peakRise) {
                return OFF_CORRIDOR;
            }
            return UP_FLICK;
        } catch (Throwable oops) {
            return NOT_TRACKED;
        }
    }

    /**
     * Ends the gesture.
     *
     * <p>Asked at release, which is the only reliable boundary available: Gboard's own start fields
     * cannot be used to detect a new gesture now that the origin does not come from them, and there
     * is no DOWN hook. Clearing here means a pointer that is never asked about leaks its state into
     * the next gesture — which the running-maximum recovers from on the first downward sample.
     */
    private static void finish() {
        seen = false;
        peakRise = 0f;
        driftAtPeak = 0f;
    }

    /**
     * Whether the gesture that just ended was an upward flick.
     *
     * <p>Answers false for anything it was not watching, so a pointer that never reached the move
     * path — a tap, or one skipped because its index was stale — cannot be mistaken for a flick.
     */
    public static boolean wasUpFlick(int id, float gestureStartX, float gestureStartY) {
        int outcome = classify(id, gestureStartX, gestureStartY);
        finish();
        return outcome == UP_FLICK;
    }

    /**
     * The flick distance in pixels.
     *
     * <p>{@code Resources.getSystem()} rather than a Context, because this is reached from a static
     * emission with no instance to borrow one from, and display density is a system property rather
     * than an application one.
     */
    private static float flickDistancePx() {
        return FLICK_DP * Resources.getSystem().getDisplayMetrics().density;
    }
}
