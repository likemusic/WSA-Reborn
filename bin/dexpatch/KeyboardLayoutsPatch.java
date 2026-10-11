// Path A (ready GSI) counterpart of docs/patches.md `a15/0013`: in framework.jar's dex, replace the body of
// android.hardware.input.InputManager.getKeyboardLayoutsForInputDevice(InputDeviceIdentifier)
//   return new KeyboardLayout[0];          (Android 15 stub)
// with
//   return getKeyboardLayouts();           (as before Android 15)
// Microsoft's SystemApp (API 33) waits forever for keyboard_layout_english_us otherwise and never
// configures the network; the host then shuts the VM down after ~2 minutes. 11.10.2026.
//
// Run: bin/dexpatch.sh KeyboardLayoutsPatch <in.dex> <out.dex> (dexlib2 3.x from tools/smali3 — 2.5.2 does not know the
// hidden-API flags of Android 15 dex files and crashes rewriting them).
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class KeyboardLayoutsPatch {
    static final String CLS = "Landroid/hardware/input/InputManager;";
    static final String NAME = "getKeyboardLayoutsForInputDevice";
    static int patched = 0;

    public static void main(String[] args) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    if (!m.getDefiningClass().equals(CLS) || !m.getName().equals(NAME)
                            || m.getParameters().size() != 1 || m.getImplementation() == null) {
                        return base.rewrite(m);
                    }
                    MethodImplementation old = m.getImplementation();
                    int regs = Math.max(old.getRegisterCount(), 3);
                    int p0 = regs - 2; // instance method: this + 1 parameter
                    List<ImmutableInstruction> code = Arrays.asList(
                            new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, p0, 0, 0, 0, 0,
                                    new ImmutableMethodReference(CLS, "getKeyboardLayouts",
                                            Collections.emptyList(),
                                            "[Landroid/hardware/input/KeyboardLayout;")),
                            new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
                    patched++;
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags(), m.getAnnotations(),
                            m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(regs, code, null, null));
                };
            }
        });
        DexFile out = rw.getDexFileRewriter().rewrite(dex);
        DexPool.writeTo(new FileDataStore(new File(args[1])), out);
        System.out.println("patched methods: " + patched);
        if (patched != 1) System.exit(1);
    }
}
