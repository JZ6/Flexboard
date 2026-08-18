package dev.jz6.flexboard.extension.selectall;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

/**
 * The action behind Flexboard's <b>Select all</b> toolbar button.
 *
 * <p>This class is deliberately made of nothing but framework types. Gboard hands the button an
 * arbitrary {@link Runnable} and runs it on tap, so the only thing crossing from patched bytecode
 * into here is {@code this} — an {@link InputMethodService}, which is a platform class. Nothing
 * obfuscated is named in Java, and there is no reflection, which is what keeps a Gboard version
 * bump from silently turning this into a no-op.
 *
 * <p><b>Why a static holder rather than a constructor argument.</b> The patch that builds the
 * access point runs inside the access-points code, which has no reference to the IME service; the
 * patch that does have one runs in {@code onCreate}. So the service is published from there and
 * picked up here. Gboard is a single IME process with a single service instance, so there is one
 * writer and the field is only ever overwritten with an equivalent value.
 *
 * <p>The field is {@code volatile} because the writer is the main thread during service creation
 * and the reader is whichever thread Gboard runs its key actions on. It is never cleared on
 * destroy: a stale service whose input connection has gone is handled by the null check below,
 * whereas clearing it would open a window where a live keyboard has no action at all.
 */
public final class SelectAll implements Runnable {

    private static volatile InputMethodService service;

    /** Called from patched bytecode at the top of Gboard's {@code InputMethodService.onCreate}. */
    public static void setService(InputMethodService inputMethodService) {
        service = inputMethodService;
    }

    /** Required by the patch, which emits {@code new-instance} plus a no-argument constructor. */
    public SelectAll() {
    }

    @Override
    public void run() {
        InputMethodService inputMethodService = service;
        if (inputMethodService == null) {
            return;
        }

        // Null whenever no editor is focused, and briefly during connection restarts. Gboard's own
        // editing actions null-check the same way rather than assuming a connection is live.
        InputConnection inputConnection = inputMethodService.getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }

        inputConnection.performContextMenuAction(android.R.id.selectAll);
    }
}
