package dev.jz6.flexboard.extension.diagnostic;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Records why the keyboard crashed, and puts it on the clipboard the next time it starts.
 *
 * <p>Exists because a swipe-up build crashed the keyboard on a device with no logcat, no adb and no
 * way to read a stack trace, and three releases had already been spent choosing between explanations
 * that all looked equally plausible. The exception is one line of fact; the guesses were not. This
 * turns a crash into a thing you can paste.
 *
 * <p>Installed at app start, only by builds of the swipe-up patch. It is a diagnostic for that
 * patch, not a feature: it overwrites the clipboard after a crash, which is a side effect nobody who
 * has not asked for it should have.
 *
 * <p>Two steps, because a process that is dying cannot do anything slow:
 * <ol>
 *   <li>on an uncaught exception, {@link #record} saves the trace with a synchronous
 *       {@code commit()} — {@code apply()} writes on a background thread and the process is about to
 *       be killed — and then hands the exception to whatever handler was installed before, so
 *       Android's own crash handling is unchanged;</li>
 *   <li>on the next start, {@link #deliver} copies the saved trace to the clipboard and deletes it.
 *       If the clipboard is unavailable the trace is kept and tried again on the next start, rather
 *       than lost.</li>
 * </ol>
 *
 * <p>Everything here catches {@code Throwable}. A crash reporter that can itself crash the keyboard
 * is worse than none.
 */
public final class CrashRecorder {

    private static final String PREFS = "flexboard_crash";
    private static final String KEY = "last";

    /** Enough for the exception, its causes and the frames that matter; not a wall of text. */
    private static final int LIMIT = 3500;
    private static final int HEAD = 2300;
    private static final int TAIL = 1100;

    private static volatile boolean installed;

    private CrashRecorder() {
    }

    /** Called at app start with the application context. Safe to call more than once. */
    public static void install(Context context) {
        try {
            if (installed || context == null) {
                return;
            }
            installed = true;
            Context app = context.getApplicationContext();
            if (app == null) {
                app = context;
            }
            deliver(app);
            Thread.setDefaultUncaughtExceptionHandler(
                    handler(app, Thread.getDefaultUncaughtExceptionHandler()));
        } catch (Throwable oops) {
            // See the class comment.
        }
    }

    /**
     * The handler that records a crash and then hands it on.
     *
     * <p>A factory rather than a private class so the two properties that matter can be tested
     * without installing anything process-wide: the crash is handed on even when recording it
     * fails, and even when there was no handler before. If it were not, a crash would leave a
     * frozen keyboard instead of a restarted one.
     */
    public static Thread.UncaughtExceptionHandler handler(
            Context context, Thread.UncaughtExceptionHandler previous) {
        return new Recorder(context, previous);
    }

    /** Records the crash, then lets the previous handler do what it always did. */
    private static final class Recorder implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        Recorder(Context context, Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                record(context, describe(thread, error));
            } catch (Throwable oops) {
                // Nothing may stand between the crash and Android's own handling of it.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        }
    }

    /**
     * The crash as text: the thread, the exception and its causes.
     *
     * <p>When the trace is too long the start and the end are kept and the middle dropped. The start
     * says what was thrown and from where; the end is the deepest {@code Caused by}, which is
     * usually the real reason.
     */
    public static String describe(Thread thread, Throwable error) {
        StringWriter buffer = new StringWriter();
        PrintWriter writer = new PrintWriter(buffer);
        writer.print("Flexboard crash report\nthread: ");
        writer.println(thread == null ? "?" : thread.getName());
        if (error == null) {
            writer.println("(no exception)");
        } else {
            error.printStackTrace(writer);
        }
        writer.flush();
        String text = buffer.toString();
        if (text.length() <= LIMIT) {
            return text;
        }
        return text.substring(0, HEAD) + "\n... (middle omitted) ...\n"
                + text.substring(text.length() - TAIL);
    }

    /** Saves the report, synchronously. */
    public static void record(Context context, String report) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY, report);
        editor.commit();
    }

    /**
     * Copies a saved report to the clipboard and forgets it.
     *
     * @return true if a report was delivered; false if there was nothing, or it could not be
     *         delivered and has been kept for next time
     */
    public static boolean deliver(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String report = prefs.getString(KEY, null);
            if (report == null) {
                return false;
            }
            Object service = context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (!(service instanceof ClipboardManager)) {
                return false;
            }
            ((ClipboardManager) service).setPrimaryClip(ClipData.newPlainText("Flexboard crash", report));
            prefs.edit().remove(KEY).commit();
            return true;
        } catch (Throwable oops) {
            return false;
        }
    }
}
