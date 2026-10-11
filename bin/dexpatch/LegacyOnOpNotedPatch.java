// Path A (ready GSI) counterpart of the full docs/patches.md `a15/0009`: android.app.AppOpsManager$OnOpNotedListener
// .onOpNoted(String op, int uid, String pkg, String tag, int flags, int mode) becomes a DEFAULT method that
// forwards to the pre-U onOpNoted(int op, int uid, String pkg, String tag, int flags, int mode) by reflection:
//
//   default void onOpNoted(String op, int uid, String pkg, String tag, int flags, int mode) {
//       try {
//           getClass().getMethod("onOpNoted", int.class, int.class, String.class, String.class, int.class, int.class)
//                   .invoke(this, AppOpsManager.strOpToOp(op), uid, pkg, tag, flags, mode);
//       } catch (ReflectiveOperationException e) { }
//   }
//
// Microsoft's SystemApp (API 33, PermissionUsageRedirectionHandler) implements only the old signature; the
// abstract new one killed the persistent process on every noted op (network flap), and the empty default of
// DefaultNoopPatch kept it alive but silenced the permission-usage indicator in Windows. Listeners that
// implement the new method are not affected (theirs overrides the default). The 7-argument default
// (device-aware APIs) calls this one, so it is covered too.
// Run: bin/dexpatch.sh LegacyOnOpNotedPatch <in.dex> <out.dex>. Exit code 1 unless exactly one method was patched. 11.10.2026.
import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder;
import com.android.tools.smali.dexlib2.builder.instruction.*;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LegacyOnOpNotedPatch {
    static final String CLS = "Landroid/app/AppOpsManager$OnOpNotedListener;";
    static final List<String> PARAMS = List.of("Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/lang/String;", "I", "I");
    static final String STR = "Ljava/lang/String;", OBJ = "Ljava/lang/Object;", INT = "Ljava/lang/Integer;";
    static final String CLASS = "Ljava/lang/Class;", METHOD = "Ljava/lang/reflect/Method;";

    static ImmutableMethodReference mref(String cls, String name, List<String> params, String ret) {
        return new ImmutableMethodReference(cls, name, params, ret);
    }

    // Locals v0..v5, parameters: p0=this v6, p1=op v7, p2=uid v8, p3=pkg v9, p4=tag v10, p5=flags v11, p6=mode v12.
    static MethodImplementationBuilder body() {
        final int THIS = 6, OP = 7, UID = 8, PKG = 9, TAG = 10, FLAGS = 11, MODE = 12;
        MethodImplementationBuilder b = new MethodImplementationBuilder(13);
        Label start = b.getLabel("try_start"), end = b.getLabel("try_end"), handler = b.getLabel("catch");
        b.addLabel("try_start");
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, OP, 0, 0, 0, 0,
                mref("Landroid/app/AppOpsManager;", "strOpToOp", List.of(STR), "I")));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, THIS, 0, 0, 0, 0,
                mref(OBJ, "getClass", List.of(), CLASS)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        // Class[] {int, int, String, String, int, int}
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 6));
        b.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 2, 2, new ImmutableTypeReference("[" + CLASS)));
        b.addInstruction(new BuilderInstruction21c(Opcode.SGET_OBJECT, 3, new ImmutableFieldReference(INT, "TYPE", CLASS)));
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_CLASS, 4, new ImmutableTypeReference(STR)));
        int[] types = {3, 3, 4, 4, 3, 3};
        for (int i = 0; i < 6; i++) {
            b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 5, i));
            b.addInstruction(new BuilderInstruction23x(Opcode.APUT_OBJECT, types[i], 2, 5));
        }
        b.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 3, new ImmutableStringReference("onOpNoted")));
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, 3, 2, 0, 0,
                mref(CLASS, "getMethod", List.of(STR, "[" + CLASS), METHOD)));
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1));
        // Object[] {Integer(op), Integer(uid), pkg, tag, Integer(flags), Integer(mode)}
        b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 2, 6));
        b.addInstruction(new BuilderInstruction22c(Opcode.NEW_ARRAY, 2, 2, new ImmutableTypeReference("[" + OBJ)));
        int[] src = {0, UID, PKG, TAG, FLAGS, MODE};
        boolean[] box = {true, true, false, false, true, true};
        for (int i = 0; i < 6; i++) {
            int r = src[i];
            if (box[i]) {
                b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, r, 0, 0, 0, 0,
                        mref(INT, "valueOf", List.of("I"), INT)));
                b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3));
                r = 3;
            }
            b.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 5, i));
            b.addInstruction(new BuilderInstruction23x(Opcode.APUT_OBJECT, r, 2, 5));
        }
        b.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, THIS, 2, 0, 0,
                mref(METHOD, "invoke", List.of(OBJ, "[" + OBJ), OBJ)));
        b.addLabel("try_end");
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        b.addLabel("catch");
        b.addInstruction(new BuilderInstruction11x(Opcode.MOVE_EXCEPTION, 0));
        b.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
        b.addCatch(new ImmutableTypeReference("Ljava/lang/ReflectiveOperationException;"), start, end, handler);
        return b;
    }

    public static void main(String[] args) throws Exception {
        final int[] patched = {0};
        DexFile dex = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.forDexVersion(39));
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Method> getMethodRewriter(Rewriters rewriters) {
                Rewriter<Method> base = super.getMethodRewriter(rewriters);
                return m -> {
                    List<String> types = new ArrayList<>();
                    for (MethodParameter p : m.getParameters()) types.add(p.getType());
                    if (!m.getDefiningClass().equals(CLS) || !m.getName().equals("onOpNoted") || !types.equals(PARAMS)) {
                        return base.rewrite(m);
                    }
                    if (!m.getReturnType().equals("V")) throw new IllegalStateException("not void");
                    patched[0]++;
                    return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                            m.getReturnType(), m.getAccessFlags() & ~AccessFlags.ABSTRACT.getValue(),
                            m.getAnnotations(), m.getHiddenApiRestrictions(), body().getMethodImplementation());
                };
            }
        });
        DexPool.writeTo(new FileDataStore(new File(args[1])), rw.getDexFileRewriter().rewrite(dex));
        System.out.println("patched methods: " + patched[0]);
        if (patched[0] != 1) System.exit(1);
    }
}
