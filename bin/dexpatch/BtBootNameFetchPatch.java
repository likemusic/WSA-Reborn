// Bytecode counterpart of a15/0023 (docs/patches.md) (A14, L21, A15, L22): BluetoothManagerService.internalHandleOnBootPhase
// on a fresh /data does not know the adapter name and address and binds to the Bluetooth service just to read
// them (MESSAGE_GET_NAME_AND_ADDRESS); on A14/A15 that brings up the stack, which calls initialize() of
// Microsoft's HAL android.hardware.bluetooth@1.1-service.sim, and the HAL aborts there on its own scripted
// beacon (rootcanal::Beacon, std::stoi of a file path) — two SIGABRT of the HAL and com.android.bluetooth on every
// first boot. Android 16 removed this branch; so does the patch:
//   } else if (!isNameAndAddressSet()) { ...GET_NAME_AND_ADDRESS... } else { autoOnSetupTimer(); }
//   -> } else { autoOnSetupTimer(); }
// Same-size replacement: the if-nez after isNameAndAddressSet() becomes goto/16 to the same target (the else).
// The method is internalHandleOnBootPhase (A14 r75, A15) or handleOnBootPhase (A14 QPR1 GSI).
// Works on service-bluetooth.jar of the com.android.btservices APEX (bin/patch-apex-jar.sh).
// Run: bin/dexpatch.sh BtBootNameFetchPatch <in.dex> <out.dex>; exit code 1 unless exactly one branch was replaced.
// 11.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction20t;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class BtBootNameFetchPatch {
    static final String BMS = "Lcom/android/server/bluetooth/BluetoothManagerService;";

    public static void main(String[] args) throws Exception {
        final int[] done = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    MethodImplementation impl = m.getImplementation();
                    if (!m.getDefiningClass().equals(BMS) || !(m.getName().equals("internalHandleOnBootPhase") || m.getName().equals("handleOnBootPhase")) || impl == null) {
                        return base.rewrite(m);
                    }
                    List<Instruction> code = new ArrayList<>();
                    for (Instruction i : impl.getInstructions()) code.add(i);
                    int found = 0;
                    for (int k = 0; k + 2 < code.size(); k++) {
                        Instruction c = code.get(k);
                        if (!(c instanceof ReferenceInstruction) || !(((ReferenceInstruction) c).getReference() instanceof MethodReference)) continue;
                        MethodReference r = (MethodReference) ((ReferenceInstruction) c).getReference();
                        if (!r.getName().equals("isNameAndAddressSet") || !r.getDefiningClass().equals(BMS)) continue;
                        if (code.get(k + 1).getOpcode() != Opcode.MOVE_RESULT || code.get(k + 2).getOpcode() != Opcode.IF_NEZ) continue;
                        int off = ((OffsetInstruction) code.get(k + 2)).getCodeOffset();
                        code.set(k + 2, new ImmutableInstruction20t(Opcode.GOTO_16, off));
                        found++;
                    }
                    if (found == 0) return base.rewrite(m);
                    done[0] += found;
                    List<ImmutableInstruction> imm = new ArrayList<>();
                    for (Instruction i : code) imm.add(ImmutableInstruction.of(i));
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                            m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(),
                            new ImmutableMethodImplementation(impl.getRegisterCount(), imm, impl.getTryBlocks(), impl.getDebugItems()));
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("boot-time name/address fetch removed: " + done[0]);
        if (done[0] != 1) System.exit(1);
    }
}
