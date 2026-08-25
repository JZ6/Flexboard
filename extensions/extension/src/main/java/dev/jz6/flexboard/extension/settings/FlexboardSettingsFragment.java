package dev.jz6.flexboard.extension.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.libraries.inputmethod.preferencewidgets.CommonPreferenceFragment;

import dev.jz6.flexboard.extension.ime.ImeService;
import dev.jz6.flexboard.extension.toolbar.Hotkeys;

import java.util.ArrayList;
import java.util.List;

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
     * The context that can show a dialog: the one the tapped row was constructed with. Every
     * port {@code Preference} carries it in the field {@code j} — written by the 4-arg
     * constructor, and consumed by the ported {@code performClick} as the target of
     * {@code Context.startActivity}, which without {@code FLAG_ACTIVITY_NEW_TASK} means it is an
     * Activity: the settings host itself. Reading it is reflection over an app class (never a
     * hidden-SDK surface), and the field is pinned in preflight; any failure answers {@code null}
     * and the caller falls back to the no-dialog behavior.
     */
    private static Context dialogContext(androidx.preference.Preference row) {
        try {
            java.lang.reflect.Field field =
                androidx.preference.Preference.class.getDeclaredField("j");
            field.setAccessible(true);
            return (Context) field.get(row);
        } catch (ReflectiveOperationException | ClassCastException | SecurityException ignored) {
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
     * row opens the icon-picker grid, and Export/Import open the blob popups. Rows keep working
     * without a dialog surface — icons fall back to tap-cycling, import to reading the
     * clipboard; outcomes always surface in the tapped row's own summary.
     */
    @Override
    public boolean aA(androidx.preference.Preference preference) {
        syncRowIconsOnce();

        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            if (isRow(preference, Hotkeys.iconKey(slot))) {
                pickIcon(preference, slot);
                return true;
            }
        }
        if (isRow(preference, "flexboard_hotkey_copy")) {
            export(preference);
            return true;
        }
        if (isRow(preference, "flexboard_hotkey_paste")) {
            importBlob(preference);
            return true;
        }
        return super.aA(preference);
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

    // -----------------------------------------------------------------------------------------
    // Icon picker
    // -----------------------------------------------------------------------------------------

    /**
     * The icon row's tap. Preferred: a grid dialog of the whole bundled pack in a framework
     * AlertDialog — appcompat is stripped from Gboard, so the framework class under the host
     * theme is as native as dialogs get here. Fallback when the row's context can't host one:
     * tap-cycling (the pre-dialog behavior), which needs no UI at all.
     */
    private void pickIcon(androidx.preference.Preference iconRow, int slot) {
        Context ui = dialogContext(iconRow);
        if (ui != null) {
            try {
                showIconDialog(ui, iconRow, slot);
                return;
            } catch (Exception dialogUnavailable) {
                // fall through to the cycle fallback
            }
        }
        Context context = processContext();
        if (context == null) {
            iconRow.n("no app context — try again from the keyboard");
            return;
        }
        Hotkeys.cycleIcon(context, slot);
        redrawSlot(context, slot);
    }

    private void showIconDialog(Context ui, androidx.preference.Preference iconRow, int slot) {
        GridLayout grid = new GridLayout(ui);
        grid.setColumnCount(4);
        int cell = dp(ui, 48);
        int padding = dp(ui, 8);
        String current = Hotkeys.currentIconToken(ui, slot);
        // Listeners attach after the dialog exists, so they can dismiss it; the two lists are
        // index-parallel for the same reason the old picker kept the correspondence by position.
        List<ImageView> items = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String name : Hotkeys.choices()) {
            Drawable glyph = Hotkeys.drawableOf(ui, name);
            if (glyph == null) {
                continue;
            }
            ImageView item = new ImageView(ui);
            item.setImageDrawable(glyph);
            item.setLayoutParams(new ViewGroup.LayoutParams(cell, cell));
            item.setPadding(padding, padding, padding, padding);
            if (name.equals(current)) {
                item.setAlpha(0.35f);
            }
            grid.addView(item);
            items.add(item);
            names.add(name);
        }
        // The framework dialog's custom panel doesn't scroll on its own — on a short window the
        // bottom cells (and the buttons) would be unreachable without the wrapper.
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(grid);
        final AlertDialog dialog = new AlertDialog.Builder(ui)
            .setTitle("Hotkey " + slot + " icon")
            .setView(scroll)
            .setNegativeButton("Reset to default", (dlog, which) -> {
                Hotkeys.resetIconToken(ui, slot);
                redrawSlot(ui, slot);
                // The dialog stays open after a reset — move the "current" dimming onto the
                // slot's default cell rather than leaving it on the cell that was chosen.
                String now = Hotkeys.currentIconToken(ui, slot);
                for (int i = 0; i < items.size(); i++) {
                    items.get(i).setAlpha(names.get(i).equals(now) ? 0.35f : 1f);
                }
            })
            .setNeutralButton("Cancel", null)
            .show();
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            items.get(i).setOnClickListener(v -> {
                Hotkeys.setIconToken(ui, slot, names.get(index));
                redrawSlot(ui, slot);
                dialog.dismiss();
            });
        }
    }

    // -----------------------------------------------------------------------------------------
    // Export / Import
    // -----------------------------------------------------------------------------------------

    /** Export: copy the blob to the clipboard as always, and additionally show it in a dialog. */
    private void export(androidx.preference.Preference row) {
        Context context = processContext();
        if (context == null) {
            row.n("no app context — try again from the keyboard");
            return;
        }
        row.n(Hotkeys.exportToClipboard(context));
        Context ui = dialogContext(row);
        if (ui == null) {
            return;
        }
        try {
            showExportDialog(ui, context);
        } catch (Exception dialogUnavailable) {
            // the copy + summary already happened; the popup is best-effort
        }
    }

    private void showExportDialog(Context ui, Context store) {
        TextView blob = new TextView(ui);
        blob.setText(Hotkeys.exportText(store));
        blob.setTextIsSelectable(true);
        int padding = dp(ui, 16);
        blob.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(blob);
        new AlertDialog.Builder(ui)
            .setTitle("Exported hotkeys")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show();
    }

    /** Import: a paste box with Apply; falls back to reading the clipboard with no dialog. */
    private void importBlob(androidx.preference.Preference row) {
        Context ui = dialogContext(row);
        if (ui != null) {
            try {
                showImportDialog(ui, row);
                return;
            } catch (Exception dialogUnavailable) {
                // fall through to the clipboard path
            }
        }
        Context context = processContext();
        if (context == null) {
            row.n("no app context — try again from the keyboard");
            return;
        }
        String outcome = Hotkeys.importFromClipboard(context);
        row.n(outcome);
        if (outcome.startsWith("imported")) {
            onImportApplied(context);
        }
    }

    private void showImportDialog(final Context ui, final androidx.preference.Preference row) {
        final EditText field = new EditText(ui);
        field.setMinLines(6);
        field.setGravity(Gravity.TOP);
        field.setHint("Paste a Flexboard export here");
        int padding = dp(ui, 16);
        field.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(ui)
            .setTitle("Import hotkeys")
            .setView(field)
            .setPositiveButton("Apply", (dlog, which) -> {
                String blob = field.getText().toString();
                String outcome = Hotkeys.importFromText(ui, blob);
                row.n(outcome);
                if (outcome.startsWith("imported")) {
                    onImportApplied(ui);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // -----------------------------------------------------------------------------------------
    // Redrawing
    // -----------------------------------------------------------------------------------------

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Live-updates the icon and summary of one slot's two rows from the store. */
    private void redrawSlot(Context context, int slot) {
        String token = Hotkeys.currentIconToken(context, slot);
        setRowIcon(d(Hotkeys.iconKey(slot)), Hotkeys.displayName(token), context, token);
        setRowIcon(d(Hotkeys.textKey(slot)), null, context, token);
    }

    private static void setRowIcon(androidx.preference.Preference row, String summary,
            Context context, String token) {
        if (row == null) {
            return;
        }
        Drawable icon = Hotkeys.drawableOf(context, token);
        if (icon != null) {
            // One Drawable instance holds exactly one view callback — inflate per row.
            row.N(icon);
        }
        if (summary != null) {
            row.n(summary);
        }
    }

    /**
     * Redraws every row's icon from the store, once per screen instance at first tap, and again
     * wholesale after a successful import — the rows show what the store holds, not the XML.
     *
     * <p>The rows' XML icons are the slot *defaults*: the port exposes no row-bind hook a
     * compile-time stub can override, so a stored override can't appear at inflation.
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
        redrawAllRows(context);
    }

    /**
     * After a blob lands: push each slot's text through its row's own persistence lane
     * ({@code EditTextPreference.i}, the ported setText), then repaint the icons.
     *
     * <p>The blob write goes to the SharedPreferences file directly, but a row's dialog reads
     * through the datastore bridge into Gboard's store, whose in-memory view never hears about
     * our file write — the import took effect for the toolbar while the settings rows kept
     * showing the pre-import text. {@code i} writes the same value through the bridge (and
     * persists, so both lanes agree from then on), which is also why the fallback clipboard path
     * needs it identically. {@code ae} alone would do the persist half, but it is protected —
     * the stub deliberately omits it so that call fails to compile rather than to link on device.
     */
    private void onImportApplied(Context context) {
        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            androidx.preference.Preference row = d(Hotkeys.textKey(slot));
            if (row instanceof androidx.preference.EditTextPreference) {
                ((androidx.preference.EditTextPreference) row)
                    .i(Hotkeys.textOf(context, slot));
            }
        }
        redrawAllRows(context);
    }

    private void redrawAllRows(Context context) {
        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            redrawSlot(context, slot);
        }
    }
}
