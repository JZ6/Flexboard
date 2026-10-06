package android.view;

/**
 * Compile-time shape only — the members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public final class MotionEvent {

    private final int action;
    private final float x;
    private final float y;

    private MotionEvent(int action, float x, float y) {
        this.action = action;
        this.x = x;
        this.y = y;
    }

    /** The framework's one-pointer factory signature, enough for desktop gesture tests. */
    public static MotionEvent obtain(long downTime, long eventTime, int action,
            float x, float y, int metaState) {
        return new MotionEvent(action, x, y);
    }

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    public final int getActionMasked() {
        return action;
    }

    public final int getActionIndex() {
        return 0;
    }

    public final int getPointerCount() {
        return 1;
    }

    public final int getPointerId(int pointerIndex) {
        return 0;
    }

    public final float getX(int pointerIndex) {
        return x;
    }

    public final float getY(int pointerIndex) {
        return y;
    }

    public final int getHistorySize() {
        return 0;
    }

    public final float getHistoricalX(int pointerIndex, int pos) {
        return 0f;
    }

    public final float getHistoricalY(int pointerIndex, int pos) {
        return 0f;
    }
}
