package android.content;

import android.net.Uri;

/**
 * Compile-time shape only, for {@code :extension-check}. Only the ACTION_VIEW constructor the
 * About section's Source row uses; the real class replaces this at runtime.
 */
public class Intent {

    public static final String ACTION_VIEW = "android.intent.action.VIEW";

    public Intent(String action, Uri uri) {
    }
}
