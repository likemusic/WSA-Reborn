// Path A (ready GSI) counterpart of docs/patches/0007 (a15/0007, A16 variant in a16/frameworks_base.diff): the
// Windows host draws the frame, title and shadow of the window, so WM Shell's own caption decoration for
// freeform tasks must not show up as a second title bar inside it. In com.android.wm.shell.windowdecor
// .WindowDecoration (SystemUI.apk) R8 has inlined the helpers, so the three edits are found by instruction
// patterns, not by method; both layouts are known:
//   1. caption surface hidden: show(..) -> hide(..) where the surface is
//        A15: read from mCaptionContainerSurface right before the call;
//        A16: the one just given setLayer(.., CAPTION_LAYER_Z_ORDER = -1) in the same call chain;
//   2. caption insets always removed:
//        A15: if-nez mIsCaptionVisible before removing mWindowDecorationInsets -> nop nop;
//        A16: if-eqz mIsCaptionVisible (then mIsInsetSource) -> goto to the same target + nop;
//   3. no guest shadow, the float fed to setShadowRadius becomes 0:
//        A15: move-result of Resources.getDimension -> const/4 0;
//        A16: int-to-float right before each setShadowRadius -> const/4 0 (four calls);
//   A14 (QPR1, GSI UQ1A): 1. as A15; 2. sget-boolean ViewRootImpl.CAPTION_ON_SHELL; if-eqz -> goto/16 to the same
//        target (the else branch hides the caption and adds no insets); 3. move-result of getDimension reaches
//        setShadowRadius through a merge label, so the register is followed further (12.10.2026).
// Every replacement has the same size as the original, so branch offsets, try blocks and debug info stay valid.
// Unlike the source patch this is unconditional (no ro.wsa.host_window_decor check): it only goes into WSA slots.
// Run: bin/dexpatch.sh WindowDecorPatch <in.dex> <out.dex>. Exit code 1 unless all three edits were made (shadow: at least one call). 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction11n;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction12x;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction20t;
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

public class WindowDecorPatch {
    static final String CLS = "Lcom/android/wm/shell/windowdecor/WindowDecoration;";
    static final String TX = "Landroid/view/SurfaceControl$Transaction;";
    static final String SC = "Landroid/view/SurfaceControl;";
    static int hidden, insets, shadow;

    static String fieldName(Instruction i) {
        if (!(i instanceof ReferenceInstruction)) return null;
        Object r = ((ReferenceInstruction) i).getReference();
        return r instanceof FieldReference && ((FieldReference) r).getDefiningClass().equals(CLS)
                ? ((FieldReference) r).getName() : null;
    }

    static MethodReference methodRef(Instruction i) {
        if (!(i instanceof ReferenceInstruction)) return null;
        Object r = ((ReferenceInstruction) i).getReference();
        return r instanceof MethodReference ? (MethodReference) r : null;
    }

    // A16: ...; const/4 vL, -1; invoke-virtual {vT, vS, vL}, Transaction.setLayer; move-result-object vT2;
    //      invoke-virtual {vT2, vS}, Transaction.show — the caption surface (CAPTION_LAYER_Z_ORDER is -1).
    static boolean afterCaptionLayer(List<Instruction> out, int k) {
        if (k < 3 || out.get(k - 1).getOpcode() != Opcode.MOVE_RESULT_OBJECT) return false;
        MethodReference set = methodRef(out.get(k - 2));
        if (set == null || !set.getName().equals("setLayer") || !set.getDefiningClass().equals(TX)) return false;
        Instruction35c setI = (Instruction35c) out.get(k - 2), showI = (Instruction35c) out.get(k);
        if (setI.getRegisterD() != showI.getRegisterD()) return false;
        Instruction c = out.get(k - 3);
        return c.getOpcode() == Opcode.CONST_4 && ((Instruction11n) c).getRegisterA() == setI.getRegisterE()
                && ((Instruction11n) c).getNarrowLiteral() == -1;
    }

