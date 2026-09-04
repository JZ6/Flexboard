package android.content;

/**
 * Compile-time shape only, for {@code :extension-check}. Thrown by {@code startActivity} when no
 * app can handle the intent — the case the Source row catches so a device with no browser reports
 * it in the row's summary rather than crashing the settings screen.
 */
public class ActivityNotFoundException extends RuntimeException {

    public ActivityNotFoundException() {
        super();
    }

    public ActivityNotFoundException(String name) {
        super(name);
    }
}
