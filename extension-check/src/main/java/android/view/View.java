package android.view;

import android.content.Context;

/**
 * Compile-time shape only — the widget surface the settings dialogs compose.
 */
public class View {

    public View(Context context) {
        // stub
    }

    /** Posted work runs after layout; focusing before that silently does nothing. */
    public boolean post(Runnable action) {
        return false;
    }

    public boolean requestFocus() {
        return false;
    }

    public void setPadding(int left, int top, int right, int bottom) {
        // stub
    }

    public void setAlpha(float alpha) {
        // stub
    }

    public void setLayoutParams(ViewGroup.LayoutParams params) {
        // stub
    }

    public void setOnClickListener(OnClickListener listener) {
        // stub
    }

    public interface OnClickListener {
        void onClick(View view);
    }
}
