package dev.jz6.flexboard.extension.textaction;

import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * The action behind Flexboard's <b>Select all</b>, <b>Copy</b> and <b>Paste</b> toolbar buttons.
 *
 * <p>This class is deliberately made of nothing but framework types. Gboard hands a button an
 * arbitrary {@link Runnable} and runs it on tap, so the only thing crossing from patched bytecode
 * into here is one of the small integers below. Nothing obfuscated is named in Java, and there is
 * no reflection, which is what keeps a Gboard version bump from silently turning these into
 * no-ops.
 *
 * <p><b>Why one class rather than three.</b> The three buttons differ only in which context-menu
 * action they ask for, so three classes would be three copies of {@link #run()} and three places
 * for its null handling to drift apart.
 *
 * <p><b>Why an ordinal rather than the framework id.</b> The patch emits the constructor argument,
 * and passing {@code android.R.id.copy} would mean hardcoding {@code 0x0102001b} in Kotlin. The ids
 * below are Flexboard's own, mapped to the framework's in {@link #menuAction()}, so the framework
 * constants stay symbolic in the one language that can name them. They are duplicated in
 * {@code TextActionsPatch.kt} and held in step by {@code check_shared_constants.py}.
 *
 * <p><b>The IME service lives in {@link ImeService}.</b> It used to be a static field here, back
 * when these three buttons were the only thing that needed it. Hotkeys need it too, so it moved
 * rather than being copied — see that class for why a static holder is the right shape at all.
 */
public final class TextAction implements Runnable {

    /** Must match TEXT_ACTION_SELECT_ALL in TextActionsPatch.kt. */
    private static final int SELECT_ALL = 0;

    /** Must match TEXT_ACTION_COPY in TextActionsPatch.kt. */
    private static final int COPY = 1;

    /** Must match TEXT_ACTION_PASTE in TextActionsPatch.kt. */
    private static final int PASTE = 2;

    private final int action;

    /** Required by the patch, which emits {@code new-instance} plus this constructor. */
    public TextAction(int action) {
        this.action = action;
    }

    @Override
    public void run() {
        InputConnection inputConnection = ImeService.connection();
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
