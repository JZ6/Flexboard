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
    private static float startX;
    private static float startY;

    /** Most negative dy seen this gesture, and the dx at that moment. Up is negative. */
    private static float peakDy;
    private static float dxAtPeak;

    private UpFlickTracker() {
    }

    /** Called once per pointer per move event, with the gesture's start and current positions. */
    public static void track(int id, float gestureStartX, float gestureStartY, float x, float y) {
        try {
            if (id != pointerId || gestureStartX != startX || gestureStartY != startY) {
                pointerId = id;
                startX = gestureStartX;
                startY = gestureStartY;
                peakDy = 0f;
                dxAtPeak = 0f;
            }
            float dy = y - gestureStartY;
            if (dy < peakDy) {
                peakDy = dy;
                dxAtPeak = x - gestureStartX;
            }
        } catch (Throwable oops) {
            // This runs on every motion event of every pointer. It must never be the reason the
            // keyboard stops handling touch.
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
    public static int classify(int id, float gestureStartX, float gestureStartY) {
        try {
            if (id != pointerId || gestureStartX != startX || gestureStartY != startY) {
                return NOT_TRACKED;
            }
            if (peakDy > -flickDistancePx()) {
                return TOO_SHORT;
            }
            if (CORRIDOR_RATIO * Math.abs(dxAtPeak) > Math.abs(peakDy)) {
                return OFF_CORRIDOR;
            }
            return UP_FLICK;
        } catch (Throwable oops) {
            return NOT_TRACKED;
        }
    }

    /**
     * Whether the gesture that just ended was an upward flick.
     *
     * <p>Answers false for anything it was not watching, so a pointer that never reached the move
     * path — a tap, or one skipped because its index was stale — cannot be mistaken for a flick.
     */
    public static boolean wasUpFlick(int id, float gestureStartX, float gestureStartY) {
        return classify(id, gestureStartX, gestureStartY) == UP_FLICK;
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
    public static void report(int id, float gestureStartX, float gestureStartY) {
        try {
            boolean tracked = id == pointerId
                    && gestureStartX == startX
                    && gestureStartY == startY;
            if (tracked && peakDy == 0f) {
                return;
            }
            int outcome = classify(id, gestureStartX, gestureStartY);
            InputConnection connection = ImeService.connection();
            if (connection == null) {
                return;
            }
            connection.commitText(Integer.toString(outcome), 1);
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
