// Generic: in one method, point the N-th call of <owner>-><old name>(…) at <owner>-><new name> with the same signature —
// e.g. turn one Math.min(II) into Math.max(II). Used for 0026: LaunchParamsUtil.getDefaultFreeformSize caps a new
// freeform window to a "letterbox" of the stable area (landscape at most min(w,h) x min(w,h)^2/max(w,h) — 1500x878 px on
// a 2560x1600 display, small on 1080p), Microsoft's WSA does not; making the first Math.min (the short side of the
// stable area) a Math.max lifts the cap, fitting into the screen stays with adjustBoundsToFitInDisplayArea and the
// bridge's fitToHostWorkArea.
//
// Run: bin/dexpatch.sh InvokeRenamePatch <in.dex> <out.dex> <class descriptor> <method>[/<parameter count>] \
//          <owner descriptor> <old name> <new name> <occurrence, 1-based>
//   e.g. 'Lcom/android/server/wm/LaunchParamsUtil;' getDefaultFreeformSize 'Ljava/lang/Math;' min max 1
// Exit code 1 unless exactly one call was changed. 12.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
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
import java.util.List;

public class InvokeRenamePatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3].split("/")[0];
        final int params = args[3].contains("/") ? Integer.parseInt(args[3].split("/")[1]) : -1;
        final String owner = args[4], oldName = args[5], newName = args[6];
        final int occurrence = Integer.parseInt(args[7]);
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || impl == null) return base.rewrite(m);
                    if (params >= 0 && m.getParameterTypes().size() != params) return base.rewrite(m);
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    List<BuilderInstruction> code = mi.getInstructions();
                    int seen = 0;
                    for (int k = 0; k < code.size(); k++) {
                        BuilderInstruction i = code.get(k);
                        if (!(i instanceof ReferenceInstruction) || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
                        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
                        if (!r.getDefiningClass().equals(owner) || !r.getName().equals(oldName)) continue;
                        if (++seen != occurrence) continue;
                        MethodReference nr = new ImmutableMethodReference(owner, newName, r.getParameterTypes(), r.getReturnType());
                        if (i instanceof BuilderInstruction35c) {
                            BuilderInstruction35c c = (BuilderInstruction35c) i;
                            mi.replaceInstruction(k, new BuilderInstruction35c(c.getOpcode(), c.getRegisterCount(), c.getRegisterC(),
                                    c.getRegisterD(), c.getRegisterE(), c.getRegisterF(), c.getRegisterG(), nr));
                        } else {
                            BuilderInstruction3rc c = (BuilderInstruction3rc) i;
                            mi.replaceInstruction(k, new BuilderInstruction3rc(c.getOpcode(), c.getStartRegister(), c.getRegisterCount(), nr));
                        }
                        done[0]++;
                    }
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("calls renamed: " + done[0]);
        if (done[0] != 1) System.exit(1);
    }
}
