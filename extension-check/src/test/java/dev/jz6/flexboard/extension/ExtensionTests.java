package dev.jz6.flexboard.extension;

import android.content.Context;
import android.view.MotionEvent;

import dev.jz6.flexboard.extension.diagnostic.CrashRecorder;
import dev.jz6.flexboard.extension.prefs.GboardSettings;
import dev.jz6.flexboard.extension.gesture.SwipeUp;
import dev.jz6.flexboard.extension.toolbar.Hotkeys;

/**
 * Tests for the extension's own logic, run on a desktop JVM with no Android and no test framework.
 *
 * <p>Plain assertions and a {@code main}, for the same reason the Python suite uses stdlib
 * {@code unittest}: this module exists to compile the extension without the Android SDK, and adding
 * a dependency to it to prove that is the wrong trade. A failure prints what it expected and exits
 * non-zero, which is all a gate lane needs.
 *
 * <p>What is covered is the export/import blob, label clamp, icon-token selection, crash recorder,
 * and per-key suggested defaults. Several shipped before a test; one of them shipped a bug — an
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
        clipboardExportCanBeImported();
        importSkipsSlotsBeyondThisBuild();
        textSurvivesTabsAndNewlines();
        labelIsClampedByCodePointsNotChars();
        emptyTextHidesTheSlot();
        crashReportNamesTheExceptionAndTheThread();
        crashReportKeepsBothEndsOfALongTrace();
        crashReportIsSavedSynchronously();
        crashReportReachesTheClipboardOnce();
        crashReportSurvivesAClipboardThatCannotBeReached();
        crashReportSurvivesARemovalThatDoesNotCommit();
        nothingSavedMeansNothingDelivered();
        theHandlerRecordsThenHandsTheCrashOn();
        aFailureWhileRecordingStillHandsTheCrashOn();
        theHandlerToleratesHavingNoPredecessor();
        suggestedSettingsLeaveUserChoicesAlone();
        oldSlideRatioIsRemovedOnce();
        chosenSlideRatioIsPreserved();
        preferenceFilesStaySeparate();
        aNewDownClearsAStaleSwipeClaim();
        aDiagonalMoveDoesNotClaimTheGesture();
        aRefusedTakeoverReleasesTheSwipe();
        aConfirmedTakeoverKeepsTheSwipe();

        System.out.printf("%d checks, %d failed%n", checks, failures);
        if (failures > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the crash recorder

    private static void crashReportNamesTheExceptionAndTheThread() {
        Thread main = new Thread("main");
        String report = CrashRecorder.describe(main, new IllegalStateException("boom"));
        truthy("names the exception class", report.contains("IllegalStateException"));
        truthy("carries the message", report.contains("boom"));
        truthy("names the thread", report.contains("thread: main"));
    }

    /**
     * The end of a long trace is the deepest cause, which is usually the real reason, and the start
     * is what was thrown and from where. Keeping only the start would have thrown the answer away.
     */
    private static void crashReportKeepsBothEndsOfALongTrace() {
        Throwable chain = new RuntimeException("root-cause-marker");
        for (int i = 0; i < 150; i++) {
            chain = new RuntimeException("wrapper " + i, chain);
        }
        String report = CrashRecorder.describe(new Thread("main"), chain);
        truthy("a long trace is cut down", report.length() < 4000);
        truthy("the outermost exception survives", report.contains("wrapper 149"));
        truthy("the deepest cause survives", report.contains("root-cause-marker"));
        truthy("the cut says so", report.contains("middle omitted"));
    }

    /** apply() writes on a background thread and the process is about to be killed. */
    private static void crashReportIsSavedSynchronously() {
        FakeContext context = new FakeContext();
        CrashRecorder.record(context, "report");
        equal("the report is saved", "report", (String) context.store("flexboard_crash").get("last"));
        equal("by commit(), not apply()", "1", String.valueOf(context.commits));
    }

    private static void crashReportReachesTheClipboardOnce() {
        FakeContext context = new FakeContext();
        CrashRecorder.record(context, "the trace");
        truthy("delivery reports success", CrashRecorder.deliver(context));
        equal("the clipboard holds the trace", "the trace", context.clipboard().text);
        falsy("and it is forgotten once delivered", context.store("flexboard_crash").containsKey("last"));
        falsy("so a second start delivers nothing", CrashRecorder.deliver(context));
    }

    /** The whole point of keeping it: a report that cannot be delivered now is delivered later. */
    private static void crashReportSurvivesAClipboardThatCannotBeReached() {
        FakeContext context = new FakeContext().withoutClipboard();
        CrashRecorder.record(context, "the trace");
        falsy("delivery reports failure", CrashRecorder.deliver(context));
        equal("the report is still there", "the trace", (String) context.store("flexboard_crash").get("last"));
    }

    private static void nothingSavedMeansNothingDelivered() {
        FakeContext context = new FakeContext();
        falsy("no report, no delivery", CrashRecorder.deliver(context));
        equal("and the clipboard is untouched", null, context.clipboard().text);
    }

    private static void crashReportSurvivesARemovalThatDoesNotCommit() {
        FakeContext context = new FakeContext().withoutCommit();
        context.store("flexboard_crash").put("last", "the trace");
        falsy("failed removal is not reported as delivery", CrashRecorder.deliver(context));
        equal("trace remains for a later attempt", "the trace",
            (String) context.store("flexboard_crash").get("last"));
    }

    private static void theHandlerRecordsThenHandsTheCrashOn() {
        final boolean[] handedOn = {false};
        FakeContext context = new FakeContext();
        Thread.UncaughtExceptionHandler handler = CrashRecorder.handler(context,
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    handedOn[0] = true;
                }
            });
        handler.uncaughtException(new Thread("main"), new IllegalStateException("boom"));
        truthy("the crash is recorded", context.store("flexboard_crash").containsKey("last"));
        truthy("and handed to the handler that was there before", handedOn[0]);
    }

    /** A reporter that can swallow a crash leaves a frozen keyboard instead of a restarted one. */
    private static void aFailureWhileRecordingStillHandsTheCrashOn() {
        final boolean[] handedOn = {false};
        Context broken = new Context() {
            @Override public Context getApplicationContext() { return this; }
            @Override public String getPackageName() { return "x"; }
            @Override public android.content.SharedPreferences getSharedPreferences(String n, int m) {
                throw new IllegalStateException("storage is unavailable");
            }
            @Override public android.content.res.Resources getResources() { return null; }
        };
        Thread.UncaughtExceptionHandler handler = CrashRecorder.handler(broken,
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread thread, Throwable error) {
                    handedOn[0] = true;
                }
            });
        handler.uncaughtException(new Thread("main"), new IllegalStateException("boom"));
        truthy("the crash still reaches the previous handler", handedOn[0]);
    }

    private static void theHandlerToleratesHavingNoPredecessor() {
        FakeContext context = new FakeContext();
        CrashRecorder.handler(context, null)
            .uncaughtException(new Thread("main"), new IllegalStateException("boom"));
        truthy("it still records", context.store("flexboard_crash").containsKey("last"));
    }

    private static FakeContext settingsContext() {
        return fresh()
            .withResource(0x7f140a01, "pref_enable_flick_symbols")
            .withResource(0x7f140ad3, "keyboard_slide_sensitivity_ratio")
            .withResource(0x7f140a21, "enable_secondary_digits")
            .withResource(0x7f1409c0, "block_offensive_words")
            .withResource(0x7f140b6f, "show_suggestions")
            .withResource(0x7f140b6e, "show_suggestion_strip")
            .withResource(0x7f140a07, "pref_key_enable_grammar_checker")
            .withResource(0x7f140a28, "enable_smart_reply");
    }

    private static void suggestedSettingsLeaveUserChoicesAlone() {
        FakeContext context = settingsContext();
        context.store().put("show_suggestions", true);
        GboardSettings.defaultSuggestedSettings(context);
        truthy("user's choice sticks", (Boolean) context.store().get("show_suggestions"));
        falsy("other default still lands", (Boolean) context.store().get("block_offensive_words"));
        falsy("no sensitivity value seeded", context.store().containsKey("keyboard_slide_sensitivity_ratio"));
    }

    private static void oldSlideRatioIsRemovedOnce() {
        FakeContext context = settingsContext();
        context.store().put("keyboard_slide_sensitivity_ratio", "0.6");
        GboardSettings.defaultSuggestedSettings(context);
        falsy("obsolete 0.6 value removed", context.store().containsKey("keyboard_slide_sensitivity_ratio"));
        context.store().put("keyboard_slide_sensitivity_ratio", "0.6");
        GboardSettings.defaultSuggestedSettings(context);
        equal("migration only runs once", "0.6",
            (String) context.store().get("keyboard_slide_sensitivity_ratio"));
    }

    private static void chosenSlideRatioIsPreserved() {
        FakeContext context = settingsContext();
        context.store().put("keyboard_slide_sensitivity_ratio", "0.8");
        GboardSettings.defaultSuggestedSettings(context);
        equal("user-selected ratio survives", "0.8",
            (String) context.store().get("keyboard_slide_sensitivity_ratio"));
    }

    private static void preferenceFilesStaySeparate() {
        FakeContext context = settingsContext();
        CrashRecorder.record(context, "the trace");
        falsy("crash data is not a Gboard preference", context.store().containsKey("last"));
        truthy("the separate crash file has the trace", context.store("flexboard_crash").containsKey("last"));
    }

    /** The scrub handler name is how SwipeUp chooses which of Gboard's handler subclasses acts. */
    private static final class ScrubDeleteMotionEventHandler {}

    private static MotionEvent motion(int action, float x, float y) {
        return MotionEvent.obtain(0L, 0L, action, x, y, 0);
    }

    private static void aNewDownClearsAStaleSwipeClaim() {
        Object handler = new ScrubDeleteMotionEventHandler();
        equal("first touch passes", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 100))));
        equal("first vertical move claims", "1", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 60))));
        // Simulate a lost UP/CANCEL: the claim is still held, but a fresh DOWN must start clean.
        equal("new touch after lost UP passes", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 150))));
        equal("new swipe may claim", "1", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 110))));
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_CANCEL, 0, 110));
    }

    private static void aDiagonalMoveDoesNotClaimTheGesture() {
        Object handler = new ScrubDeleteMotionEventHandler();
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 100));
        equal("diagonal move remains a stock gesture", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 30, 60))));
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_CANCEL, 30, 60));
    }

    /** Another handler already owned the gesture: no revert was sent, and the rest is stock again. */
    private static void aRefusedTakeoverReleasesTheSwipe() {
        Object handler = new ScrubDeleteMotionEventHandler();
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 100));
        equal("swipe up claims", "1", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 60))));
        SwipeUp.tookOver(false);
        equal("after a refusal the next move is the scrub engine's", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 20))));
        equal("and the release is not swallowed either", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_UP, 0, 20))));
    }

    /** The revert was sent: the swipe stays ours until it ends, so no key is typed on release. */
    private static void aConfirmedTakeoverKeepsTheSwipe() {
        Object handler = new ScrubDeleteMotionEventHandler();
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 100));
        equal("swipe up claims", "1", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 60))));
        SwipeUp.tookOver(true);
        equal("later moves are swallowed", "2", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_MOVE, 0, 20))));
        equal("the release is swallowed", "2", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_UP, 0, 20))));
        equal("the next gesture starts clean", "0", String.valueOf(
            SwipeUp.decide(handler, motion(MotionEvent.ACTION_DOWN, 0, 100))));
        SwipeUp.decide(handler, motion(MotionEvent.ACTION_CANCEL, 0, 100));
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
        equal("bad row is rejected", "export is malformed — nothing changed",
            Hotkeys.importFromText(context, "flexboard-hotkeys v1\n1\tonly-two\n"));
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

    private static void clipboardExportCanBeImported() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "clipboard-test");
        Hotkeys.exportToClipboard(context);
        Hotkeys.setText(context, 1, "overwritten");
        truthy("clipboard import succeeds", Hotkeys.applied(Hotkeys.importFromClipboard(context)));
        equal("clipboard restores the slot", "clipboard-test", Hotkeys.textOf(context, 1));
    }

    private static void importSkipsSlotsBeyondThisBuild() {
        // The one tolerated divergence: a blob from a build with more slots must still apply.
        FakeContext context = fresh();
        String blob = Hotkeys.exportText(context).trim() + "\n"
            + "1\tkept\t" + Hotkeys.choices()[0] + "\n"
            + (Hotkeys.slotCount() + 5) + "\tfuture\t" + Hotkeys.choices()[0] + "\n";
        String outcome = Hotkeys.importFromText(context, blob);
        truthy("a wider blob still applies", Hotkeys.applied(outcome));
        equal("an overlapping slot still lands", "kept", Hotkeys.textOf(context, 1));
        truthy("skipped slot is reported", outcome.contains("beyond this build's slot count skipped"));
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
        equal("code-point clamp keeps eleven whole emoji plus ellipsis",
            repeat("\uD83D\uDE00", 11) + "…", label);
    }

    private static void emptyTextHidesTheSlot() {
        FakeContext context = fresh();
        Hotkeys.setText(context, 1, "visible");
        truthy("a nonempty slot is shown", Hotkeys.shown(context, 1));
        Hotkeys.setText(context, 1, "");
        falsy("an empty slot is not shown", Hotkeys.shown(context, 1));
        Hotkeys.setText(context, 1, "  ");
        falsy("a whitespace-only slot is not shown", Hotkeys.shown(context, 1));
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
