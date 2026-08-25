package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/**
 * Compile-time shape only — the composite dialog's vertical column.
 */
public class LinearLayout extends ViewGroup {

    /** Vertical stacking; the value is API-stable since API 1. */
    public static final int VERTICAL = 1;

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
        // stub
    }
}
