package android.view;

/**
 * Compile-only stub: the SurfaceControl.Transaction methods bin/dexpatch/platform needs (hidden ones the public
 * android.jar does not have, and the public ones next to them — this class shadows android.jar's). Never packaged — at run time the platform's own class is used.
 */
public final class SurfaceControl {
    public static class Transaction {
        public Transaction setWindowCrop(SurfaceControl sc, int width, int height) { throw new RuntimeException("stub"); }
        public Transaction setPosition(SurfaceControl sc, float x, float y) { throw new RuntimeException("stub"); }
        public Transaction show(SurfaceControl sc) { throw new RuntimeException("stub"); }
        public void apply() { throw new RuntimeException("stub"); }
    }
}
