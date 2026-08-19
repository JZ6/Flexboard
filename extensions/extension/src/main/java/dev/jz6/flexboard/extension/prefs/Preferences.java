package dev.jz6.flexboard.extension.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Gboard's own preference file, resolved the way Gboard resolves it.
 *
 * <p>Flexboard writes its settings into Gboard's store rather than a file of its own, so the
 * bytecode patches can read them back with Gboard's own accessor and nothing has to be kept in
 * sync. That only works if both sides open the same file, and the file is not the obvious one.
 *
 * <p><b>Getting this wrong is why every slider did nothing until {@code v0.1.0-dev.7}</b> — the
 * name was right and the file was the wrong one. Gboard's store ({@code Lqhy;}) is constructed with
 * a null name, which resolves to {@code PreferenceManager.getDefaultSharedPreferences}, and it does
 * this first:
 *
 * <pre>
 *   v5 = context.getApplicationContext()
 *   if (!v5.isDeviceProtectedStorage()) v5 = v5.createDeviceProtectedStorageContext()
 *   PreferenceManager.getDefaultSharedPreferences(v5)
 * </pre>
 *
 * <p>A device-protected context stores under {@code /data/user_de/…}, an ordinary Activity context
 * under {@code /data/user/…} — same {@code <packageName>_preferences} name, two unrelated files.
 * Gboard needs the keyboard to work before the device is unlocked, which is why it keeps its
 * preferences in direct-boot storage.
 *
 * <p>Mirrored line for line rather than paraphrased, including the {@code getApplicationContext}
 * call, so the two sides cannot drift. Deriving the file name from {@link Context#getPackageName()}
 * is what keeps it correct after the package-rename patch, since every caller resolves the same
 * running package.
 *
 * <p>There is no version guard because there is nothing to guard against: both context methods are
 * API 24 and Gboard's manifest declares {@code minSdkVersion} 26, so they are below the floor this
 * code can ever run on. A guard would also be worse than useless — falling back would silently
 * return to reading the wrong file.
 *
 * <p><b>Why this is its own class.</b> It lived in {@code FlexboardSettingsActivity} while the
 * settings screen was the only writer. The hotkey actions read the same file from the keyboard
 * process, and a second copy of this reasoning is exactly how the two would drift back apart.
 */
public final class Preferences {

    private static final String SUFFIX = "_preferences";

    private Preferences() {}

    /** Gboard's default preference file, opened against the context Gboard itself would use. */
    public static SharedPreferences of(Context context) {
        Context storage = deviceProtected(context);
        return storage.getSharedPreferences(
                storage.getPackageName() + SUFFIX, Context.MODE_PRIVATE);
    }

    @SuppressLint("NewApi") // Both methods are API 24; Gboard's minSdkVersion is 26.
    private static Context deviceProtected(Context context) {
        Context application = context.getApplicationContext();
        if (application.isDeviceProtectedStorage()) {
            return application;
        }
        Context storage = application.createDeviceProtectedStorageContext();
        return storage != null ? storage : application;
    }
}
