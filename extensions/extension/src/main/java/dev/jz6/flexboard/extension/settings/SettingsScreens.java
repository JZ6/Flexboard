package dev.jz6.flexboard.extension.settings;

import android.content.Context;
import android.inputmethodservice.InputMethodService;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * The two things every extension-hosted settings fragment needs from the outside: a Context
 * worth calling {@link android.content.res.Resources#getIdentifier} against, and the
 * resource-id lookup itself.
 *
 * <p>The fragments covered here subclass Gboard's obfuscated {@code CommonPreferenceFragment}
 * through a compile-time stub, so they can't name the inherited {@code getResources()} or
 * {@code getContext()} — every superclass method letter is unknown at compile time. Both come
 * through a Context acquired some other way.
 *
 * <p>First source is the IME service the base patch publishes: present whenever the keyboard
 * has been up this process. The fallback covers settings opened cold — Gboard's entry point
 * needs no service — by reflecting the framework's {@code ActivityThread.currentApplication()}.
 * Neither source names a Gboard symbol, so a package rename and R8 are both irrelevant.
 */
public final class SettingsScreens {

    private SettingsScreens() {}

    /** A Context in this process, best effort. */
    public static Context processContext() {
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
     * The id of a patch-added resource, by name rather than by number — aapt2 picks the number
     * after this class is compiled, so the name is the only handle available here.
     *
     * <p>Returns 0 when no Context could be produced, which the fragments treat as "show
     * nothing, do not crash".
     */
    public static int xmlId(Context context, String name) {
        if (context == null) {
            return 0;
        }
        return context.getResources().getIdentifier(name, "xml", context.getPackageName());
    }
}
