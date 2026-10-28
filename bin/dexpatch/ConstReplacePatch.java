// Generic: in one method, replace the int literal <old> of const/4, const/16 or const with <new> — the bytecode form of
// changing a compile-time constant (javac inlines static final ints at every use). Used for 0026 (new freeform windows
// sized as on Microsoft's WSA: LaunchParamsUtil.getDefaultFreeformSize 1064x600/412x732 dp -> 1280x720/480x720, and
// TaskLaunchParamsModifier.resolveOrientation giving an app without an orientation landscape instead of portrait).
// The new literal is written with the narrowest const form that holds it, in the same register. The literal operand of
// <binop>-int/lit8 and /lit16 is replaced too, in the same form (a16/0030: a flag mask, `flags & (A | B)` — 27.10.2026).
//
// Run: bin/dexpatch.sh ConstReplacePatch <in.dex> <out.dex> <class descriptor> <method>[/<parameter count>] <old> <new> [<count>]
//   e.g. 'Lcom/android/server/wm/LaunchParamsUtil;' getDefaultFreeformSize 1064 1280
//   "/<n>" picks one overload (TaskLaunchParamsModifier has resolveOrientation/1 and resolveOrientation/3).
// Exit code 1 unless exactly <count> (default 1) literals were replaced. 12.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Format;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22b;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22s;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.List;

public class ConstReplacePatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3].split("/")[0];
        final int params = args[3].contains("/") ? Integer.parseInt(args[3].split("/")[1]) : -1;
        final int from = Integer.parseInt(args[4]), to = Integer.parseInt(args[5]);
        final int expected = args.length > 6 ? Integer.parseInt(args[6]) : 1;
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
                    for (int k = 0; k < code.size(); k++) {
                        BuilderInstruction i = code.get(k);
                        Opcode op = i.getOpcode();
                        if (op.format == Format.Format22s || op.format == Format.Format22b) {
                            if (((NarrowLiteralInstruction) i).getNarrowLiteral() != from) continue;
                            TwoRegisterInstruction t = (TwoRegisterInstruction) i;
                            boolean lit8 = op.format == Format.Format22b;
                            if (lit8 ? to != (byte) to : to != (short) to) throw new IllegalArgumentException(op.name + ": " + to + " does not fit");
                            mi.replaceInstruction(k, lit8 ? new BuilderInstruction22b(op, t.getRegisterA(), t.getRegisterB(), to)
                                    : new BuilderInstruction22s(op, t.getRegisterA(), t.getRegisterB(), to));
                            done[0]++;
                            continue;
                        }
                        if (op != Opcode.CONST_4 && op != Opcode.CONST_16 && op != Opcode.CONST) continue;
                        if (((NarrowLiteralInstruction) i).getNarrowLiteral() != from) continue;
                        int reg = ((OneRegisterInstruction) i).getRegisterA();
                        BuilderInstruction repl;
                        if (to >= -8 && to <= 7 && reg < 16) repl = new BuilderInstruction11n(Opcode.CONST_4, reg, to);
                        else if (to >= Short.MIN_VALUE && to <= Short.MAX_VALUE) repl = new BuilderInstruction21s(Opcode.CONST_16, reg, to);
                        else repl = new BuilderInstruction31i(Opcode.CONST, reg, to);
                        mi.replaceInstruction(k, repl);
                        done[0]++;
                    }
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("literals replaced: " + done[0]);
        if (done[0] != expected) System.exit(1);
    }
}
