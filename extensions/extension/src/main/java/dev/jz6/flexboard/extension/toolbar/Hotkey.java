package dev.jz6.flexboard.extension.toolbar;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * The tap action for a hotkey slot: commit the slot's stored text at the cursor. The patch emits
 * {@code new-instance} plus this constructor; reading the text at tap time (rather than at
 * toolbar build time) keeps edited text live without a keyboard restart.
 */
public final class Hotkey implements Runnable {

    private final int slot;

    public Hotkey(int slot) {
        this.slot = slot;
    }

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
