package androidx.preference;

import android.graphics.drawable.Drawable;

/**
 * Compile-time shape only. The extension sees the preference at click-dispatch time through this
 * surface; at runtime inside Gboard's process the real androidx class answers.
 *
 * <p>The members are the port's <b>obfuscated</b> letters, not androidx's source names: R8 renames
 * every member it keeps, and one-instruction getters like {@code getKey()} are not kept at all —
 * they were inlined out of the dex, so any extension code compiled against {@code getKey()} or
 * {@code setSummary(...)} links fine here and dies on device with NoSuchMethodError. The letters
 * below are each pinned by body shape in {@code tools/apk/preflight.py} (the settings section):
 * {@code t} is findPreference, {@code n} is setSummary (it carries the "Preference already has a
 * SummaryProvider set." throw), {@code N} is setIcon (writes the icon field, zeroes the resource
 * id, notifies). Do not add members by androidx name.
 */
public class Preference {

    /** findPreference(String key) — the row under {@code key} in this screen's tree, or null. */
    public Preference t(String key) {
        return null;
    }

    /** setSummary(CharSequence) — live-updates the row's summary line. */
    public void n(CharSequence summary) {
        // stub
    }

    /** setIcon(Drawable) — live-updates the row's leading icon. */
    public void N(Drawable icon) {
        // stub
    }
}
