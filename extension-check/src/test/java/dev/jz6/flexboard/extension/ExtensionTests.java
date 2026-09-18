package dev.jz6.flexboard.extension;

import dev.jz6.flexboard.extension.toolbar.Hotkeys;

/**
 * Tests for the extension's own logic, run on a desktop JVM with no Android and no test framework.
 *
 * <p>Plain assertions and a {@code main}, for the same reason the Python suite uses stdlib
 * {@code unittest}: this module exists to compile the extension without the Android SDK, and adding
 * a dependency to it to prove that is the wrong trade. A failure prints what it expected and exits
 * non-zero, which is all a gate lane needs.
 *
 * <p>What is covered is the part that is not Android: the export/import blob, the label clamp, and
 * the icon-token resolution. All three ship, none had a test, and one of them shipped a bug — an
 * import that cleared a slot's text and left its icon override behind, which survived because
 * export/import/export round-trips cleanly with the stale key sitting underneath.
 */
public final class ExtensionTests {

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        roundTripPreservesText();
        roundTripPreservesIconChoice();
        importClearsASlotTheBlobOmits();
        importClearsTheIconOfAnOmittedSlot();
        importRejectsAMalformedBlob();
        importRejectsADuplicateSlot();
        importSkipsSlotsBeyondThisBuild();
        textSurvivesTabsAndNewlines();
        labelIsClampedByCodePointsNotChars();
        emptyTextHidesTheSlot();

        System.out.printf("%d checks, %d failed%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the blob

    private static void roundTripPreservesText() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "hello");
        Hotkeys.setText(context, 3, "world");
        String blob = Hotkeys.exportText(context);

