package androidx.preference;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Compile-time stub of the port's EditTextPreference.
 *
 * <p>The settings screen no longer rows any of these — a DialogPreference's {@code onClick} in
 * the port's performClick fires the stock dialog ahead of the fragment's {@code aA} intercept —
 * but the composite/popup dialogs still inflate the stock editor's content view, and the layout
 * id lives on the superclass chain (DialogPreference's {@code f} field). The cost of learning it
 * as a constant is a per-build lookup; instead this stub exists so the fragment can construct a
 * probe instance on demand and read {@code f} off it reflectively. Only the constructor is
 * needed.
 */
public class EditTextPreference extends Preference {

    public EditTextPreference(Context context, AttributeSet attrs) {
        super();
    }
}
