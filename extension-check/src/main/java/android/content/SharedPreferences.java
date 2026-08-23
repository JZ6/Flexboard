package android.content;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public interface SharedPreferences {

    boolean contains(String key);

    Editor edit();

    int getInt(String key, int defValue);

    String getString(String key, String defValue);

    boolean getBoolean(String key, boolean defValue);

    interface Editor {
        Editor putInt(String key, int value);

        Editor putBoolean(String key, boolean value);

        Editor putString(String key, String value);

        void apply();
    }
}
