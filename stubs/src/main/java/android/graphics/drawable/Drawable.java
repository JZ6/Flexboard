package android.graphics.drawable;

/**
 * Compile-time shape only — an empty marker.
 *
 * <p>This class lives in {@code :stubs} rather than with the rest of the hand-written android
 * surface ({@code extension-check/src/main/java/android}) because the androidx-preference stub
 * beside it refers to it — {@code stubs} cannot see {@code extension-check} (the dependency runs
 * the other way). When the real extension module compiles against the SDK's android.jar, this
 * stub appears on the classpath alongside the genuine Drawable; only references to the type
 * survive into the extension DEX either way (it is {@code compileOnly}), and the runtime answer
 * is always the framework's class. Keep it empty: a member here would silently shadow the SDK's
 * at compile time for anything consuming {@code :stubs}.
 */
public class Drawable {
}
