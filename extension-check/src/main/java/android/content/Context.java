package android.content;

import android.content.res.Resources;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public abstract class Context {

    public static final int MODE_PRIVATE = 0;

    public abstract Context getApplicationContext();

    public abstract String getPackageName();

    public abstract SharedPreferences getSharedPreferences(String name, int mode);

    public abstract String getString(int resId);

    public abstract Resources getResources();

    public boolean isDeviceProtectedStorage() {
        return false;
    }

    public Context createDeviceProtectedStorageContext() {
        return null;
    }

    public static final String CLIPBOARD_SERVICE = "clipboard";

    public Object getSystemService(String name) {
        return null;
    }
}
