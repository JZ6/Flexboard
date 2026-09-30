package dev.jz6.flexboard.extension.gesture;

import android.content.res.Resources;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * Remembers how far a pointer travelled upward, so an up-flick can be recognised by its journey
 * rather than by where the finger happened to be when it lifted.
 *
 * <p>Gboard's own classifier, {@code Lpvi;->h}, runs once on ACTION_UP over start-to-end
 * displacement. That is why "swipe up to undo autocorrect" fires intermittently while swipe left
 * and swipe right never miss: those are handled by {@code ScrubMotionEventHandler}, which sees
 * every motion event and claims the pointer mid-gesture. A finger lifting from an upward flick is
 * decelerating and commonly drifts back down, so the displacement at release can be well short of
 * the displacement at the peak. One comparison, taken at the worst possible instant.
 *
 * <p>This is fed from {@code TouchActionBundle.handleActionMove}, once per pointer per event, and
 * keeps the furthest upward point of the current gesture. The question asked at release changes
 * from <em>is the release point high enough</em> to <em>did this gesture ever go far enough</em>.
 *
 * <p><b>No reset hook is needed.</b> The move path carries the gesture's start coordinates
 * alongside the current ones, so a change of start is itself the signal that a new gesture began.
 * Two consecutive gestures starting at bit-identical float coordinates is not a real case.
 *
 * <p>Single-slot rather than a map. Every gesture this recognises is a single finger on a key, the
 * input pipeline is single-threaded, and a map keyed by pointer id would need eviction that nothing
 * would ever exercise. A second finger simply takes the slot, which loses the first finger's peak —
 * correct, since a multi-touch gesture is not an up-flick.
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

    /** How many move events this gesture produced. Sparse sampling is one of the suspects. */
    private static int sampleCount;

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
                sampleCount = 1;
                return;
            }
            sampleCount++;
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

    /** Outcomes of {@link #classify}, and the markers {@link #report} types for each. */
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
        sampleCount = 0;
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
     * Diagnostic build only: types the outcome of every pointer release as a digit.
     *
     * <p>Called unconditionally rather than on success, because a gesture that produces nothing is
     * the case under investigation and a silent failure tells you nothing. A run of 1s means the
     * tracker is not seeing the pointer; a run of 2s means 24dp is too far; 3s mean the corridor is
     * too narrow. The proportions matter as much as the values.
     *
     * <p>Only fires for gestures that moved at all, so ordinary typing does not fill the field with
     * 1s — a tap has no travel to classify and nothing to report.
     */
    public static void report(int id, float unusedStartX, float unusedStartY) {
        try {
            boolean tracked = id == pointerId && seen;
            float rise = tracked ? peakRise : 0f;
            float drift = tracked ? Math.abs(driftAtPeak) : 0f;
            int outcome = classify(id, unusedStartX, unusedStartY);
            int samples = sampleCount;
            finish();

            // Below a quarter of the flick distance this was not an attempt at a gesture, and
            // reporting it fills the field with noise about ordinary typing.
            if (!tracked || rise < flickDistancePx() / 4f) {
                return;
            }

            // The measurement, not a verdict on it. Three rounds of this feature have been lost to
            // me choosing between explanations that all produce the same category — "too short" is
            // consistent with the threshold being wrong, with the sampling being sparse, and with
            // the measurement being broken, and those need different fixes. A number separates
            // them in one swipe: do a deliberate long flick and read what it thought it saw.
            //
            //   u<rise>/<drift>s<samples>=<outcome>
            //
            // rise and drift in dp so they can be compared against the 24dp threshold directly,
            // samples being how many move events the gesture produced, which is the one thing that
            // cannot be inferred afterwards.
            float density = Resources.getSystem().getDisplayMetrics().density;
            String text = "u" + Math.round(rise / density)
                    + "/" + Math.round(drift / density)
                    + "s" + samples
                    + "=" + outcome + " ";
            InputConnection connection = ImeService.connection();
            if (connection == null) {
                return;
            }
            connection.commitText(text, 1);
        } catch (Throwable oops) {
            // A diagnostic must never be the thing that breaks the keyboard it is measuring.
        }
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
