package dev.jz6.flexboard.extension.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
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
 *   <li>The host instantiates it with a public no-arg constructor, so this class is {@code final}
 *       and public.</li>
 *   <li>It extends a compile-time stub of the real Gboard class (see {@code stubs/}), so every
 *       inherited surface it touches is the port's obfuscated letters: {@code aB()} for the screen
 *       id, {@code aA(Preference)} for clicks, {@code d(CharSequence)} (this chain's public
 *       findPreference) for row identity, and on the rows themselves {@code n(CharSequence)}
 *       (setSummary) and {@code N(Drawable)} (setIcon).
 *       There is no getKey to dispatch on — R8 inlined the one-instruction getter out of the dex
 *       entirely — so a row is identified by asking the tree for its key and comparing identity
 *       with the tapped instance.
 * </ul>
 */
public final class FlexboardSettingsFragment extends CommonPreferenceFragment {

    /** Must match the file {@code SettingsScreenPatch} writes to {@code res/xml/}. */
    private static final String SCREEN_NAME = "flexboard_settings";

    public FlexboardSettingsFragment() {}

    /** Set once the row icons have been re-drawn from the store on this instance. */
    private boolean iconsSynced;

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
     * Click dispatch on this screen's rows. The ported androidx click chain
     * ({@code Preference.I()V} → the manager's hosted fragment) lands on the fragment class's
     * {@code aA} by name, which is why the obfuscated letters here and on the row stub are
     * load-bearing, and why {@code super.aA(...)} is the fallback that keeps the stock rows —
     * the per-slot {@code EditTextPreference} dialogs above all — working.
     *
     * <p>Three row families are intercepted by identity: each {@code flexboard_hotkey_N_icon}
     * row cycles its slot through the bundled icon pack, and the Export/Import buttons write
     * and read the clipboard. Every outcome reports through the tapped row's own summary, so a
     * paste typo is told by the row it happened on rather than by a toast the theme might not
     * style.
     */
    @Override
    public boolean aA(androidx.preference.Preference preference) {
        syncRowIconsOnce();

        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            if (isRow(preference, Hotkeys.iconKey(slot))) {
                cycleIcon(preference, slot);
                return true;
            }
        }
        boolean copy = isRow(preference, "flexboard_hotkey_copy");
        boolean paste = !copy && isRow(preference, "flexboard_hotkey_paste");
        if (!copy && !paste) {
            return super.aA(preference);
        }
        Context context = processContext();
        if (context == null) {
            preference.n("no app context — try again from the keyboard");
            return true;
        }
        String outcome = copy
            ? Hotkeys.exportToClipboard(context)
            : Hotkeys.importFromClipboard(context);
        preference.n(outcome);
        // The once-per-instance sync ran before this tap, i.e. before the import wrote the store
        // — re-run it so the rows show what the import landed, not what it replaced. "imported"
        // is the one success prefix among the outcome strings; failures leave the screen as is.
        if (!copy && outcome.startsWith("imported")) {
            iconsSynced = false;
            syncRowIconsOnce();
        }
        return true;
    }

    /**
     * Whether {@code tapped} is the row carrying {@code key}. There is no getKey on the ported
     * {@code Preference} — R8 inlined it away — so this looks the key up in the screen's own tree
     * and compares identity. The lookup is the fragment's own findPreference, not the row's:
     * {@code Preference.findPreference} survives in the dex as {@code protected}, and calling it
     * from this class would link clean in the IDE and throw IllegalAccessError on the first tap.
     * A miss returns null, not an exception, so an unknown click falls through to the host's
     * handling unharmed.
     */
    private boolean isRow(androidx.preference.Preference tapped, String key) {
        return d(key) == tapped;
    }

    /**
     * The icon-row tap: advance the slot's stored icon and show the result on both of the slot's
     * rows — the icon row itself (icon + the name as its summary) and the text row above it.
     */
    private void cycleIcon(androidx.preference.Preference iconRow, int slot) {
        Context context = processContext();
        if (context == null) {
            iconRow.n("no app context — try again from the keyboard");
            return;
        }
        String picked = Hotkeys.cycleIcon(context, slot);
        // One Drawable instance holds exactly one view callback, so each row gets its own
        // inflation rather than two ImageViews sharing an object.
        Drawable icon = Hotkeys.drawableOf(context, picked);
        if (icon != null) {
            iconRow.N(icon);
        }
        androidx.preference.Preference textRow = d(Hotkeys.textKey(slot));
        if (textRow != null) {
            Drawable textIcon = Hotkeys.drawableOf(context, picked);
            if (textIcon != null) {
                textRow.N(textIcon);
            }
        }
        iconRow.n(Hotkeys.displayName(picked));
    }

    /**
     * Redraws every row's icon from the store, once per screen instance (and once more after each
     * successful import — that tap's once-a-pass runs *before* the blob lands).
     *
     * <p>The rows' XML icons are the slot *defaults*: the port exposes no row-bind hook a
     * compile-time stub can override, so a stored override can't appear at inflation. Any tap on
     * the screen runs this first, so the defaults a user might be looking at heal on the way in.
     */
    private void syncRowIconsOnce() {
        if (iconsSynced) {
            return;
        }
        Context context = processContext();
        if (context == null) {
            // Latch only after a context exists: a settings screen opened before the keyboard
            // ever ran must not burn its one sync pass drawing nothing.
            return;
        }
        iconsSynced = true;
        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            String token = Hotkeys.currentIconToken(context, slot);
            androidx.preference.Preference iconRow = d(Hotkeys.iconKey(slot));
            if (iconRow != null) {
                Drawable icon = Hotkeys.drawableOf(context, token);
                if (icon != null) {
                    iconRow.N(icon);
                }
                iconRow.n(Hotkeys.displayName(token));
            }
            androidx.preference.Preference textRow = d(Hotkeys.textKey(slot));
            if (textRow != null) {
                Drawable textIcon = Hotkeys.drawableOf(context, token);
                if (textIcon != null) {
                    textRow.N(textIcon);
                }
            }
        }
    }
}
