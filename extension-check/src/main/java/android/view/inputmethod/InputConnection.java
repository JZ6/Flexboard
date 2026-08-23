package android.view.inputmethod;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public interface InputConnection {

    boolean performContextMenuAction(int id);

    boolean commitText(CharSequence text, int newCursorPosition);
}
