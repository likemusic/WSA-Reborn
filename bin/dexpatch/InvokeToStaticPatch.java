// Generic: in one method, turn a call of an instance method <target> (invoke-direct/-virtual, plain or /range) into
// invoke-static of a helper with the same registers — the receiver becomes the helper's first parameter, every other
// parameter keeps its type with object types widened to Object (the helper is compiled against the SDK and does not
// see the window manager classes). Used for 0027 on path A: TaskLaunchParamsModifier.calculate() calls
// getTaskBounds(ActivityRecord, TaskDisplayArea, WindowLayout, int, boolean, Rect) through invoke-direct/range;
// it becomes com.wsa.gsi.WsaCascade.getTaskBounds(Object, Object, Object, Object, int, boolean, Rect), which cascades
// the bounds from the topmost freeform window and then calls the original method reflectively.
//
// Run: bin/dexpatch.sh InvokeToStaticPatch <in.dex> <out.dex> <class> <method> <target name> <helper class> <helper method>
//   e.g. 'Lcom/android/server/wm/TaskLaunchParamsModifier;' calculate getTaskBounds 'Lcom/wsa/gsi/WsaCascade;' getTaskBounds
// Parameters of android.graphics.Rect and primitives keep their type (the helper declares them so). Every matching
// call is replaced: D8 may duplicate a call when it splits a method's tail into branches (A14 calculate() has the one
// source call of getTaskBounds three times). Exit code 1 if none was found. 13.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
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

public class InvokeToStaticPatch {
    static String widen(String t) {
        if (t.length() == 1 || t.equals("Landroid/graphics/Rect;")) return t;   // primitive or Rect
        return "Ljava/lang/Object;";
    }

    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3], target = args[4], helper = args[5], helperName = args[6];
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || impl == null) return base.rewrite(m);
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    List<BuilderInstruction> code = mi.getInstructions();
                    for (int k = 0; k < code.size(); k++) {
                        BuilderInstruction i = code.get(k);
                        Opcode op = i.getOpcode();
                        boolean range = op == Opcode.INVOKE_DIRECT_RANGE || op == Opcode.INVOKE_VIRTUAL_RANGE;
                        if (!range && op != Opcode.INVOKE_DIRECT && op != Opcode.INVOKE_VIRTUAL) continue;
                        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
                        if (!r.getDefiningClass().equals(cls) || !r.getName().equals(target)) continue;
                        List<String> params = new ArrayList<>();
                        params.add("Ljava/lang/Object;");
                        for (CharSequence p : r.getParameterTypes()) params.add(widen(p.toString()));
                        MethodReference nr = new ImmutableMethodReference(helper, helperName, params, r.getReturnType());
                        if (range) {
                            BuilderInstruction3rc c = (BuilderInstruction3rc) i;
                            mi.replaceInstruction(k, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, c.getStartRegister(), c.getRegisterCount(), nr));
                        } else {
                            BuilderInstruction35c c = (BuilderInstruction35c) i;
                            mi.replaceInstruction(k, new BuilderInstruction35c(Opcode.INVOKE_STATIC, c.getRegisterCount(), c.getRegisterC(),
                                    c.getRegisterD(), c.getRegisterE(), c.getRegisterF(), c.getRegisterG(), nr));
                        }
                        done[0]++;
                    }
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("calls made static: " + done[0]);
        if (done[0] == 0) System.exit(1);
    }
}
