// Generic: in a void method, right after "iget-object vA, vB, <field>" put "if (vA == null) return;" — the bytecode
// form of a missing null check on a field that is cleared on destroy. Used for a17/0024 (Launcher3
// TaskbarNavButtonController.onRecentsButtonLayoutChanged reads mControllers.cueBarController after onDestroy has
// set mControllers to null: NPE, launcher3 crashes). Needs no spare registers: only vA is read.
//
// Run: bin/dexpatch.sh NullFieldReturnPatch <in.dex> <out.dex> <class descriptor> <method> <field name> [all]
//   e.g. 'Lcom/android/launcher3/taskbar/TaskbarNavButtonController;' onRecentsButtonLayoutChanged mControllers
// Exit code 1 unless exactly one read of the field was guarded. With "all" every read is guarded (a field read in
// several branches: Launcher3 A14 QPR1 NavbarButtonsViewController.updateButtonLayoutSpacing and mBackButton, which is
// null until initButtons() ran — the taskbar was created in gesture navigation) and at least one is required.
// 12.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.List;

public class NullFieldReturnPatch {
    public static void main(String[] args) throws Exception {
        final String cls = args[2], name = args[3], field = args[4];
        final boolean all = args.length > 5 && args[5].equals("all");
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(cls) || !m.getName().equals(name) || impl == null) return base.rewrite(m);
                    if (!m.getReturnType().equals("V")) throw new IllegalStateException(name + " is not void");
                    MutableMethodImplementation mi = new MutableMethodImplementation(impl);
                    List<BuilderInstruction> code = mi.getInstructions();
                    java.util.List<int[]> reads = new java.util.ArrayList<>();
                    for (int k = 0; k < code.size(); k++) {
                        BuilderInstruction i = code.get(k);
                        if (i.getOpcode() != Opcode.IGET_OBJECT) continue;
                        FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                        if (!f.getDefiningClass().equals(cls) || !f.getName().equals(field)) continue;
                        if (!all && !reads.isEmpty()) throw new IllegalStateException(field + " is read more than once in " + name);
                        reads.add(new int[] {k, ((TwoRegisterInstruction) i).getRegisterA()});
                    }
                    if (reads.isEmpty()) return base.rewrite(m);
                    // From the last read backwards, so earlier indices stay valid.
                    for (int r = reads.size() - 1; r >= 0; r--) {
                        int at = reads.get(r)[0], reg = reads.get(r)[1];
                        // … iget-object vA; if-nez vA, :next; return-void; :next …
                        Label next = mi.newLabelForIndex(at + 1);
                        mi.addInstruction(at + 1, new BuilderInstruction10x(Opcode.RETURN_VOID));
                        mi.addInstruction(at + 1, new BuilderInstruction21t(Opcode.IF_NEZ, reg, next));
                        done[0]++;
                    }
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), mi);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("null field guarded: " + done[0]);
        if (all ? done[0] < 1 : done[0] != 1) System.exit(1);
    }
}
