package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * The action behind a Flexboard hotkey button — one tap types a string the user chose.
 *
 * <p>Structurally the same button as {@code TestAction}: Gboard hands an access point an
 * arbitrary {@link Runnable} and runs it on tap. Only the action differs — {@code commitText}
 * with the slot's configured text. The slot ordinal arrives as the constructor's single int
 * while the toolbar is being built, and the text is re-read at tap time rather than cached, so a
 * change in the settings screen takes effect on the next tap, not the next keyboard reload.
 *
 * <p>The {@code int} constructor and {@code implements Runnable} are both load-bearing: the
 * patch text references the two directly, and {@code check_shared_constants.py} fails the build
 * if either side drops them.
 */
public final class Hotkey implements Runnable {

    private final int slot;

    public Hotkey(int slot) {
        this.slot = slot;
    }

    /**
     * Reads the slot's text and commits it. Both the service and the connection can be gone —
     * no editor focused, service briefly restarted — so both are checked the same way Gboard's
     * own actions do it, rather than assuming either is live.
     */
    @Override
    public void run() {
        InputMethodService service = ImeService.get();
        if (service == null) {
            return;
        }
        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) {
            return;
        }
        String text = Hotkeys.textOf((Context) service, slot);
        if (text.isEmpty()) {
            return;
        }
        connection.commitText(text, 1);
    }
}
