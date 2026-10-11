// Path A (ready GSI) counterpart of docs/patches.md `a15/0014` (WebView when the default provider is missing).
// In com.android.server.webkit.WebViewUpdateServiceImpl2.findPreferredWebViewPackage (services.jar):
//   iget-object vS, vThis, mSystemInterface; [A15: iget-object vC, vThis, mContext;]
//   invoke-interface {vS[, vC]}, SystemInterface.getUserChosenWebViewProvider([Context])
//     -> invoke-static {vS, vThis}, com.wsa.gsi.WsaWebView.chosenProvider(Object, Object)String
// (the helper passes the service's mContext itself where the method takes one)
// Same-size replacement; the helper (bin/dexpatch/platform) goes into services.jar as an extra classesN.dex.
// Run: bin/dexpatch.sh WebViewFallbackPatch <in.dex> <out.dex>. Exit code 1 unless exactly one call was replaced.
// 11.10.2026.
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

public class WebViewFallbackPatch {
    static final String CLS = "Lcom/android/server/webkit/WebViewUpdateServiceImpl2;";

    public static void main(String[] args) throws Exception {
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(CLS) || !m.getName().equals("findPreferredWebViewPackage") || impl == null) {
                        return base.rewrite(m);
                    }
                    List<Instruction> code = new ArrayList<>();
                    for (Instruction i : impl.getInstructions()) code.add(i);
                    boolean changed = false;
                    for (int k = 1; k < code.size(); k++) {
                        Instruction i = code.get(k);
                        if (i.getOpcode() != Opcode.INVOKE_INTERFACE) continue;
                        MethodReference r = (MethodReference) ((ReferenceInstruction) i).getReference();
                        if (!r.getName().equals("getUserChosenWebViewProvider")) continue;
                        int si = ((Instruction35c) i).getRegisterC(), self = -1;
                        // A16: iget mSystemInterface right before; A15: then iget mContext (the Context argument)
                        for (int j = k - 1; j >= Math.max(0, k - 4) && self < 0; j--) {
                            Instruction g = code.get(j);
                            if (g.getOpcode() == Opcode.IGET_OBJECT && ((TwoRegisterInstruction) g).getRegisterA() == si
                                    && ((FieldReference) ((ReferenceInstruction) g).getReference()).getName().equals("mSystemInterface")) {
                                self = ((TwoRegisterInstruction) g).getRegisterB();
                            }
                        }
                        if (self < 0) continue;
                        code.set(k, new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, si, self, 0, 0, 0,
                                new ImmutableMethodReference("Lcom/wsa/gsi/WsaWebView;", "chosenProvider",
                                        List.of("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/String;")));
                        done[0]++;
                        changed = true;
                    }
                    if (!changed) return base.rewrite(m);
                    List<ImmutableInstruction> imm = new ArrayList<>();
                    for (Instruction i : code) imm.add(ImmutableInstruction.of(i));
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(impl.getRegisterCount(), imm, impl.getTryBlocks(), impl.getDebugItems()));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("user-chosen WebView provider redirected: " + done[0]);
        if (done[0] != 1) System.exit(1);
    }
}
