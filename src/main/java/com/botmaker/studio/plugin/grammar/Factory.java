package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;

/**
 * What writes a value, as the host sees it: the only place the shape of a {@link ComponentType#factory()}
 * is asked about.
 *
 * <p>The host reads the executable's shape and never invokes it. A plugin's factory is third-party code
 * reached through a contract method that may throw, so {@link #of} contains that and answers empty.
 */
public record Factory(Executable executable) {

    /** How the call is spelled. A closed set: these are the three shapes Java has. */
    public enum Kind {
        /** {@code new Owner(p₁, …)}. */
        CONSTRUCTOR,
        /** {@code Owner.name(p₁, …)}. */
        STATIC,
        /** {@code part₀.name(p₁, …)}: read, never written. */
        RECEIVER
    }

    /** The factory a component type declares, or empty when it has none or asking threw. */
    public static Optional<Factory> of(ComponentType<?> component) {
        try {
            Executable executable = component == null ? null : component.factory();
            return executable == null ? Optional.empty() : Optional.of(new Factory(executable));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** A JDK method the host's own containers are written with. */
    static Factory method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return new Factory(owner.getMethod(name, parameters));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(owner.getName() + "." + name + " is gone", e);
        }
    }

    public Kind kind() {
        if (executable instanceof Constructor<?>) return Kind.CONSTRUCTOR;
        return Modifier.isStatic(executable.getModifiers()) ? Kind.STATIC : Kind.RECEIVER;
    }

    /** The method's name, or {@code ""} for a constructor, which is written by its class. */
    public String name() {
        return executable instanceof Method method ? method.getName() : "";
    }

    /** The class the call is written on: the constructed class, or the method's declaring class. */
    public Class<?> owner() {
        return executable.getDeclaringClass();
    }

    /** How many parts the call is written with, the receiver included; a varargs tail counts once. */
    public int parts() {
        return executable.getParameterCount() + (kind() == Kind.RECEIVER ? 1 : 0);
    }

    public boolean varargs() {
        return executable.isVarArgs();
    }

    /** Whether a call written with {@code written} parts can be this one. */
    public boolean fits(int written) {
        return varargs() ? written >= parts() - 1 : written == parts();
    }

    /**
     * Whether {@code call} is a call to this method: a static one on its owner, or an instance one on any
     * receiver. A static call written with no receiver, which a static import allows, counts only when
     * {@code bare}.
     */
    public boolean matches(MethodInvocation call, boolean bare) {
        if (kind() == Kind.CONSTRUCTOR || !call.getName().getIdentifier().equals(name())) return false;
        if (!fits(call.arguments().size() + (kind() == Kind.RECEIVER ? 1 : 0))) return false;
        Expression receiver = call.getExpression();
        if (kind() == Kind.RECEIVER) return receiver != null;
        if (receiver == null) return bare;
        return receiver instanceof Name written && names(written, owner());
    }

    /** Whether {@code creation} is {@code new Owner(…)} with a count this constructor takes. */
    public boolean matches(ClassInstanceCreation creation) {
        return kind() == Kind.CONSTRUCTOR && creation.getAnonymousClassDeclaration() == null
                && creation.getExpression() == null
                && names(creation.getType(), owner()) && fits(creation.arguments().size());
    }

    /** Whether a written name — {@code Map}, {@code java.util.Map}, {@code Flow.Limits} — names {@code type}. */
    public static boolean names(Name written, Class<?> type) {
        return SourceNames.refersTo(written, JavaNames.canonical(type));
    }

    /** Whether a written type — {@code Point}, {@code Box<>}, {@code geometry.Point} — names {@code type}. */
    public static boolean names(Type written, Class<?> type) {
        return switch (written) {
            case SimpleType simple -> names(simple.getName(), type);
            case ParameterizedType parameterized -> names(parameterized.getType(), type);
            case NameQualifiedType qualified -> type.getEnclosingClass() == null
                    ? JavaNames.canonical(type).equals(qualified.getQualifier().getFullyQualifiedName() + "."
                            + qualified.getName().getIdentifier())
                    : names(qualified.getQualifier(), type.getEnclosingClass())
                            && qualified.getName().getIdentifier().equals(type.getSimpleName());
            case QualifiedType qualified -> type.getEnclosingClass() != null
                    && names(qualified.getQualifier(), type.getEnclosingClass())
                    && qualified.getName().getIdentifier().equals(type.getSimpleName());
            case null, default -> false;
        };
    }
}
