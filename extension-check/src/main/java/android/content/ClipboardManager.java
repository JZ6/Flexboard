package android.content;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
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
