package dev.jz6.flexboard.extension.textaction;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.InputConnection;

/**
 * The action behind Flexboard's <b>Select all</b>, <b>Copy</b> and <b>Paste</b> toolbar buttons.
 *
 * <p>This class is deliberately made of nothing but framework types. Gboard hands a button an
 * arbitrary {@link Runnable} and runs it on tap, so the only things crossing from patched bytecode
 * into here are {@code this} — an {@link InputMethodService}, a platform class — and one of the
 * small integers below. Nothing obfuscated is named in Java, and there is no reflection, which is
 * what keeps a Gboard version bump from silently turning these into no-ops.
 *
 * <p><b>Why one class rather than three.</b> The three buttons differ only in which context-menu
 * action they ask for. Three classes would mean three copies of the service holder, and three
 * places for the null handling below to drift apart — or one holder that the other two reach into,
 * which is the same coupling with more indirection.
 *
 * <p><b>Why an ordinal rather than the framework id.</b> The patch emits the constructor argument,
 * and passing {@code android.R.id.copy} would mean hardcoding {@code 0x0102001b} in Kotlin. The ids
 * below are Flexboard's own, mapped to the framework's in {@link #menuAction()}, so the framework
 * constants stay symbolic in the one language that can name them. They are duplicated in
 * {@code TextActionsPatch.kt} and held in step by {@code check_shared_constants.py}.
 *
 * <p><b>Why a static holder rather than a constructor argument for the service.</b> The patch that
 * builds the access points runs inside the access-points code, which has no reference to the IME
 * service; the patch that does have one runs in {@code onCreate}. So the service is published from
 * there and picked up here. Gboard is a single IME process with a single service instance, so there
 * is one writer and the field is only ever overwritten with an equivalent value.
 *
 * <p>The field is {@code volatile} because the writer is the main thread during service creation
 * and the reader is whichever thread Gboard runs its key actions on. It is never cleared on
 * destroy: a stale service whose input connection has gone is handled by the null check below,
 * whereas clearing it would open a window where a live keyboard has no action at all.
 */
public final class TextAction implements Runnable {

    /** Must match TEXT_ACTION_SELECT_ALL in TextActionsPatch.kt. */
    private static final int SELECT_ALL = 0;

    /** Must match TEXT_ACTION_COPY in TextActionsPatch.kt. */
    private static final int COPY = 1;

    /** Must match TEXT_ACTION_PASTE in TextActionsPatch.kt. */
    private static final int PASTE = 2;

    private static volatile InputMethodService service;

    /** Called from patched bytecode at the top of Gboard's {@code InputMethodService.onCreate}. */
    public static void setService(InputMethodService inputMethodService) {
        service = inputMethodService;
    }

    private final int action;

    /** Required by the patch, which emits {@code new-instance} plus this constructor. */
    public TextAction(int action) {
        this.action = action;
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

        inputConnection.performContextMenuAction(menuAction());
    }

    /**
     * Flexboard's ordinal to the framework's context-menu id.
     *
     * <p>Select all is the default rather than a case of its own, so an argument the patch should
     * never emit degrades to the button this started as instead of to nothing at all. A tap that
     * does the wrong one of these is reportable; a tap that silently does nothing is not.
     */
    private int menuAction() {
        switch (action) {
            case COPY:
                return android.R.id.copy;
            case PASTE:
                return android.R.id.paste;
            case SELECT_ALL:
            default:
                return android.R.id.selectAll;
        }
    }
}