        FakeContext restored = fresh();
        Hotkeys.importFromText(restored, blob);
        equal("slot 1 text", "hello", Hotkeys.textOf(restored, 1));
        equal("slot 3 text", "world", Hotkeys.textOf(restored, 3));
    }

    private static void roundTripPreservesIconChoice() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 2, "x");
        String chosen = Hotkeys.choices()[3];
        Hotkeys.setIconToken(context, 2, chosen);
        String blob = Hotkeys.exportText(context);

        FakeContext restored = fresh();
        Hotkeys.importFromText(restored, blob);
        equal("slot 2 icon", chosen, Hotkeys.currentIconToken(restored, 2));
    }

    private static void importClearsASlotTheBlobOmits() {
        // The blob is a replacement, not a merge: applyBlob's own comment says a slot it does not
        // mention is cleared, because an export should restore exactly what it captured.
        FakeContext source = fresh();
        Hotkeys.setText(source, 1, "keep");
        String blob = Hotkeys.exportText(source);

        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "keep");
        Hotkeys.setText(context, 2, "drop");
        Hotkeys.importFromText(context, blob);
        equal("omitted slot is cleared", "", Hotkeys.textOf(context, 2));
    }

    private static void importClearsTheIconOfAnOmittedSlot() {
        // The bug that shipped. The text was cleared and the icon override was not, so typing into
        // the slot later brought back an icon from before the import. Invisible to a round-trip
        // test, because serialize skips unoccupied slots.
        FakeContext context = fresh();
        String empty = Hotkeys.exportText(fresh());
        String expected = defaultIconFor(4);
        Hotkeys.setText(context, 4, "gone");
        Hotkeys.setIconToken(context, 4, Hotkeys.choices()[5]);

        Hotkeys.importFromText(context, empty);
        Hotkeys.setText(context, 4, "new");
        equal("an omitted slot's icon returns to its default",
            expected, Hotkeys.currentIconToken(context, 4));
    }

    private static void importRejectsAMalformedBlob() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "original");
        Hotkeys.importFromText(context, "not a blob at all");
        equal("a rejected paste leaves the store untouched", "original", Hotkeys.textOf(context, 1));
    }

    private static void importRejectsADuplicateSlot() {
        FakeContext source = fresh();
        Hotkeys.setText(source, 1, "a");
        String blob = Hotkeys.exportText(source);
        String duplicated = blob + blob.substring(blob.indexOf('\n') + 1);

        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "original");
        Hotkeys.importFromText(context, duplicated);
        equal("a duplicate slot rejects the whole paste", "original", Hotkeys.textOf(context, 1));
    }

    private static void importSkipsSlotsBeyondThisBuild() {
        // The one tolerated divergence: a blob from a build with more slots must still apply.
        FakeContext context = fresh();
        String blob = Hotkeys.exportText(context).trim() + "\n"
            + (Hotkeys.slotCount() + 5) + "\tfuture\t" + Hotkeys.choices()[0] + "\n";
        String outcome = Hotkeys.importFromText(context, blob);
        truthy("a wider blob still applies", Hotkeys.applied(outcome));
    }

    private static void textSurvivesTabsAndNewlines() {
        // The blob is tab and newline delimited, so both have to survive escaping.
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "a\tb\nc\\d");
        String blob = Hotkeys.exportText(context);

        FakeContext restored = fresh();
        Hotkeys.importFromText(restored, blob);
        equal("delimiters survive the round trip", "a\tb\nc\\d", Hotkeys.textOf(restored, 1));
    }

    // ---------------------------------------------------------------- the label

    private static void labelIsClampedByCodePointsNotChars() {
        // Emoji are surrogate pairs. Clamping on char indices splits one in half and renders a
        // replacement glyph on the key.
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, repeat("\uD83D\uDE00", 20));
        String label = Hotkeys.labelOf(context, 1);
        truthy("no unpaired surrogate is left at the end of a clamped label",
            label.isEmpty() || !Character.isHighSurrogate(label.charAt(label.length() - 1)));
    }

    private static void emptyTextHidesTheSlot() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "");
        falsy("an empty slot is not shown", Hotkeys.shown(context, 1));
    }

    // ---------------------------------------------------------------- harness

    /**
     * One context, emptied between tests.
     *
     * <p>Not one per test, which is what this started as and why a passing behaviour looked
     * broken. {@code Preferences.of} memoises the store in a {@code static volatile} — correct on a
     * device, where a process has exactly one preferences file, and fatal to test isolation,
     * because every later {@code FakeContext} silently reuses the first one's map. The test that
     * noticed was right about the behaviour and wrong about the setup.
     */
    private static final FakeContext CONTEXT = new FakeContext();

    private static FakeContext fresh() {
        CONTEXT.store().clear();
        return CONTEXT;
    }

    private static String defaultIconFor(int slot) {
        // Read before the caller's state is built, since there is only one store to read from.
        String saved = Hotkeys.textOf(CONTEXT, slot);
        String savedIcon = CONTEXT.store().containsKey(Hotkeys.iconKey(slot))
            ? (String) CONTEXT.store().get(Hotkeys.iconKey(slot)) : null;
        CONTEXT.store().remove(Hotkeys.iconKey(slot));
        Hotkeys.setText(CONTEXT, slot, "probe");
        String token = Hotkeys.currentIconToken(CONTEXT, slot);
        Hotkeys.setText(CONTEXT, slot, saved);
        if (savedIcon != null) {
            CONTEXT.store().put(Hotkeys.iconKey(slot), savedIcon);
        }
        return token;
    }

    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    private static void equal(String what, String expected, String actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.out.printf("  FAIL %s%n       expected %s%n       got      %s%n",
                what, quote(expected), quote(actual));
        }
    }

    private static void truthy(String what, boolean value) {
        checks++;
        if (!value) {
            failures++;
            System.out.printf("  FAIL %s (expected true)%n", what);
        }
    }

    private static void falsy(String what, boolean value) {
        checks++;
        if (value) {
            failures++;
            System.out.printf("  FAIL %s (expected false)%n", what);
        }
    }

    private static String quote(String text) {
        return text == null ? "null"
            : "'" + text.replace("\t", "\\t").replace("\n", "\\n") + "'";
    }

    private ExtensionTests() {
    }
}
