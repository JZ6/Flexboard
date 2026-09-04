package android.view;

/** Compile-time shape only, for {@code :extension-check}. Just the soft-input mode flags. */
public final class WindowManager {

    private WindowManager() {}

    public static final class LayoutParams {

        public static final int SOFT_INPUT_STATE_ALWAYS_VISIBLE = 0x00000005;
        public static final int SOFT_INPUT_ADJUST_RESIZE = 0x00000010;

        private LayoutParams() {}
    }
}
