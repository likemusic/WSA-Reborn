// Path A (ready GSI): turn an abstract interface method into a default method with an empty body, so
// that implementations built against an older API (which lack it) no longer hit AbstractMethodError.
// Used for android.app.AppOpsManager$OnOpNotedListener.onOpNoted(String,int,String,String,int,int):
// Microsoft's SystemApp (API 33) implements only the old signature, and every noted op crashed it
// (a network flap each time) — the simple counterpart of docs/patches.md `a15/0009` (that one also
// forwards to the old method; this one just drops the call).
//   bin/dexpatch.sh DefaultNoopPatch <in.dex> <out.dex> <class> <method> <param1,param2,...>
// e.g. 'Landroid/app/AppOpsManager$OnOpNotedListener;' onOpNoted 'Ljava/lang/String;,I,Ljava/lang/String;,Ljava/lang/String;,I,I'
// 11.10.2026.
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DefaultNoopPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3];
        final List<String> params = args[4].isEmpty() ? Collections.emptyList() : Arrays.asList(args[4].split(","));
        final int[] patched = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    List<String> types = new ArrayList<>();
                    for (MethodParameter p : m.getParameters()) types.add(p.getType());
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || !types.equals(params)) {
                        return base.rewrite(m);
                    }
                    if (!m.getReturnType().equals("V")) throw new IllegalStateException("not void");
                    if ((m.getAccessFlags() & AccessFlags.ABSTRACT.getValue()) == 0) throw new IllegalStateException("not abstract");
                    int regs = 1; // this
                    for (String t : types) regs += (t.equals("J") || t.equals("D")) ? 2 : 1;
                    patched[0]++;
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags() & ~AccessFlags.ABSTRACT.getValue(),
                            m.getAnnotations(), m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(regs,
                                    Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),
                                    null, null));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("patched methods: " + patched[0]);
        if (patched[0] != 1) System.exit(1);
    }
}
