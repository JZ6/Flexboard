package android.widget;

import android.content.Context;
import android.text.Editable;

/**
 * Compile-time shape only. {@link #getText()} returns Editable rather than CharSequence because
 * the return type is part of the method descriptor at call sites — a stub that widened it would
 * compile an invoke the runtime class doesn't declare.
 */
public class EditText extends TextView {

    public EditText(Context context) {
        super(context);
    }

    public Editable getText() {
        return null;
    }
}
