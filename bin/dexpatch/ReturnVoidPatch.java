// Path A (ready GSI): make void methods return immediately, in place of a source patch we cannot
// apply to a prebuilt image. Example — docs/patches.md `0004` for the GSI's service-connectivity.jar:
//   ReturnVoidPatch in.dex out.dex Lcom/android/server/ethernet/EthernetServiceImpl; enforceAutomotiveDevice maybeValidateEthernetTransport
// Every overload of each named method is replaced by "return-void" (non-void methods are refused).
// Exit code 1 if nothing matched (run it over every classesN.dex of a jar). 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ReturnVoidPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2];
        final Set<String> names = new HashSet<>(Arrays.asList(args).subList(3, args.length));
        final int[] patched = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    if (!m.getDefiningClass().equals(cls) || !names.contains(m.getName())
                            || m.getImplementation() == null) {
                        return base.rewrite(m);
                    }
                    if (!m.getReturnType().equals("V")) {
                        throw new IllegalStateException(m.getName() + " does not return void");
                    }
                    patched[0]++;
                    System.out.println("return-void: " + cls + "->" + m.getName());
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(m.getImplementation().getRegisterCount(),
                                    Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                                    null, null));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("patched methods: " + patched[0]);
        if (patched[0] == 0) System.exit(1);
    }
}
