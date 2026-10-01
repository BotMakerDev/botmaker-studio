package com.botmaker.studio.runtime.agent;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Rewrites the bot's own classes as they load: each call into a traced plugin class becomes an
 * {@code invokedynamic} whose call site {@link TracedCalls} links to the same method, timed and traced.
 *
 * <p><b>The call site, not the callee.</b> Instrumenting the plugin's methods would trace every call the plugin
 * makes to itself ({@code ImageClicker.click} calling {@code ImageFinder.find}), and a depth counter to hide
 * those would hide everything once a plugin calls back into the bot, which {@code Bot.run} does for the whole
 * run. Rewriting the bot's calls traces exactly what the bot asked for, however deep: an activity body called
 * by the flow is the bot's code, so its calls are traced too.
 *
 * <p>Uses the JDK's own class-file API (final in 24), so the agent brings no library into the bot's JVM. A class
 * that cannot be rewritten is left as it was: tracing never stops a bot from running.
 */
final class CallSites implements ClassFileTransformer {

    static final int STATIC = 0;
    static final int VIRTUAL = 1;

    private static final DirectMethodHandleDesc BOOTSTRAP = ConstantDescs.ofCallsiteBootstrap(
            ClassDesc.of(TracedCalls.class.getName()), "bootstrap", ConstantDescs.CD_CallSite,
            ConstantDescs.CD_int, ConstantDescs.CD_Class);

    private final TracePlan plan;
    private volatile boolean warned;

    CallSites(TracePlan plan) {
        this.plan = plan;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                            byte[] bytes) {
        if (redefined != null || domain == null || domain.getCodeSource() == null) return null;
        if (!plan.isBotClass(domain.getCodeSource().getLocation())) return null;
        try {
            return rewrite(bytes, loader);
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                System.err.println("[Trace] " + className + " is not traced: " + e);
            }
            return null;
        }
    }

    /** {@code bytes} with every traced call rewritten, or {@code null} when it makes none. */
    byte[] rewrite(byte[] bytes, ClassLoader loader) {
        ClassLoader resolving = loader == null ? ClassLoader.getSystemClassLoader() : loader;
        ClassFile files = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(resolving).orElse(ClassHierarchyResolver.defaultResolver())));
        ClassModel model = files.parse(bytes);
        if (!callsTraced(model)) return null;
        CodeTransform calls = (code, element) -> {
            if (element instanceof InvokeInstruction call && traced(call)) {
                code.invokedynamic(site(call));
            } else {
                code.with(element);
            }
        };
        return files.transformClass(model, ClassTransform.transformingMethodBodies(calls));
    }

    private boolean callsTraced(ClassModel model) {
        for (MethodModel method : model.methods()) {
            CodeModel code = method.code().orElse(null);
            if (code == null) continue;
            for (CodeElement element : code) {
                if (element instanceof InvokeInstruction call && traced(call)) return true;
            }
        }
        return false;
    }

    private boolean traced(InvokeInstruction call) {
        if (call.opcode() == Opcode.INVOKESPECIAL) return false;
        ClassDesc owner = call.owner().asSymbol();
        if (!owner.isClassOrInterface()) return false;
        return plan.traces(binaryName(owner), call.name().stringValue());
    }

    /** The {@code invokedynamic} that stands for {@code call}: the receiver, when there is one, is its first argument. */
    private static DynamicCallSiteDesc site(InvokeInstruction call) {
        ClassDesc owner = call.owner().asSymbol();
        boolean isStatic = call.opcode() == Opcode.INVOKESTATIC;
        MethodTypeDesc type = isStatic ? call.typeSymbol() : call.typeSymbol().insertParameterTypes(0, owner);
        return DynamicCallSiteDesc.of(BOOTSTRAP, call.name().stringValue(), type,
                isStatic ? STATIC : VIRTUAL, owner);
    }

    private static String binaryName(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }
}
