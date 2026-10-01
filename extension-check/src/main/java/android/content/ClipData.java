package android.content;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public class ClipData {

    private CharSequence text;

    public static ClipData newPlainText(CharSequence label, CharSequence text) {
        ClipData data = new ClipData();
        data.text = text;
        return data;
    }

    public int getItemCount() {
        return text == null ? 0 : 1;
    }

    public static class Item {
        private final CharSequence text;

        Item(CharSequence text) {
            this.text = text;
        }

        public CharSequence getText() {
            return text;
        }
    }

    public Item getItemAt(int index) {
        return index == 0 && text != null ? new Item(text) : null;
    }
}
