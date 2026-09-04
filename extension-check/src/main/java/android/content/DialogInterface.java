package android.content;

/**
 * Compile-time shape only — the dialog's button-listener surface the settings dialogs use.
 */
public interface DialogInterface {

    void dismiss();

    interface OnClickListener {
        void onClick(DialogInterface dialog, int which);
    }

    interface OnDismissListener {
        void onDismiss(DialogInterface dialog);
    }

    interface OnShowListener {
        void onShow(DialogInterface dialog);
    }
}
