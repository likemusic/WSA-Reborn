// Path A (ready GSI) counterpart of docs/patches.md `0005`: android.net.NetworkCapabilities.setNetworkSpecifier
// (framework-connectivity.jar in the com.android.tethering APEX) without the "single non-test transport"
// check — Microsoft's SystemApp sends Ethernet capabilities this check rejects. New body:
//   mNetworkSpecifier = networkSpecifier; return this;
// Run: bin/dexpatch.sh NetworkSpecifierPatch <in.dex> <out.dex>. 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class NetworkSpecifierPatch {
    static final String CLS = "Landroid/net/NetworkCapabilities;";
    public static void main(String[] args) throws Exception {
        final int[] patched = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    if (!m.getDefiningClass().equals(CLS) || !m.getName().equals("setNetworkSpecifier")
                            || m.getParameters().size() != 1 || !m.getReturnType().equals(CLS)
                            || m.getImplementation() == null) {
                        return base.rewrite(m);
                    }
                    int regs = m.getImplementation().getRegisterCount();
                    int p0 = regs - 2, p1 = regs - 1;
                    List<ImmutableInstruction> code = Arrays.asList(
                            new ImmutableInstruction22c(Opcode.IPUT_OBJECT, p1, p0,
                                    new ImmutableFieldReference(CLS, "mNetworkSpecifier",
                                            "Landroid/net/NetworkSpecifier;")),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, p0));
                    patched[0]++;
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(regs, code, null, null));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("patched methods: " + patched[0]);
        if (patched[0] != 1) System.exit(1);
    }
}
