// Path A (ready GSI) counterpart of docs/patches.md `0029` (a freeform task that became visible without a transition gets its
// leash position and crop) in SystemUI.apk: at the start of com.android.wm.shell.freeform.FreeformTaskListener
// onTaskAppeared(RunningTaskInfo, SurfaceControl) and onTaskInfoChanged(RunningTaskInfo) put
//   com.wsa.gsi.WsaSettle.appeared|changed(this, <parameters>);
// The helper (bin/dexpatch/platform/com/wsa/gsi/WsaSettle.java) goes into SystemUI.apk as an extra classesN.dex. At the
// start of onTaskInfoChanged the task's State still holds the previous RunningTaskInfo, so the helper tells "became
// visible" itself. The call takes the parameter registers as they are (invoke-static/range this..last parameter), no
// locals are needed.
// Run: bin/dexpatch.sh FreeformSettlePatch <in.dex> <out.dex>; exit code 1 unless both methods were patched. 15.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FreeformSettlePatch {
    static final String LISTENER = "Lcom/android/wm/shell/freeform/FreeformTaskListener;";
    static final String HELPER = "Lcom/wsa/gsi/WsaSettle;";
    static final String INFO = "Landroid/app/ActivityManager$RunningTaskInfo;";
    static final String LEASH = "Landroid/view/SurfaceControl;";

    public static void main(String[] args) throws Exception {
        final int[] done = {0, 0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    String params = String.join(",", m.getParameterTypes().stream().map(CharSequence::toString).toList());
                    int which = m.getName().equals("onTaskAppeared") && params.equals(INFO + "," + LEASH) ? 0
                            : m.getName().equals("onTaskInfoChanged") && params.equals(INFO) ? 1 : -1;
                    if (!m.getDefiningClass().equals(LISTENER) || which < 0 || impl == null || !m.getReturnType().equals("V")) {
                        return base.rewrite(m);
                    }
                    List<String> types = new ArrayList<>();
                    types.add("Ljava/lang/Object;");
                    types.add("Ljava/lang/Object;");
                    if (which == 0) types.add("Ljava/lang/Object;");
                    int words = types.size();
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    mi.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, impl.getRegisterCount() - words,
                            words, new ImmutableMethodReference(HELPER, which == 0 ? "appeared" : "changed", types, "V")));
                    done[which]++;
                    System.out.println("patched " + m.getName());
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("onTaskAppeared: " + done[0] + ", onTaskInfoChanged: " + done[1]);
        if (done[0] != 1 || done[1] != 1) System.exit(1);
    }
}
