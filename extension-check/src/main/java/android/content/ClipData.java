package android.content;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public class ClipData {

    public static ClipData newPlainText(CharSequence label, CharSequence text) {
        return null;
    }

    public int getItemCount() {
        return 0;
    }

    public static class Item {
        public CharSequence getText() {
            return null;
        }
    }

    public Item getItemAt(int index) {
        return null;
    }
}
