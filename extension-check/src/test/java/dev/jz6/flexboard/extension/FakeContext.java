package dev.jz6.flexboard.extension;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory {@link Context} and {@link SharedPreferences}, so the extension's own logic can be
 * run on a desktop JVM.
 *
 * <p>The blob parser, label clamp and crash recorder run on the desktop against this context.
 * The earlier import bug (in Hotkeys.java) was pure logic behind a preference store.
 *
 * <p>Deliberately not a mocking framework. The store is a {@code HashMap} and the editor writes
 * through on {@code apply()}, which is enough for every call the extension makes and adds no
 * dependency to a module whose whole purpose is compiling without one.
 */
public final class FakeContext extends Context {

    /** How many synchronous commits were made, as opposed to asynchronous applies. */
    public int commits;
    private boolean commitSucceeds = true;

    public FakeContext withoutCommit() {
        commitSucceeds = false;
        return this;
    }

    private final Map<String, Map<String, Object>> stores = new HashMap<>();
    private final Map<Integer, String> resources = new HashMap<>();

    private SharedPreferences preferences(final Map<String, Object> store) {
        return new SharedPreferences() {
            @Override
            public boolean contains(String key) {
                return store.containsKey(key);
            }

            @Override
            public Editor edit() {
                return new Editor() {
                    // Null means removal. Later calls replace earlier ones, as on Android.
                    private final Map<String, Object> pending = new HashMap<>();

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
                        pending.put(key, null);
                        return this;
                    }

                    @Override
                    public boolean commit() {
                        commits++;
                        if (!commitSucceeds) {
                            return false;
                        }
                        apply();
                        return true;
                    }

                    @Override
                    public void apply() {
                        // Writes land only here, just as on Android's Editor.
                        for (Map.Entry<String, Object> change : pending.entrySet()) {
                            if (change.getValue() == null) {
                                store.remove(change.getKey());
                            } else {
                                store.put(change.getKey(), change.getValue());
                            }
                        }
                        pending.clear();
                    }
                };
            }

            @Override
            public int getInt(String key, int defValue) {
                Object value = store.get(key);
                return value == null ? defValue : (Integer) value;
            }

            @Override
            public String getString(String key, String defValue) {
                Object value = store.get(key);
                return value == null ? defValue : (String) value;
            }

            @Override
            public boolean getBoolean(String key, boolean defValue) {
                Object value = store.get(key);
                return value == null ? defValue : (Boolean) value;
            }
        };
    }

    /** Register a string resource, for the paths that resolve a preference key by id. */
    public FakeContext withResource(int id, String value) {
        resources.put(id, value);
        return this;
    }

    public Map<String, Object> store() {
        return store(getPackageName() + "_preferences");
    }

    public Map<String, Object> store(String name) {
        Map<String, Object> values = stores.get(name);
        if (values == null) {
            values = new HashMap<>();
            stores.put(name, values);
        }
        return values;
    }

    @Override
    public String getPackageName() {
        return "com.google.android.inputmethod.latin";
    }

    private final android.content.res.Resources drawableResources = new android.content.res.Resources() {
        @Override
        public String getString(int id) {
            String value = resources.get(id);
            if (value == null) {
                throw new NotFoundException();
            }
            return value;
        }
    };

    @Override
    public android.content.res.Resources getResources() {
        return drawableResources;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    /** A clipboard that remembers what was put on it, and can be taken away. */
    public static final class FakeClipboard extends android.content.ClipboardManager {
        public String text;
        private android.content.ClipData clip;

        @Override
        public void setPrimaryClip(android.content.ClipData clip) {
            this.clip = clip;
            text = clip.getItemAt(0).getText().toString();
        }

        @Override
        public boolean hasPrimaryClip() {
            return clip != null;
        }

        @Override
        public android.content.ClipData getPrimaryClip() {
            return clip;
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
        return preferences(store(name));
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
