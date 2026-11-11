// Generic: in one method, make a boolean call's result a constant — `move-result vN` right after `invoke <call>` becomes
// `const/4 vN <value>`, the call itself stays. Used for 0035: AndroidPackageUtils.createNativeLibraryHandle passes
// isExtractNativeLibrariesRequested() to NativeLibraryHelper.Handle, whose native code then skips uncompressed libraries.
//
// Run: bin/dexpatch.sh ForceCallResultPatch <in.dex> <out.dex> <class descriptor> <method> <called method name> <0|1>
// Exit code 1 unless exactly one result was replaced. 04.11.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.List;

public class ForceCallResultPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3], call = args[4];
        final int value = Integer.parseInt(args[5]);
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
                    for (int k = 0; k + 1 < code.size(); k++) {
                        BuilderInstruction i = code.get(k);
                        if (!i.getOpcode().name.startsWith("invoke-")) continue;
                        if (!(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
                        if (!((MethodReference) ((ReferenceInstruction) i).getReference()).getName().equals(call)) continue;
                        if (code.get(k + 1).getOpcode() != Opcode.MOVE_RESULT) continue;
                        int reg = ((OneRegisterInstruction) code.get(k + 1)).getRegisterA();
                        if (reg > 15) throw new IllegalArgumentException("register v" + reg + " does not fit const/4");
                        mi.replaceInstruction(k + 1, new BuilderInstruction11n(Opcode.CONST_4, reg, value));
                        done[0]++;
                    }
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("results replaced: " + done[0]);
        if (done[0] != 1) System.exit(1);
    }
}
