package android.view;

/**
 * Compile-only stub: the hidden SurfaceControl.Transaction methods bin/dexpatch/platform needs, which the public
 * android.jar does not have. Never packaged — at run time the platform's own class is used.
 */
public final class SurfaceControl {
    public static class Transaction {
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) { throw new RuntimeException("stub"); }
        public void apply() { throw new RuntimeException("stub"); }
    }
}