    static List<Instruction> patch(List<Instruction> in) {
        List<Instruction> out = new ArrayList<>(in);
        for (int k = 0; k < out.size(); k++) {
            Instruction i = out.get(k);
            MethodReference mr = methodRef(i);
            // 1. ...iget-object vY, p0, mCaptionContainerSurface; invoke-virtual {vX, vY}, Transaction.show
            if (i.getOpcode() == Opcode.INVOKE_VIRTUAL && mr != null && mr.getDefiningClass().equals(TX)
                    && mr.getName().equals("show") && k > 0
                    && ("mCaptionContainerSurface".equals(fieldName(out.get(k - 1))) || afterCaptionLayer(out, k))) {
                Instruction35c c = (Instruction35c) i;
                out.set(k, new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, c.getRegisterCount(),
                        c.getRegisterC(), c.getRegisterD(), c.getRegisterE(), c.getRegisterF(), c.getRegisterG(),
                        new ImmutableMethodReference(TX, "hide", List.of(SC), TX)));
                hidden++;
            }
            // 2. iget-boolean vX, p0, mIsCaptionVisible; if-nez vX, :keep; iget-object .., mWindowDecorationInsets
            if (i.getOpcode() == Opcode.IGET_BOOLEAN && "mIsCaptionVisible".equals(fieldName(i))
                    && k + 2 < out.size() && out.get(k + 1).getOpcode() == Opcode.IF_NEZ
                    && "mWindowDecorationInsets".equals(fieldName(out.get(k + 2)))) {
                out.set(k + 1, new ImmutableInstruction10x(Opcode.NOP));
                out.add(k + 2, new ImmutableInstruction10x(Opcode.NOP)); // if-nez is 2 code units
                insets++;
            }
            // 2 (A16). iget-boolean vX, p0, mIsCaptionVisible; if-eqz vX, :remove; iget-boolean .., mIsInsetSource
            if (i.getOpcode() == Opcode.IGET_BOOLEAN && "mIsCaptionVisible".equals(fieldName(i))
                    && k + 2 < out.size() && out.get(k + 1).getOpcode() == Opcode.IF_EQZ
                    && out.get(k + 2).getOpcode() == Opcode.IGET_BOOLEAN && out.get(k + 2) instanceof ReferenceInstruction
                    && ((FieldReference) ((ReferenceInstruction) out.get(k + 2)).getReference()).getName().equals("mIsInsetSource")) {
                int off = ((OffsetInstruction) out.get(k + 1)).getCodeOffset();
                if (off < -128 || off > 127) throw new IllegalStateException("branch too far for goto: " + off);
                out.set(k + 1, new ImmutableInstruction10t(Opcode.GOTO, off)); // same target, offsets are from the instruction start
                out.add(k + 2, new ImmutableInstruction10x(Opcode.NOP));     // if-eqz is 2 code units, goto is 1
                insets++;
            }
            // 2 (A14). sget-boolean vX, ViewRootImpl.CAPTION_ON_SHELL; if-eqz vX, :hide -> goto/16 :hide
            if (i.getOpcode() == Opcode.SGET_BOOLEAN && k + 1 < out.size()
                    && out.get(k + 1).getOpcode() == Opcode.IF_EQZ && i instanceof ReferenceInstruction) {
                FieldReference f = (FieldReference) ((ReferenceInstruction) i).getReference();
                if (f.getDefiningClass().equals("Landroid/view/ViewRootImpl;") && f.getName().equals("CAPTION_ON_SHELL")) {
                    int off = ((OffsetInstruction) out.get(k + 1)).getCodeOffset();
                    out.set(k + 1, new ImmutableInstruction20t(Opcode.GOTO_16, off)); // 2 code units, like if-eqz
                    insets++;
                }
            }
            // 3 (A16). int-to-float vX, vY; invoke-virtual {t, surface, vX}, Transaction.setShadowRadius
            if (i.getOpcode() == Opcode.INT_TO_FLOAT && k + 1 < out.size()) {
                MethodReference next = methodRef(out.get(k + 1));
                int dst = ((Instruction12x) i).getRegisterA();
                if (next != null && next.getName().equals("setShadowRadius") && next.getDefiningClass().equals(TX)
                        && ((Instruction35c) out.get(k + 1)).getRegisterE() == dst && dst < 16) {
                    out.set(k, new ImmutableInstruction11n(Opcode.CONST_4, dst, 0));
                    shadow++;
                }
            }
            // 3. invoke-virtual Resources.getDimension; move-result vX; ... setShadowRadius(.., vX)
            if (mr != null && mr.getDefiningClass().equals("Landroid/content/res/Resources;")
                    && mr.getName().equals("getDimension") && k + 1 < out.size()
                    && out.get(k + 1).getOpcode() == Opcode.MOVE_RESULT) {
                int reg = ((OneRegisterInstruction) out.get(k + 1)).getRegisterA();
                boolean feedsShadow = false;
                for (int j = k + 2; j < Math.min(out.size(), k + 6); j++) {
                    MethodReference m2 = methodRef(out.get(j));
                    if (m2 != null && m2.getName().equals("setShadowRadius")) { feedsShadow = true; break; }
                }
                if (feedsShadow && reg < 16) {
                    out.set(k + 1, new ImmutableInstruction11n(Opcode.CONST_4, reg, 0));
                    shadow++;
                }
            }
            // 3 (A14). invoke-virtual Resources.getDimension; move-result vX; goto/label ...; setShadowRadius(.., vX)
            if (mr != null && mr.getDefiningClass().equals("Landroid/content/res/Resources;")
                    && mr.getName().equals("getDimension") && k + 1 < out.size()
                    && out.get(k + 1).getOpcode() == Opcode.MOVE_RESULT) {
                int reg = ((OneRegisterInstruction) out.get(k + 1)).getRegisterA();
                for (int j = k + 2; j < Math.min(out.size(), k + 80); j++) {
                    MethodReference m2 = methodRef(out.get(j));
                    if (m2 == null || !m2.getName().equals("setShadowRadius")) continue;
                    if (((Instruction35c) out.get(j)).getRegisterE() == reg && reg < 16) {
                        out.set(k + 1, new ImmutableInstruction11n(Opcode.CONST_4, reg, 0));
                        shadow++;
                    }
                    break;
                }
            }
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(CLS) || impl == null) return base.rewrite(m);
                    List<Instruction> code = new ArrayList<>();
                    for (Instruction i : impl.getInstructions()) code.add(i);
                    int before = hidden + insets + shadow;
                    List<Instruction> patched = patch(code);
                    if (hidden + insets + shadow == before) return base.rewrite(m);
                    System.out.println("patched " + m.getName());
                    List<ImmutableInstruction> imm = new ArrayList<>();
                    for (Instruction i : patched) imm.add(ImmutableInstruction.of(i));
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(impl.getRegisterCount(), imm,
                                    impl.getTryBlocks(), impl.getDebugItems()));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("caption hidden: " + hidden + ", caption insets removed: " + insets + ", shadow zeroed: " + shadow);
        if (hidden != 1 || insets != 1 || shadow < 1) System.exit(1);
    }
}
