package dev.jz6.flexboard.extension;

import android.content.Context;

/**
 * Resource lookups by name, done so they survive the package rename.
 *
 * <p>Everything Flexboard adds to the APK — the settings screen XML, the whole icon pack — is
 * addressed by <em>name</em> at runtime rather than by a baked id, because aapt2 assigns those
 * ids during patching and they would not survive a rebuild on a different machine. That makes
 * {@code Resources#getIdentifier} load-bearing, and its third argument is the problem.
 *
 * <p>{@code getIdentifier}'s {@code defPackage} is matched against the <em>resource table's</em>
 * package name, not the application's. Those are the same thing in a normal build and are not
 * here: {@code installAsGboardClonePatch} ships on by default and rewrites the package in
 * AndroidManifest.xml, while {@code resources.arsc} still names itself
 * {@code com.google.android.inputmethod.latin}. Passing {@code context.getPackageName()} on a
 * cloned build therefore asks for a package the table does not contain, and a miss returns 0 —
 * which as a resource id means a blank settings screen and an icon pack that resolves to nothing.
 *
 * <p>In practice the lookup does resolve today, so something between here and the table is
 * reconciling the two names — most likely the encoder rewriting the package when the manifest
 * changes. This does not depend on that continuing to be true. It asks the way the app is named
 * first, and falls back to the name the table was built with, which costs nothing when the first
 * attempt succeeds and keeps the feature working if the encoder ever stops.
 */
public final class ResourceIds {

    /**
     * Must match {@code Constants.GBOARD_PACKAGE_NAME} in the patches; the constants checker
     * holds the two together. This is the name baked into {@code resources.arsc}, which is the
     * package the table answers to regardless of what the manifest was renamed to.
     */
    private static final String STOCK_PACKAGE_NAME = "com.google.android.inputmethod.latin";

    private ResourceIds() {}

    /**
     * The id of {@code type/name}, or 0 when this build has no such resource.
     *
     * <p>Never throws: {@code getIdentifier} answers a miss with 0 rather than an exception, and
     * both callers already treat 0 as "fall back to what the XML gave us".
     */
    public static int byName(Context context, String type, String name) {
        android.content.res.Resources resources = context.getResources();
        int id = resources.getIdentifier(name, type, context.getPackageName());
        if (id != 0) {
            return id;
        }
        return resources.getIdentifier(name, type, STOCK_PACKAGE_NAME);
    }
}
