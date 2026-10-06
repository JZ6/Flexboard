package android.content;

/**
 * SDK shape with minimal test behaviour. It runs in extension-check via FakeClipboard but is
 * never packaged into the shipped extension, which compiles against android.jar.
 */
public class ClipData {

    private CharSequence text;

    private ClipData() {}

    public static ClipData newPlainText(CharSequence label, CharSequence text) {
        ClipData data = new ClipData();
        data.text = text;
        return data;
    }

    public int getItemCount() {
        return 1;
    }

    public static class Item {
        private final CharSequence text;

        public Item(CharSequence text) {
            this.text = text;
        }

        public CharSequence getText() {
            return text;
        }
    }

    public Item getItemAt(int index) {
        if (index != 0) {
            throw new IndexOutOfBoundsException("one clipboard item");
        }
        return new Item(text);
    }
}
