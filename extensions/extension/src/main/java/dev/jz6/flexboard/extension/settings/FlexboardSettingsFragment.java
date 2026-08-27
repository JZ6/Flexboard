package dev.jz6.flexboard.extension.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
     *
     * <p>The posted runnable is the open-time paint: the rows don't exist until the XML this id
     * names is inflated (long after this method returns), and the port exposes no bind hook a
     * stub can override. But inflation completes synchronously on the main thread right after
     * {@code aB()} returns, so a post to the main looper runs on the next looper iteration —
     * after the rows exist, before any tap can arrive. Handler/Looper are framework symbols, so
     * nothing here is pin-shaped. If the pass lands against a missing row or a missing context
     * it no-ops silently and the first-tap sync ({@code syncRowIconsOnce}) remains as backup.
     */
    @Override
    public int aB() {
        Context context = processContext();
        if (context == null) {
            return 0;
        }
        new Handler(Looper.getMainLooper()).post(this::paintRowsFromStore);
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
     * The dialog-content view Gboard's own text-editor dialog gets — inflated at runtime, so our
     * popups look stock instead of a bare widget in a window.
     *
     * <p>The layout id is never written down: every port DialogPreference carries it in field
     * {@code f} (written in its 4-arg ctor from the theme's dialog-preference style, read by the
     * dialog base's onCreateDialog — both facts pinned in preflight). This screen holds no
     * DialogPreference rows to read it from (plain rows, on purpose — see aA), so a probe
     * EditTextPreference is constructed on the spot: its public 2-argument constructor fills
     * {@code f} the same way. Any failure answers null and the caller falls back to its plain
     * widgets.
     */
    private View stockEditorBlock(Context ui) {
        try {
            androidx.preference.EditTextPreference probe =
                new androidx.preference.EditTextPreference(ui, null);
            Class<?> type = probe.getClass();
            java.lang.reflect.Field layout = null;
            while (layout == null && type != null) {
                try {
                    layout = type.getDeclaredField("f");
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (layout == null) {
                return null;
            }
            layout.setAccessible(true);
            int layoutId = layout.getInt(probe);
            if (layoutId == 0) {
                return null;
            }
            return LayoutInflater.from(ui).inflate(layoutId, null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The first EditText in a view tree (the stock block wraps one), or null. */
    private static EditText findEditText(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText hit = findEditText(group.getChildAt(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /**
     * Click dispatch on this screen's rows. The ported androidx click chain
     * ({@code Preference.I()V} → the manager's hosted fragment) lands on the fragment class's
     * {@code aA} by name, which is why the obfuscated letters here and on the row stub are
     * load-bearing, and why {@code super.aA(...)} is the fallback that keeps the stock rows —
     * the swipe slider and the About row — working.
     *
     * <p>One row per slot drives one composite dialog (text field + icon grid), intercepted by
     * identity; Export/Import open the blob popups. The hotkey rows are PLAIN Preferences:
     * keep them EditTextPreference-shaped and the port's performClick opens the stock text
     * editor <b>before</b> aA ever runs (performClick fires onClick, which for a DialogPreference
     * shows a dialog, and only then the tree listener) — the two-stacked-dialogs bug. When the
     * composite can't host at all, the failure surfaces in the row summary instead of a dialog.
     */
    @Override
    public boolean aA(androidx.preference.Preference preference) {
        syncRowIconsOnce();

        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            if (isRow(preference, Hotkeys.textKey(slot))) {
                if (!editHotkey(preference, slot)) {
                    preference.n("couldn't open the editor — reopen Settings from the keyboard");
                }
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
    // The composite hotkey editor
    // -----------------------------------------------------------------------------------------

    /**
     * One row's tap: the composite editor — the slot's text field above the bundled-pack icon
     * grid — when the row carries a host Activity context. Answers {@code false} when it can't
     * so the caller can report instead of silently eating the tap (the row is a plain Preference;
     * super.aA would show nothing at all).
     */
    private boolean editHotkey(androidx.preference.Preference row, int slot) {
        Context ui = dialogContext(row);
        if (ui == null) {
            return false;
        }
        try {
            showHotkeyDialog(ui, row, slot);
            return true;
        } catch (Exception dialogUnavailable) {
            return false;
        }
    }

    private void showHotkeyDialog(final Context ui, final androidx.preference.Preference row,
            final int slot) {
        LinearLayout column = new LinearLayout(ui);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ui, 16);
        column.setPadding(pad, 0, pad, dp(ui, 8));

        // Stock look for the text half too when the editor dialog's own content inflates.
        View stock = stockEditorBlock(ui);
        final EditText field;
        if (stock != null && findEditText(stock) != null) {
            field = findEditText(stock);
            column.addView(stock, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            field = new EditText(ui);
            column.addView(field, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        field.setText(Hotkeys.textOf(ui, slot));
        field.setHint("Text to commit");

        // Grid of the bundled pack, dimmed on the current choice. A tap moves the dim only —
        // both halves (text and icon) commit together through the dismiss hook:
        //   Save → commit both (the button itself just dismisses; the hook does the work).
        //   Cancel → discard both (sets the flag the hook checks first).
        //   Back / outside-tap → commit both, i.e. the autosave every other dismissal means.
        // An "undo" that works everywhere stays "tap the old icon again".
        GridLayout grid = new GridLayout(ui);
        grid.setColumnCount(4);
        int cell = dp(ui, 48);
        int spacing = dp(ui, 8);
        final String seed = Hotkeys.currentIconToken(ui, slot);
        final String[] pending = { seed };
        final boolean[] discarded = { false };
        final List<ImageView> items = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (String name : Hotkeys.choices()) {
            Drawable glyph = Hotkeys.drawableOf(ui, name);
            if (glyph == null) {
                continue;
            }
            ImageView item = new ImageView(ui);
            item.setImageDrawable(glyph);
            item.setLayoutParams(new ViewGroup.LayoutParams(cell, cell));
            item.setPadding(spacing, spacing, spacing, spacing);
            if (name.equals(seed)) {
                item.setAlpha(0.35f);
            }
            grid.addView(item);
            items.add(item);
            names.add(name);
        }
        // The framework dialog's custom panel doesn't scroll on its own — on a short window the
        // bottom cells (and the button) would be unreachable without the wrapper.
        ScrollView scroll = new ScrollView(ui);
        scroll.addView(grid);
        column.addView(scroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(ui)
            .setTitle("Hotkey " + slot)
            .setView(column)
            .setNegativeButton("Cancel", (dlog, which) -> discarded[0] = true)
            .setPositiveButton("Save", null)
            .show();
        // All dismissal roads lead here: the hook commits both halves unless Cancel said
        // otherwise. (The buttons only manage the flag; the hook is the one writer.)
        dialog.setOnDismissListener(dlog -> {
            if (discarded[0]) {
                return;
            }
            Hotkeys.setText(ui, slot, field.getText().toString());
            if (!pending[0].equals(seed)) {
                Hotkeys.setIconToken(ui, slot, pending[0]);
            }
            redrawSlot(ui, slot);
        });
        for (int i = 0; i < items.size(); i++) {
            final int index = i;
            items.get(i).setOnClickListener(v -> {
                pending[0] = names.get(index);
                for (int j = 0; j < items.size(); j++) {
                    items.get(j).setAlpha(j == index ? 0.35f : 1f);
                }
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
        // Stock look when we can borrow the editor dialog's own content view (themed ScrollView
        // + framed EditText); a bare padded field when we can't.
        View stock = stockEditorBlock(ui);
        final EditText field;
        final View content;
        if (stock != null && findEditText(stock) != null) {
            field = findEditText(stock);
            content = stock;
        } else {
            field = new EditText(ui);
            field.setMinLines(6);
            field.setGravity(Gravity.TOP);
            int padding = dp(ui, 16);
            field.setPadding(padding, padding, padding, padding);
            content = field;
        }
        field.setHint("Paste a Flexboard export here");
        new AlertDialog.Builder(ui)
            .setTitle("Import hotkeys")
            .setView(content)
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

    /**
     * Live-updates one slot's row from the store: icon, and the summary showing the committed
     * text ("Tap to edit" when empty). The summary is ours — the rows being plain Preferences is
     * what makes {@code n()} safe to call on them (on an EditTextPreference row the port's
     * ctor-installed SummaryProvider would have it throw).
     */
    private void redrawSlot(Context context, int slot) {
        androidx.preference.Preference row = d(Hotkeys.textKey(slot));
        if (row == null) {
            return;
        }
        String text = Hotkeys.textOf(context, slot);
        // The stored text is painted verbatim, but the empty-test mirrors the toolbar's
        // shown-gate: a whitespace-only slot draws no button, so it claims "Tap to edit".
        row.n(text.trim().isEmpty() ? "Tap to edit" : text);
        Drawable icon = Hotkeys.drawableOf(context, Hotkeys.currentIconToken(context, slot));
        if (icon != null) {
            row.N(icon);
        }
    }

    /**
     * Paints every row from the store — text summary and icon alike. Called by the looper-posted
     * pass from {@link #aB()} (rows can't be painted before the XML inflates, and that one lands
     * after it) and — through the latch — by any first tap, for cold opens before the keyboard
     * has ever run.
     */
    private void paintRowsFromStore() {
        Context context = processContext();
        if (context == null) {
            // Only latch on a real paint: an opening without a context available yet must not
            // burn the trigger that would have fixed the screen.
            return;
        }
        iconsSynced = true;
        redrawAllRows(context);
    }

    /**
     * The first-tap trigger for the same paint. The {@link #aB()} post usually beats any tap;
     * this stays so a screen that somehow got here unpainted still fixes itself.
     */
    private void syncRowIconsOnce() {
        if (iconsSynced) {
            return;
        }
        paintRowsFromStore();
    }

    /**
     * After a blob lands, repaint every row from the store — text summaries and icons alike.
     *
     * <p>The blob write goes to the store file directly; the rows' own summaries are painted by
     * us from that same file (see redrawSlot), so there is no second store lane to leave behind.
     */
    private void onImportApplied(Context context) {
        redrawAllRows(context);
    }

    private void redrawAllRows(Context context) {
        for (int slot = 1; slot <= Hotkeys.slotCount(); slot++) {
            redrawSlot(context, slot);
        }
    }
}
