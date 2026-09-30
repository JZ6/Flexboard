package android.view;

/**
 * Compile-time shape only — the members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public class MotionEvent {

    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int ACTION_MOVE = 2;
    public static final int ACTION_CANCEL = 3;
    public static final int ACTION_POINTER_DOWN = 5;
    public static final int ACTION_POINTER_UP = 6;

    public final int getActionMasked() {
        return 0;
    }

    public final int getActionIndex() {
        return 0;
    }

    public final int getPointerCount() {
        return 0;
    }

    public final int getPointerId(int pointerIndex) {
        return 0;
    }

    public final float getX(int pointerIndex) {
        return 0f;
    }

    public final float getY(int pointerIndex) {
        return 0f;
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
