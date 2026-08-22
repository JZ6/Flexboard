package dev.jz6.flexboard.extension.settings;

import android.content.Context;

import com.google.android.libraries.inputmethod.preferencewidgets.CommonPreferenceFragment;

/**
 * Flexboard's settings screen, hosted by Gboard's own {@code SettingsActivity}.
 *
 * <p>Supersedes the hand-built {@code FlexboardSettingsActivity}. The header row this class is
 * reached from carries a {@code fragment=} attribute naming this class (see
 * {@code SettingsScreenPatch}); Gboard's settings host — a port of androidx
 * {@code PreferenceFragmentCompat}'s click path — sees the attribute, instantiates the class by
 * name through {@code Class.forName} and transacts it into place. There is no router or registry
 * to patch into: the class name on the row is the whole registration.
 *
 * <p>Everything after that is Gboard's — the app bar, the back stack, the RecyclerView, the row
 * layouts, its theme and Material You colours, and the preference store. A fragment-lifecycle
 * callback installs a {@code PreferenceDataStore} bridge onto every {@code PreferenceFragment}
 * port subclass, so the sliders write into {@code Lqhy;}'s device-protected store directly — the
 * same store the swipe patches read mid-gesture. No storage-mirror code exists any more.
 *
 * <p>The screen itself is {@code res/xml/flexboard_settings.xml}, written by
 * {@code SettingsScreenPatch} and inflated here by name rather than id — the id does not exist
 * until aapt2 recompiles the APK, long after this class stops being compiled. This resolution is
 * the only thing wrong with a fully declarative fragment: it needs a {@link Context}, and every
 * accessor for one is a superclass method whose real (obfuscated) name this class was compiled in
 * ignorance of.
 *
 * <p>Two constraints pin this class's shape:
 * <ul>
 *   <li>The host instantiates it with a public no-arg constructor, so this class is {@code final},
 *       public, and adds nothing but the {@code aB()} override.</li>
 *   <li>It extends a compile-time stub of the real Gboard class (see {@code stubs/}), so it may
 *       only call its own members — every inherited method name is unknown at compile time.</li>
 * </ul>
 */
public final class FlexboardSettingsFragment extends CommonPreferenceFragment {

    /** Must match the file {@code SettingsScreenPatch} writes to {@code res/xml/}. */
    private static final String SCREEN_NAME = "flexboard_settings";

    public FlexboardSettingsFragment() {}

    /**
     * The screen's resource id, resolved by name at runtime.
     *
     * <p>Returning 0 makes the fragment inflate nothing — a blank screen, not a crash — which is
     * the deliberate failure mode for "no Context could be produced", because the alternative is
     * the settings host going down with the tap.
     */
    @Override
    public int aB() {
        Context context = SettingsScreens.processContext();
        return SettingsScreens.xmlId(context, SCREEN_NAME);
    }
}
