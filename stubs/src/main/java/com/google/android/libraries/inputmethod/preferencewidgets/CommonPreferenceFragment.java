package com.google.android.libraries.inputmethod.preferencewidgets;

/**
 * Compile-time stub for Gboard's real {@code CommonPreferenceFragment}.
 *
 * <p>Gboard hosts its settings screens in fragments that subclass this class and report their
 * screen by overriding {@code aB()}. Flexboard's settings fragment has to do the same — Gboard's
 * settings host instantiates rows' fragments by reflective name lookup — but the real
 * implementation lives in the Gboard DEX the extension gets merged into, so it cannot be compiled
 * against directly. This stub stands in at compile time and is <b>never shipped</b>: the extension
 * module consumes it {@code compileOnly}, so only the reference survives into the extension DEX.
 *
 * <p>Only what {@code FlexboardSettingsFragment} touches is declared. Verified against Gboard
 * 18.0.3 (see {@code tools/apk/preflight.py}, the native-settings section): the class is public
 * and concrete, the no-arg constructor is public, and {@code aB()I} is public and concrete, so a
 * plain subclass with nothing but an {@code aB()} override links and verifies on device. Nothing
 * else may be added here unless the real class is re-checked — a stub member the real class
 * renames silently becomes a {@code NoSuchMethodError} at runtime.
 */
public class CommonPreferenceFragment {

    public CommonPreferenceFragment() {
    }

    /**
     * Stub of Gboard's concrete hook: the {@code res/xml} resource id of the screen to inflate.
     * The real default returns 0 (no screen); the override in the extension resolves the id by
     * resource name at runtime, because aapt2 has not assigned ids when this is compiled.
     */
    public int aB() {
        return 0;
    }

    /**
     * Stub of the ported androidx click dispatch — the real {@code Lcdr.aA(Landroidx/preference/
     * Preference;)Z} is what {@code Preference.performClick} funnels through. Defaults false so an
     * unhandled row falls through to the host's own navigation logic.
     */
    public boolean aA(androidx.preference.Preference preference) {
        return false;
    }

    /**
     * Stub of the ported {@code PreferenceFragmentCompat.findPreference} — publicly inherited
     * from the real superclass chain ({@code Lcdr;->d(Ljava/lang/CharSequence;)}, verified public
     * on Gboard 18.0.3). This, and not {@code Preference}'s own findPreference, is the key lookup
     * the extension may call: the row-class one survives R8 as {@code protected}, so a call from
     * the fragment would compile here and throw IllegalAccessError at tap time.
     */
    public androidx.preference.Preference d(CharSequence key) {
        return null;
    }
}
