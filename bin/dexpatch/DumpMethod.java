// Prints the instructions of a method (opcode, registers/literal/reference) — to look at bytecode before writing a
// patch for it, without baksmali. Writes <out.dex> unchanged (dexpatch.sh calling convention).
//
// Run: bin/dexpatch.sh DumpMethod <in.dex> <out.dex> <class descriptor> <method>   12.10.2026.
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;

import java.io.File;

public class DumpMethod {
    public static void main(String[] args) throws Exception {
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        int found = 0;
        for (ClassDef c : dex.getClasses()) {
            if (!c.getType().equals(args[2])) continue;
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(args[3]) || m.getImplementation() == null) continue;
                found++;
                System.out.println("== " + m.getName() + m.getParameterTypes() + m.getReturnType());
                int k = 0;
                for (Instruction i : m.getImplementation().getInstructions()) {
                    StringBuilder s = new StringBuilder(String.format("%4d %s", k++, i.getOpcode().name));
                    if (i instanceof OneRegisterInstruction) s.append(" v").append(((OneRegisterInstruction) i).getRegisterA());
                    if (i instanceof TwoRegisterInstruction) s.append(" v").append(((TwoRegisterInstruction) i).getRegisterB());
                    if (i instanceof NarrowLiteralInstruction) s.append(" #").append(((NarrowLiteralInstruction) i).getNarrowLiteral());
                    if (i instanceof ReferenceInstruction) s.append(" ").append(((ReferenceInstruction) i).getReference());
                    System.out.println(s);
                }
            }
        }
        java.nio.file.Files.copy(new File(args[0]).toPath(), new File(args[1]).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (found == 0) System.exit(1);
    }
}
