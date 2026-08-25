package androidx.preference;

import android.graphics.drawable.Drawable;

/**
 * Compile-time shape only. The extension sees the preference at click-dispatch time through this
 * surface; at runtime inside Gboard's process the real androidx class answers.
 *
 * <p>The members are the port's <b>obfuscated</b> letters, not androidx's source names: R8 renames
 * every member it keeps, and one-instruction getters like {@code getKey()} are not kept at all —
 * they were inlined out of the dex, so any extension code compiled against {@code getKey()} links
 * fine here and dies on device with NoSuchMethodError. The letters below are pinned by body shape
 * AND access flags in {@code tools/apk/preflight.py} (the settings section): {@code n} is
 * setSummary (it carries the "Preference already has a SummaryProvider set." throw), {@code N} is
 * setIcon (writes the icon field, zeroes the resource id, notifies). Do not add members by
 * androidx name, and do not re-add findPreference: its port ({@code t(String)}) is present in the
 * dex but {@code protected}, so a call from the settings fragment would compile here and throw
 * IllegalAccessError at tap time — the fragment dispatches through its own chain's public
 * findPreference (the {@code d(CharSequence)Preference} stubbed on CommonPreferenceFragment).
 */
public class Preference {

    /** setSummary(CharSequence) — live-updates the row's summary line. */
    public void n(CharSequence summary) {
        // stub
    }

    /** setIcon(Drawable) — live-updates the row's leading icon. */
    public void N(Drawable icon) {
        // stub
    }
}
