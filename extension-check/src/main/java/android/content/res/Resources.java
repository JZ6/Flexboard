package android.content.res;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs.
 */
public class Resources {

    public int getIdentifier(String name, String defType, String defPackage) {
        return 0;
    }

    public String getResourceTypeName(int resid) {
        return null;
    }

    public android.util.DisplayMetrics getDisplayMetrics() {
        return null;
    }

    /** Thrown by the id-addressed lookups when the id names nothing on this build. */
    public static class NotFoundException extends RuntimeException {
    }
}
