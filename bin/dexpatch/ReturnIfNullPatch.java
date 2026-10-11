// Generic: put "if (<param> == null) return <param>;" (or "return;" for a void method) at the start of a method —
// the bytecode form of a missing null check. Used for a15/0022 (AudioService.anonymizeAudioDeviceAttributesUnchecked
// without "ada == null ||", NPE in system_server that crashes GMS Nearby on a pool without BLUETOOTH_CONNECT).
// Needs no spare registers: only the parameter's own register is read.
//
// Run: bin/dexpatch.sh ReturnIfNullPatch <in.dex> <out.dex> <class descriptor> <method> <parameter index, 0-based>
//   e.g. 'Lcom/android/server/audio/AudioService;' anonymizeAudioDeviceAttributesUnchecked 0
// Exit code 1 unless exactly one method was patched. 11.10.2026.
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.List;

public class ReturnIfNullPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3];
        final int param = Integer.parseInt(args[4]);
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || impl == null) return base.rewrite(m);
                    List<? extends MethodParameter> ps = m.getParameters();
                    String type = ps.get(param).getType();
                    if (!type.startsWith("L") && !type.startsWith("[")) throw new IllegalStateException("parameter " + param + " is " + type);
                    String ret = m.getReturnType();
                    if (!ret.equals("V") && !ret.equals(type)) throw new IllegalStateException("returns " + ret + ", not " + type);
                    // Parameter registers are the last ones: [this] p0 p1 …; wide types take two.
                    int words = AccessFlags.STATIC.isSet(m.getAccessFlags()) ? 0 : 1, at = -1;
                    for (int i = 0; i < ps.size(); i++) {
                        if (i == param) at = words;
                        String t = ps.get(i).getType();
                        words += (t.equals("J") || t.equals("D")) ? 2 : 1;
                    }
                    int reg = impl.getRegisterCount() - words + at;
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    Label start = mi.newLabelForIndex(0);
                    mi.addInstruction(0, ret.equals("V") ? new BuilderInstruction10x(Opcode.RETURN_VOID)
                            : new BuilderInstruction11x(Opcode.RETURN_OBJECT, reg));
                    mi.addInstruction(0, new BuilderInstruction21t(Opcode.IF_NEZ, reg, start));
                    done[0]++;
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("null guard added: " + done[0]);
        if (done[0] != 1) System.exit(1);
    }
}
