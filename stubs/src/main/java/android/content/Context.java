package android.content;

/**
 * Compile-time shape only — an empty marker.
 *
 * <p>Lives in {@code :stubs} because the EditTextPreference stub beside it names the type in its
 * constructor's signature (the descriptor has to match the real class at runtime; a placeholder
 * type would compile an invoke the device can't resolve). When the real extension module compiles
 * against the SDK's android.jar, this stub shares the classpath with the genuine Context; it is
 * {@code compileOnly}, so only the type reference survives into the DEX. extension-check keeps its
 * own richer Context — the source-set copy wins over this jar entry locally.
 */
public abstract class Context {
}
