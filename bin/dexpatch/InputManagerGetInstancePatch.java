// Path A (ready GSI) counterpart of docs/patches.md `a17/0019`: Android 17 removed the static
// android.hardware.input.InputManager.getInstance(); Microsoft's SystemApp (targetSdk 33) calls it from
// KeyboardSetup.setKeyboardLayout during initialisation, gets NoSuchMethodError, crash-loops and never sets
// up the network. The method is added back with the Android 16 body:
//   public static InputManager getInstance() {
//       return Objects.requireNonNull(ActivityThread.currentApplication()).getSystemService(InputManager.class);
//   }
// Run: bin/dexpatch.sh InputManagerGetInstancePatch <in.dex> <out.dex>. Exit code 1 unless the class was found;
// fails if the method is already there (then the patch is not needed). 11.10.2026.
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class InputManagerGetInstancePatch {
    static final String IM = "Landroid/hardware/input/InputManager;";

    static ImmutableMethod getInstance() {
        MethodImplementationBuilder b = new MethodImplementationBuilder(2);
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference("Landroid/app/ActivityThread;", "currentApplication", List.of(), "Landroid/app/Application;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference("Ljava/util/Objects;", "requireNonNull", List.of("Ljava/lang/Object;"), "Ljava/lang/Object;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference("Landroid/content/Context;")));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_CLASS, 1, new ImmutableTypeReference(IM)));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0,
                new ImmutableMethodReference("Landroid/content/Context;", "getSystemService", List.of("Ljava/lang/Class;"), "Ljava/lang/Object;")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        b.addInstruction(new BuilderInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference(IM)));
        b.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
        return new ImmutableMethod(IM, "getInstance", Collections.emptyList(), IM,
                AccessFlags.PUBLIC.getValue() | AccessFlags.STATIC.getValue(), null, null, b.getMethodImplementation());
    }

    public static void main(String[] args) throws Exception {
        final int[] added = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<ClassDef> getClassDefRewriter(Rewriters rewriters) {
                Rewriter<ClassDef> base = super.getClassDefRewriter(rewriters);
                return c -> {
                    if (!c.getType().equals(IM)) return base.rewrite(c);
                    // Fields first: a dex-backed class reads its hidden-API flags sequentially, fields then methods.
                    List<Field> fields = new ArrayList<>();
                    for (Field f : c.getFields()) fields.add(ImmutableField.of(f));
                    List<Method> methods = new ArrayList<>();
                    for (Method m : c.getMethods()) {
                        if (m.getName().equals("getInstance") && m.getParameters().isEmpty()) {
                            throw new IllegalStateException("InputManager.getInstance() is already there");
                        }
                        methods.add(ImmutableMethod.of(m));
                    }
                    methods.add(getInstance());
                    added[0]++;
                    return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                            c.getSourceFile(), c.getAnnotations(), fields, methods);
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("InputManager.getInstance() added: " + added[0]);
        if (added[0] != 1) System.exit(1);
    }
}
