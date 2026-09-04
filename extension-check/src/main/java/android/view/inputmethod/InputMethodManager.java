package android.view.inputmethod;

import android.view.View;

/**
 * Compile-time shape only, for {@code :extension-check}. Only what the settings screen's scratch
 * pad needs to raise the keyboard on demand; the real class replaces this at runtime.
 */
public final class InputMethodManager {

    public static final int SHOW_IMPLICIT = 1;

    private InputMethodManager() {}

    public boolean showSoftInput(View view, int flags) {
        return false;
    }
}
