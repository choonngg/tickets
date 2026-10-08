import java.lang.classfile.*;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import static java.lang.constant.ConstantDescs.*;

/** External measurement only; uses Java 25's standard Class-File API. */
public class WorkerTimer {
    private static final ClassDesc SYSTEM = ClassDesc.of("java.lang.System");
    private static final ClassDesc PRINT = ClassDesc.of("java.io.PrintStream");
    private static final ClassDesc STRING_BUILDER = ClassDesc.of("java.lang.StringBuilder");
    public static void premain(String args, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                                    ProtectionDomain domain, byte[] bytes) {
                if (!"com/project/ticket/domain/queue/service/QueueAdmissionWorker".equals(name)) return null;
                return ClassFile.of().transformClass(ClassFile.of().parse(bytes),
                    ClassTransform.transformingMethodBodies(m -> m.methodName().equalsString("runCycle")
                        && m.methodType().equalsString("()V"), CodeTransform.ofStateful(() -> new CodeTransform() {
                        int start;
                        Label begin, end, handler;
                        public void atStart(CodeBuilder b) {
                            start = b.allocateLocal(TypeKind.LONG);
                            b.invokestatic(SYSTEM, "nanoTime", MethodTypeDesc.of(CD_long)).lstore(start);
                            begin = b.newBoundLabel(); end = b.newLabel(); handler = b.newLabel();
                        }
                        void emit(CodeBuilder b) {
                            // Inline JDK calls: Boot's loader need not load agent classes.
                            b.getstatic(SYSTEM, "out", PRINT)
                             .new_(STRING_BUILDER).dup().invokespecial(STRING_BUILDER, "<init>", MethodTypeDesc.of(CD_void))
                             .ldc("LOADTEST_WORKER ").invokevirtual(STRING_BUILDER, "append", MethodTypeDesc.of(STRING_BUILDER, CD_String))
                             .invokestatic(SYSTEM, "currentTimeMillis", MethodTypeDesc.of(CD_long))
                             .invokevirtual(STRING_BUILDER, "append", MethodTypeDesc.of(STRING_BUILDER, CD_long))
                             .ldc(" ").invokevirtual(STRING_BUILDER, "append", MethodTypeDesc.of(STRING_BUILDER, CD_String))
                             .invokestatic(SYSTEM, "nanoTime", MethodTypeDesc.of(CD_long)).lload(start).lsub()
                             .invokevirtual(STRING_BUILDER, "append", MethodTypeDesc.of(STRING_BUILDER, CD_long))
                             .invokevirtual(STRING_BUILDER, "toString", MethodTypeDesc.of(CD_String))
                             .invokevirtual(PRINT, "println", MethodTypeDesc.of(CD_void, CD_String));
                        }
                        public void accept(CodeBuilder b, CodeElement e) {
                            if (e instanceof ReturnInstruction) emit(b);
                            b.with(e);
                        }
                        public void atEnd(CodeBuilder b) {
                            b.labelBinding(end).exceptionCatchAll(begin, end, handler).labelBinding(handler);
                            emit(b); b.athrow();
                        }
                    })));
            }
        });
    }
}
