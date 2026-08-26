package android.view;

import android.content.Context;

/**
 * Compile-time shape only.
 */
public class ViewGroup extends View {

    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View child) {
        // stub
    }

    public void addView(View child, LayoutParams params) {
        // stub
    }

    public static class LayoutParams {

        /** API-stable since API 1. */
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public LayoutParams(int width, int height) {
            // stub
        }
    }
}
