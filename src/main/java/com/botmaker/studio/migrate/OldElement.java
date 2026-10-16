package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One element of the API a bot used to compile against: a method, a constructor, a field or a whole type, as
 * the old jar declares it. What a {@code @Migrate} rule names by its own signature, and what a broken use of
 * the bot points at.
 *
 * @param kind     what it is
 * @param owner    its declaring type's qualified name; a {@link Migrate.Kind#TYPE}'s own
 * @param name     its name; a constructor's is its owner's simple name, a type's its simple name
 * @param params   a method's or constructor's parameter types, each a qualified erasure; empty otherwise
 * @param instance whether it is reached through a receiver
 * @param type     what it gives: a method's return type, a field's type, a constructor's or type's owner
 * @param key      JDT's key for the declaration, which every use of it in the same parse shares: what
 *                 {@link com.botmaker.studio.nav.Usages#keyOf} answers for any of them
 */
public record OldElement(Migrate.Kind kind, String owner, String name, List<String> params, boolean instance,
                         String type, String key) {

    public OldElement {
        params = List.copyOf(params);
    }

    /**
     * The element {@code binding} declares, when it comes from a jar rather than the bot's own source — the
     * declaration, never a parameterised use, so every use shares one key.
     */
    static Optional<OldElement> of(IBinding binding) {
        switch (binding) {
            case IMethodBinding method -> {
                IMethodBinding declared = method.getMethodDeclaration();
                ITypeBinding owner = declared.getDeclaringClass();
                if (owner == null || owner.isFromSource() || declared.isRecovered()) return Optional.empty();
                List<String> params = new ArrayList<>();
                for (ITypeBinding param : declared.getParameterTypes()) params.add(erased(param));
                String ownerName = erased(owner);
                return Optional.of(declared.isConstructor()
                        ? new OldElement(Migrate.Kind.CONSTRUCTOR, ownerName, owner.getErasure().getName(), params,
                                false, ownerName, declared.getKey())
                        : new OldElement(Migrate.Kind.METHOD, ownerName, declared.getName(), params,
                                !Modifier.isStatic(declared.getModifiers()), erased(declared.getReturnType()),
                                declared.getKey()));
            }
            case IVariableBinding variable when variable.isField() -> {
                IVariableBinding declared = variable.getVariableDeclaration();
                ITypeBinding owner = declared.getDeclaringClass();
                if (owner == null || owner.isFromSource() || declared.isRecovered()) return Optional.empty();
                return Optional.of(new OldElement(Migrate.Kind.FIELD, erased(owner), declared.getName(), List.of(),
                        !Modifier.isStatic(declared.getModifiers()), erased(declared.getType()), declared.getKey()));
            }
            case ITypeBinding type -> {
                ITypeBinding declared = type.getErasure();
                if (declared.isPrimitive() || declared.isArray() || declared.isTypeVariable() || declared.isFromSource()
                        || declared.isRecovered() || declared.isAnonymous() || declared.isLocal()) {
                    return Optional.empty();
                }
                String name = erased(declared);
                return Optional.of(new OldElement(Migrate.Kind.TYPE, name, declared.getName(), List.of(), false, name,
                        declared.getKey()));
            }
            case null, default -> {
                return Optional.empty();
            }
        }
    }

    /** Whether its owner is the JDK's, which no plugin upgrade changes. */
    boolean jdk() {
        return owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jdk.");
    }

    /** {@code type}'s erasure as source qualifies it: {@code java.util.List}, {@code a.Outer.Inner}, {@code int[]}. */
    static String erased(ITypeBinding type) {
        return type == null ? "" : type.getErasure().getQualifiedName();
    }

    /** What a person reads it as: {@code GameScreen#marker(ImageTemplate)}, {@code State#OFF}, {@code Area}. */
    public String display() {
        String owner = simple(this.owner);
        return switch (kind) {
            case TYPE -> owner;
            case FIELD -> owner + "#" + name;
            case CONSTRUCTOR -> "new " + owner + "(" + simpleParams() + ")";
            case METHOD -> owner + "#" + name + "(" + simpleParams() + ")";
        };
    }

    private String simpleParams() {
        return String.join(", ", params.stream().map(OldElement::simple).toList());
    }

    /** {@code a.b.Outer.Inner} → {@code Inner}, {@code java.util.List[]} → {@code List[]}. */
    static String simple(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
