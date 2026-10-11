// Path A (ready GSI) counterpart of 0012 (docs/patches.md) (Toast → Windows) in SystemUI.apk: at the start of
// com.android.systemui.toast.ToastUI.showToast and hideToast put
//   if (com.wsa.gsi.WsaToast.show|hide(this, <all parameters>)) return;
// The helper (bin/dexpatch/platform/com/wsa/gsi/WsaToast.java) goes into SystemUI.apk as an extra classesN.dex.
// The call takes the parameter registers as they are (invoke-static/range over this..last parameter — they are
// contiguous), so only one local is needed for the result; at method entry every local is free. On A17
// showToast has a single local, which is why nothing is copied.
// Run: bin/dexpatch.sh ToastUiPatch <in.dex> <out.dex>; exit code 1 unless both methods were patched. 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
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

public class ToastUiPatch {
    static final String TOAST_UI = "Lcom/android/systemui/toast/ToastUI;";
    static final String HELPER = "Lcom/wsa/gsi/WsaToast;";

    public static void main(String[] args) throws Exception {
        final int[] done = {0, 0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    int which = m.getName().equals("showToast") ? 0 : m.getName().equals("hideToast") ? 1 : -1;
                    if (!m.getDefiningClass().equals(TOAST_UI) || which < 0 || impl == null || !m.getReturnType().equals("V")) {
                        return base.rewrite(m);
                    }
                    // Helper signature: (Object this, <the method's parameters, hidden types as Object>)Z
                    List<String> types = new ArrayList<>();
                    types.add("Ljava/lang/Object;");
                    int words = 1;
                    for (MethodParameter p : m.getParameters()) {
                        String t = p.getType();
                        types.add(t.startsWith("L") && !t.startsWith("Ljava/") && !t.equals("Landroid/os/IBinder;") ? "Ljava/lang/Object;" : t);
                        words += (t.equals("J") || t.equals("D")) ? 2 : 1;
                    }
                    int first = impl.getRegisterCount() - words;
                    if (first < 1) throw new IllegalStateException(m.getName() + ": no local for the result");
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    Label start = mi.newLabelForIndex(0);
                    mi.addInstruction(0, new BuilderInstruction10x(Opcode.RETURN_VOID));
                    mi.addInstruction(0, new BuilderInstruction21t(Opcode.IF_EQZ, 0, start));
                    mi.addInstruction(0, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
                    mi.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, first, words,
                            new ImmutableMethodReference(HELPER, which == 0 ? "show" : "hide", types, "Z")));
                    done[which]++;
                    System.out.println("patched " + m.getName() + types);
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("showToast: " + done[0] + ", hideToast: " + done[1]);
        if (done[0] != 1 || done[1] != 1) System.exit(1);
    }
}
