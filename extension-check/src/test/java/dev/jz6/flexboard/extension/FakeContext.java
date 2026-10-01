package dev.jz6.flexboard.extension;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory {@link Context} and {@link SharedPreferences}, so the extension's own logic can be
 * run on a desktop JVM.
 *
 * <p>The extension ships a blob parser, a label clamp and an icon-token resolver, none of which had
 * a single test. They are not Android code in any meaningful sense — they are string handling with
 * a preference store behind them — and the one bug we know shipped in this file (an import that
 * cleared a slot's text and left its icon override) was pure logic that any of these would have
 * caught.
 *
 * <p>Deliberately not a mocking framework. The store is a {@code HashMap} and the editor writes
 * through on {@code apply()}, which is enough for every call the extension makes and adds no
 * dependency to a module whose whole purpose is compiling without one.
 */
public final class FakeContext extends Context {

    /** How many synchronous commits were made, as opposed to asynchronous applies. */
    public int commits;

    private final Map<String, Object> store = new HashMap<>();
    private final Map<Integer, String> resources = new HashMap<>();

    private final SharedPreferences preferences = new SharedPreferences() {
        @Override
        public boolean contains(String key) {
            return store.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private final Map<String, Boolean> removals = new HashMap<>();

                @Override
                public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }

                @Override
                public Editor remove(String key) {
                    removals.put(key, true);
                    return this;
                }

                @Override
                public boolean commit() {
                    // Synchronous on a device, where apply() is not; here both write straight
                    // through, which is why a test about surviving a dying process has to assert
                    // that commit() is the call being made, not merely that something was written.
                    commits++;
                    apply();
                    return true;
                }

                @Override
                public void apply() {
                    // Writes land only here, so a test that forgets apply() sees no change —
                    // which is the same way the real thing behaves.
                    for (String key : removals.keySet()) {
                        store.remove(key);
                    }
                    store.putAll(pending);
                }
            };
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = store.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = store.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = store.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
    };

    /** Register a string resource, for the paths that resolve a preference key by id. */
    public FakeContext withResource(int id, String value) {
        resources.put(id, value);
        return this;
    }

    public Map<String, Object> store() {
        return store;
    }

    @Override
    public String getPackageName() {
        return "com.google.android.inputmethod.latin";
    }

    @Override
    public android.content.res.Resources getResources() {
        // Nothing under test resolves a drawable; the icon paths that would are Android-only.
        return null;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /** A clipboard that remembers what was put on it, and can be taken away. */
    public static final class FakeClipboard extends android.content.ClipboardManager {
        public String text;

        @Override
        public void setPrimaryClip(android.content.ClipData clip) {
            text = clip.getItemAt(0).getText().toString();
        }
    }

    private FakeClipboard clipboard = new FakeClipboard();

    public FakeClipboard clipboard() {
        return clipboard;
    }

    /** Model a device where the clipboard cannot be reached, so delivery has to keep the report. */
    public FakeContext withoutClipboard() {
        clipboard = null;
        return this;
    }

    @Override
    public Object getSystemService(String name) {
        return CLIPBOARD_SERVICE.equals(name) ? clipboard : null;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences;
    }

    @Override
    public String getString(int resId) {
        String value = resources.get(resId);
        if (value == null) {
            // The real Context throws for an unknown id, and the extension has a catch for exactly
            // that. Returning null instead would test a path the device never takes.
            throw new RuntimeException("no such resource: " + resId);
        }
        return value;
    }

    @Override
    public boolean isDeviceProtectedStorage() {
        return true;
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        return this;
    }
}
