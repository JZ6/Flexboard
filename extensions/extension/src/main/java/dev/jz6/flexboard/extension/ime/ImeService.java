package dev.jz6.flexboard.extension.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.prefs.Preferences;

/**
 * Gboard's running {@link InputMethodService}, published from patched bytecode so the extension's
 * toolbar actions can reach the editor.
 *
 * <p><b>Why a static holder rather than a constructor argument.</b> The patch that builds the
 * access points runs inside the access-points code, which has no reference to the IME service; the
 * patch that does have one runs in {@code onCreate}. So the service is published from there and
 * picked up here. Gboard is a single IME process with a single service instance, so there is one
 * writer and the field is only ever overwritten with an equivalent value.
 *
 * <p>The field is {@code volatile} because the writer is the main thread during service creation
 * and the reader is whichever thread Gboard runs its key actions on. It is never cleared on
 * destroy: a stale service whose input connection has gone is handled by {@link #connection()},
 * whereas clearing it would open a window where a live keyboard has no action at all.
 *
 * <p><b>Why this is its own class.</b> It began inside {@code TextAction}, which was the only thing
 * that needed it. Hotkeys need it too, and a second copy of a single-writer static would be two
 * holders racing to describe one service — so it moved here rather than being reached into from
 * another package.
 */
public final class ImeService {

    private static volatile InputMethodService service;

    private ImeService() {}

    /** Called from patched bytecode at the top of Gboard's {@code InputMethodService.onCreate}. */
    public static void setService(InputMethodService inputMethodService) {
        service = inputMethodService;
    }

    /** The published service, or {@code null} before {@code onCreate} has run. */
    public static InputMethodService get() {
        return service;
    }

    /**
     * The current editor connection, or {@code null} if there is nothing to act on.
     *
     * <p>Null whenever no editor is focused, and briefly during connection restarts. Gboard's own
     * editing actions null-check the same way rather than assuming a connection is live.
     */
    public static InputConnection connection() {
        InputMethodService inputMethodService = service;
        if (inputMethodService == null) {
            return null;
        }
        return inputMethodService.getCurrentInputConnection();
    }

    /**
     * Gboard's own preference store, or {@code null} before the service exists.
     *
     * <p>Same file the settings screen writes — see {@code Preferences} for why the context matters
     * as much as the name.
     */
    public static SharedPreferences preferences() {
        Context context = service;
        if (context == null) {
            return null;
        }
        return Preferences.of(context);
    }
}
