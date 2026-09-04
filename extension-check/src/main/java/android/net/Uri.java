package android.net;

/**
 * Compile-time shape only, for {@code :extension-check}. Just enough of {@code android.net.Uri}
 * for the About section's Source row to parse a URL and hand it to an ACTION_VIEW intent.
 *
 * <p>This module has no android.jar by design — it type-checks the extension with plain javac
 * against hand-written stubs, so a platform class the extension touches has to be declared here
 * before it will compile. The real class replaces this at runtime.
 */
public final class Uri {

    private Uri() {}

    public static Uri parse(String uriString) {
        return null;
    }
}
