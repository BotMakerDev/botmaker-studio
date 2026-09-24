package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.slot.TypeRef;
import com.botmaker.studio.plugin.grammar.JdkLiterals;
import com.botmaker.studio.types.ResolvedType;
import io.github.classgraph.ClassInfo;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;

import java.lang.reflect.Executable;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The contract's two views of what Studio resolved — a {@link TypeRef} for a slot's type and an
 * {@link Executable} for the call around it — and the one place a binding becomes either.
 *
 * <p><b>Names are binary names, compared whole.</b> A {@code TypeRef} built here answers
 * {@link TypeRef#is} by the erasure's binary name and {@link TypeRef#isSubtypeOf} by the binary names of
 * every supertype the binding reaches; an {@code Executable} is loaded on {@link PluginHost#classLoader()},
 * so the declaring class it names is the class the plugin's editor was compiled against. Nothing here
 * matches a name by how it ends. A type known only as a simple name — a declaration JDT could not resolve —
 * is {@linkplain TypeRef#unresolved unresolved}, and an editor keyed on a type is absent there.
 */
public final class HostTypes {

    private HostTypes() {}

    /** {@code type} as the contract sees it. */
    public static TypeRef of(ResolvedType type) {
        return switch (type) {
            case null -> TypeRef.unresolved("");
            case ResolvedType.Bound bound -> of(bound.binding());
            case ResolvedType.FromIndex indexed -> of(indexed.info());
            case ResolvedType.Primitive primitive -> named(primitive.kind().keyword(), primitive.simpleName());
            case ResolvedType.Named named -> named.isUnknown() ? TypeRef.unresolved(named.simpleName())
                    : named(named.qualifiedName(), named.simpleName());
        };
    }

    /** A binding's type, its supertypes read off the binding — so a bot's own class answers too. */
    public static TypeRef of(ITypeBinding binding) {
        if (binding == null || binding.isRecovered()) {
            return TypeRef.unresolved(binding == null ? "" : binding.getName());
        }
        ITypeBinding erased = binding.getErasure();
        Set<String> supertypes = new HashSet<>();
        Deque<ITypeBinding> pending = new ArrayDeque<>();
        pending.add(erased);
        while (!pending.isEmpty()) {
            ITypeBinding next = pending.removeFirst().getErasure();
            String name = className(next);
            if (name == null || !supertypes.add(name)) continue;
            if (next.getSuperclass() != null) pending.add(next.getSuperclass());
            pending.addAll(Arrays.asList(next.getInterfaces()));
        }
        if (!erased.isPrimitive()) supertypes.add(Object.class.getName());
        String name = className(erased);
        return name == null ? TypeRef.unresolved(erased.getName())
                : new Named(name, supertypes, erased.getName());
    }

    /** A library class from the index: ClassGraph lists every superclass and interface itself. */
    static TypeRef of(ClassInfo info) {
        Set<String> supertypes = new HashSet<>();
        supertypes.add(info.getName());
        info.getSuperclasses().forEach(each -> supertypes.add(each.getName()));
        info.getInterfaces().forEach(each -> supertypes.add(each.getName()));
        supertypes.add(Object.class.getName());
        return new Named(info.getName(), supertypes, info.getSimpleName());
    }

    /**
     * A type known by its binary name alone: loaded on the plugin loader for its supertypes when it can be,
     * and otherwise that one name — a bot's own class, say, which no plugin can name anyway.
     */
    public static TypeRef named(String binaryName, String displayName) {
        if (binaryName == null || binaryName.isEmpty()) return TypeRef.unresolved(displayName);
        Optional<Class<?>> known = JdkLiterals.named(binaryName).or(() -> load(binaryName));
        if (known.isPresent()) return TypeRef.of(known.get());
        // A bare "Duration" is a spelling, not a name: resolving it is what the binding was for.
        if (binaryName.indexOf('.') < 0) return TypeRef.unresolved(displayName);
        return new Named(binaryName, Set.of(binaryName, Object.class.getName()), displayName);
    }

    /**
     * The method or constructor {@code call} resolved to, loaded on the plugin loader — empty when the call
     * did not resolve or its class is not on that loader (a method of the bot itself). Matched by name and
     * erased parameter types, so an overload is the overload the source called.
     */
    public static Optional<Executable> executable(IMethodBinding call) {
        if (call == null || call.isRecovered()) return Optional.empty();
        IMethodBinding declared = call.getMethodDeclaration();
        ITypeBinding owner = declared.getDeclaringClass();
        String ownerName = owner == null ? null : className(owner.getErasure());
        if (ownerName == null) return Optional.empty();
        Optional<Class<?>> loaded = load(ownerName);
        if (loaded.isEmpty()) return Optional.empty();
        String[] parameters = Arrays.stream(declared.getParameterTypes())
                .map(each -> className(each.getErasure())).toArray(String[]::new);
        try {
            Executable[] candidates = declared.isConstructor()
                    ? loaded.get().getDeclaredConstructors() : loaded.get().getDeclaredMethods();
            for (Executable candidate : candidates) {
                if (!declared.isConstructor() && !candidate.getName().equals(declared.getName())) continue;
                String[] types = Arrays.stream(candidate.getParameterTypes()).map(Class::getName).toArray(String[]::new);
                if (Arrays.equals(types, parameters)) return Optional.of(candidate);
            }
        } catch (LinkageError e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * {@code type}'s name as {@link Class#getName()} spells it — {@code a.B$C}, {@code int},
     * {@code [Ljava.lang.String;} — or null for a type with none (a local or anonymous class, a type variable
     * left unerased).
     */
    static String className(ITypeBinding type) {
        if (type == null) return null;
        if (type.isPrimitive()) return type.getName();
        if (type.isArray()) {
            String element = descriptor(type.getElementType());
            return element == null ? null : "[".repeat(type.getDimensions()) + element;
        }
        return type.getBinaryName();
    }

    private static String descriptor(ITypeBinding element) {
        if (element.isPrimitive()) {
            return switch (element.getName()) {
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> null;
            };
        }
        String name = element.getErasure().getBinaryName();
        return name == null ? null : "L" + name + ";";
    }

    private static Optional<Class<?>> load(String binaryName) {
        try {
            return Optional.of(Class.forName(binaryName, false, PluginHost.classLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** A type the host resolved by name, with the names of everything it is a subtype of. */
    private record Named(String name, Set<String> supertypes, String displayName) implements TypeRef {

        @Override
        public boolean is(Class<?> type) {
            return type != null && name.equals(type.getName());
        }

        @Override
        public boolean isSubtypeOf(Class<?> type) {
            return type != null && supertypes.contains(type.getName());
        }

        @Override
        public boolean isResolved() {
            return true;
        }
    }
}
