package android.view;

/**
 * Compile-time shape only, for {@code :extension-check}. Only {@code setSoftInputMode}, which the
 * scratch pad sets so the keyboard is up before the dialog finishes animating in rather than
 * depending on default focus behaviour.
 */
public class Window {

    public void setSoftInputMode(int mode) {
    }
}
