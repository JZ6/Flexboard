package android.content;

/**
 * Compile-time shape only. FakeContext subclasses this in extension-check tests; it is never
 * packaged with the shipped extension, which compiles against android.jar.
 */
public class ClipboardManager {

    public boolean hasPrimaryClip() {
        return false;
    }

    public ClipData getPrimaryClip() {
        return null;
    }

    public void setPrimaryClip(ClipData clip) {
        // stub
    }
}
