package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.value.ComponentType;

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
     * What today's string reader matches before the bracket: {@code java.time.Duration.ofMillis}, or the
     * class name for a constructor. The typed reader replaces the string reader and deletes this.
     */
    String callSource() {
        String owner = JavaNames.canonical(owner());
        return kind() == Kind.CONSTRUCTOR ? owner : owner + "." + name();
    }
}
