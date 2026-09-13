package dev.jz6.flexboard.extension.diagnostic;

import android.view.inputmethod.InputConnection;

import dev.jz6.flexboard.extension.ime.ImeService;

/**
 * Types a marker character so a gesture can be seen to have fired.
 *
 * <p><b>Temporary. Delete with the diagnostic patch.</b>
 *
 * <p>The question is whether the swipe-up emission runs at all, and the first round answered it
 * with a backspace: unmistakable, but it eats the user's text, and a deletion is a poor signal
 * because it is also what a mis-fired scrub looks like.
 *
 * <p><b>Why not dispatch a character through Gboard's own event stream.</b> That was the obvious
 * change and it is the wrong one. A printable key travels as a keycode plus a {@code String} in
 * {@code Lpnu;->e}, and the exact convention is not something I could establish from the dex
 * without guessing. A guess that is wrong does not fail loudly — it dispatches an event nothing
 * consumes, which looks exactly like the gesture never firing. That would be a false negative in
 * the one measurement this patch exists to take, and it would send the whole investigation the
 * wrong way.
 *
 * <p>{@link InputConnection#commitText} has no such ambiguity. It is plain Android, it is what
 * {@code TextAction} already uses for copy and paste, and if the gesture fires the character
 * appears. The signal is the point; going through Gboard's vocabulary would add a second thing
 * that can fail without adding anything to the answer.
 */
public final class GestureProbe {

    /**
     * A digit rather than a letter, and one that is not a plausible typo of a neighbouring key.
     * It has to be obvious in a text field mid-sentence.
     */
    private static final String MARKER = "6";

    private GestureProbe() {
    }

    /** Called from the emission. Silent when there is nothing to type into. */
    public static void fired() {
        try {
            InputConnection inputConnection = ImeService.connection();
            if (inputConnection == null) {
                return;
            }
            inputConnection.commitText(MARKER, 1);
        } catch (Throwable oops) {
            // A diagnostic must never be the thing that breaks the keyboard it is measuring.
        }
    }
}
