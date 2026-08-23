package android;

/**
 * Compile-time shape only — the set of members the extension actually uses, nothing more.
 * CI compiles against the real android.jar; this stub is never packaged and never runs. The
 * values are the framework's real ones, so nothing here is load-bearing either way.
 */
public final class R {
    private R() {}

    public static final class id {
        public static final int copy = 0x01020021;
        public static final int paste = 0x01020022;
        public static final int selectAll = 0x01020031;

        private id() {}
    }
}
