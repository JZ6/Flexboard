package android.inputmethodservice;

import android.content.Context;
import android.view.inputmethod.InputConnection;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public class InputMethodService extends Context {

    public InputConnection getCurrentInputConnection() {
        return null;
    }

    @Override
    public Context getApplicationContext() {
        return null;
    }

    @Override
    public String getPackageName() {
        return null;
    }

    @Override
    public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }

    @Override
    public String getString(int resId) {
        return null;
    }

    @Override
    public android.content.res.Resources getResources() {
        return null;
    }
}
