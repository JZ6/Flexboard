package androidx.preference;

/**
 * Compile-time shape only. The extension sees the preference at click-dispatch time through this
 * surface; at runtime inside Gboard's process the real androidx class answers. Only the members
 * the extension actually calls.
 */
public class Preference {

    public String getKey() {
        return null;
    }

    public void setSummary(CharSequence summary) {
        // stub
    }
}
