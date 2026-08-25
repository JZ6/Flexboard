package dev.jz6.flexboard.extension.settings;

import android.content.Context;
import android.inputmethodservice.InputMethodService;

import com.google.android.libraries.inputmethod.preferencewidgets.CommonPreferenceFragment;

import dev.jz6.flexboard.extension.ime.ImeService;
import dev.jz6.flexboard.extension.toolbar.Hotkeys;

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
        Context context = processContext();
        if (context == null) {
            return 0;
        }
        return context.getResources()
            .getIdentifier(SCREEN_NAME, "xml", context.getPackageName());
    }

    /**
     * A Context in this process, best effort.
     *
     * <p>First choice is the IME service the base patch publishes: it is present whenever the
     * keyboard has ever been up in this process, which is the ordinary path into Gboard's
     * settings. The fallback covers settings opened cold — Gboard's entry point needs no service
     * — by reflecting the framework's {@code ActivityThread.currentApplication()}. Both are this
     * app's own objects; no Gboard symbol is named, so package rename and R8 are both irrelevant.
     */
    private static Context processContext() {
        InputMethodService service = ImeService.get();
        if (service != null) {
            return service;
        }
        try {
            return (Context) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
        }
    }

    /**
     * Click dispatch on this screen's rows. The ported androidx {@code Preference} click chain
     * lands here — {@code Preference.performClick} on the host calls the fragment class's
     * {@code aA} by name, which is why the method's obfuscated name is load-bearing and the
     * {@code super.aA(...)} return is the navigation fallback (rows that navigate, like
     * everything else on the screen, keep working).
     *
     * <p>Our two buttons write to / read from the clipboard and report through the row's own
     * summary, so a paste typo is told by the row it happened on rather than by a toast the
     * theme might not style.
     */
    @Override
    public boolean aA(androidx.preference.Preference preference) {
        String key = preference.getKey();
        if (!"flexboard_hotkey_copy".equals(key) && !"flexboard_hotkey_paste".equals(key)) {
            return super.aA(preference);
        }
        Context context = processContext();
        if (context == null) {
            preference.setSummary("no app context — try again from the keyboard");
            return true;
        }
        String outcome = "flexboard_hotkey_copy".equals(key)
            ? Hotkeys.exportToClipboard(context)
            : Hotkeys.importFromClipboard(context);
        preference.setSummary(outcome);
        return true;
    }
}
