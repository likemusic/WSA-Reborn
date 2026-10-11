// Path A (ready GSI) counterpart of docs/patches.md `a16/0017` (needed on A15 and A16; A17 removes the crop itself).
// In android.view.ViewRootImpl.relayoutWindow (framework.jar):
//   if (mPendingDragResizing && !mSurfaceSize.equals(w, h)) {
//       mTransaction.setWindowCrop(mSurfaceControl, w, h);           // (a)
//   } else if (!HardwareRenderer.isDrawingEnabled()) { ... }          // (b)
// The method has no spare registers, so instead of inserting code both calls are redirected to the helper class
// com.wsa.gsi.WsaViewRootCrop (bin/dexpatch/platform, added to framework.jar as an extra classesN.dex), each by a
// same-size invoke replacement:
//   (a) invoke-virtual {t, sc, w, h} Transaction.setWindowCrop(SurfaceControl,II) followed by goto
//         -> invoke-static {t, sc, w, h} WsaViewRootCrop.dragCrop(Transaction,SurfaceControl,II)Transaction
//   (b) invoke-static {} HardwareRenderer.isDrawingEnabled()Z that the goto of (a) jumps over (the else branch)
//         -> invoke-static {this} WsaViewRootCrop.isDrawingEnabledAfterDrag(Object)Z
// "this" is the register (a) read mTransaction from. Run: bin/dexpatch.sh ViewRootCropPatch <in.dex> <out.dex>.
// Exit code 1 unless both were replaced. 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
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

public class ViewRootCropPatch {
    static final String VRI = "Landroid/view/ViewRootImpl;";
    static final String TX = "Landroid/view/SurfaceControl$Transaction;", SC = "Landroid/view/SurfaceControl;";
    static final String HELPER = "Lcom/wsa/gsi/WsaViewRootCrop;";

    static MethodReference mref(Instruction i) {
        if (!(i instanceof ReferenceInstruction)) return null;
        Object r = ((ReferenceInstruction) i).getReference();
        return r instanceof MethodReference ? (MethodReference) r : null;
    }

    public static void main(String[] args) throws Exception {
        final int[] done = {0, 0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(VRI) || !m.getName().equals("relayoutWindow") || impl == null) {
                        return base.rewrite(m);
                    }
                    List<Instruction> code = new ArrayList<>();
                    for (Instruction i : impl.getInstructions()) code.add(i);
                    for (int k = 0; k + 1 < code.size(); k++) {
                        MethodReference r = mref(code.get(k));
                        if (code.get(k).getOpcode() != Opcode.INVOKE_VIRTUAL || r == null || !r.getDefiningClass().equals(TX)
                                || !r.getName().equals("setWindowCrop") || !r.getParameterTypes().equals(List.of(SC, "I", "I"))
                                || code.get(k + 1).getOpcode() != Opcode.GOTO) continue;
                        Instruction35c c = (Instruction35c) code.get(k);
                        // "this": the object register of the iget that loaded the transaction register
                        int self = -1;
                        for (int j = k - 1; j >= Math.max(0, k - 12); j--) {
                            Instruction g = code.get(j);
                            if (g.getOpcode() == Opcode.IGET_OBJECT && ((TwoRegisterInstruction) g).getRegisterA() == c.getRegisterC()
                                    && ((FieldReference) ((ReferenceInstruction) g).getReference()).getName().equals("mTransaction")) {
                                self = ((TwoRegisterInstruction) g).getRegisterB();
                                break;
                            }
                        }
                        if (self < 0) continue;
                        code.set(k, new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, c.getRegisterC(), c.getRegisterD(),
                                c.getRegisterE(), c.getRegisterF(), 0,
                                new ImmutableMethodReference(HELPER, "dragCrop", List.of(TX, SC, "I", "I"), TX)));
                        done[0]++;
                        // (b): the first HardwareRenderer.isDrawingEnabled() after the goto
                        for (int j = k + 2; j < Math.min(code.size(), k + 8); j++) {
                            MethodReference d = mref(code.get(j));
                            if (code.get(j).getOpcode() == Opcode.INVOKE_STATIC && d != null && d.getName().equals("isDrawingEnabled")
                                    && d.getDefiningClass().equals("Landroid/graphics/HardwareRenderer;")) {
                                code.set(j, new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, self, 0, 0, 0, 0,
                                        new ImmutableMethodReference(HELPER, "isDrawingEnabledAfterDrag", List.of("Ljava/lang/Object;"), "Z")));
                                done[1]++;
                                break;
                            }
                        }
                    }
                    if (done[0] == 0) return base.rewrite(m);
                    System.out.println("patched " + m.getName());
                    List<ImmutableInstruction> imm = new ArrayList<>();
                    for (Instruction i : code) imm.add(ImmutableInstruction.of(i));
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(impl.getRegisterCount(), imm, impl.getTryBlocks(), impl.getDebugItems()));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("drag crop applied at once: " + done[0] + ", crop removed after drag: " + done[1]);
        if (done[0] != 1 || done[1] != 1) System.exit(1);
    }
}
