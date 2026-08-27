package android.app;

import android.content.Context;
import android.content.DialogInterface;
import android.view.View;

/**
 * Compile-time shape only. The framework AlertDialog is the one dialog class in a Gboard process
 * — appcompat is stripped — and what Gboard's own hand-rolled dialogs sit on. Only the members
 * the settings screen calls. {@code dismiss()} lives on the stub itself rather than a Dialog
 * superclass for the same reason the host-fragment stub re-declares its superclass's letters:
 * resolution at runtime walks supers, the compile artifact only needs the call shape.
 */
public class AlertDialog {

    public void show() {
        // stub
    }

    public void dismiss() {
        // stub
    }

    public void setOnDismissListener(DialogInterface.OnDismissListener listener) {
        // stub
    }

    public static class Builder {

        public Builder(Context context) {
            // stub
        }

        public Builder setTitle(CharSequence title) {
            return this;
        }

        public Builder setView(View view) {
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            return this;
        }

        public AlertDialog show() {
            return null;
        }
    }
}
