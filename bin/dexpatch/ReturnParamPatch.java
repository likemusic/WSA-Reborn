// Path A (ready GSI): replace the body of an instance method with "return <parameter N>" (object parameters only).
// Example — docs/patches.md `a16/0015` for the GSI's services.jar: PlatformCompat.fixTargetSdk(ApplicationInfo, int)
// exists only to force the platform targetSdk onto uid system (the read-only flag system_uid_target_system_sdk
// is folded to true by R8, so there is no call left to switch off); returning the ApplicationInfo unchanged
// is the Android 15 behaviour the patch restores:
//   ReturnParamPatch in.dex out.dex Lcom/android/server/compat/PlatformCompat; fixTargetSdk 1
// N is 1-based (1 = first parameter after "this"). Exit code 1 if nothing matched. 11.10.2026.
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class ReturnParamPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3];
        final int n = Integer.parseInt(args[4]);
        final int[] patched = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || m.getImplementation() == null) {
                        return base.rewrite(m);
                    }
                    if ((m.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0) throw new IllegalStateException("static method");
                    List<? extends MethodParameter> ps = m.getParameters();
                    String type = ps.get(n - 1).getType();
                    if (!(type.startsWith("L") || type.startsWith("[")) || !type.equals(m.getReturnType())) {
                        throw new IllegalStateException("parameter " + n + " is " + type + ", returns " + m.getReturnType());
                    }
                    int regs = m.getImplementation().getRegisterCount(), ins = 1;
                    for (MethodParameter p : ps) ins += (p.getType().equals("J") || p.getType().equals("D")) ? 2 : 1;
                    int reg = regs - ins + 1; // register of the first parameter (p0 = this)
                    for (int i = 0; i < n - 1; i++) {
                        String t = ps.get(i).getType();
                        reg += (t.equals("J") || t.equals("D")) ? 2 : 1;
                    }
                    patched[0]++;
                    System.out.println("return p" + n + ": " + cls + "->" + name);
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), ps, m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(regs,
                                    Collections.singletonList(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, reg)),
                                    null, null));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("patched methods: " + patched[0]);
        if (patched[0] == 0) System.exit(1);
    }
}
