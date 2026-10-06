package android;

/**
 * SDK constants for desktop extension tests. The shipped extension compiles against android.jar;
 * local tests may inline these stub values, so they must match the framework ids exactly.
 */
public final class R {
    private R() {}

    public static final class id {
        public static final int copy = 0x01020021;
        public static final int paste = 0x01020022;
        public static final int selectAll = 0x0102001f;

        private id() {}
    }
}
