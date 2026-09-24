package com.botmaker.studio.project.source;

import com.botmaker.plugin.api.managed.Managed;
import com.botmaker.plugin.api.params.Param;
import com.botmaker.studio.plugin.grammar.JdkLiterals;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The annotations the host reads off a bot's own source, identified by class — never by a name that ends
 * the right way.
 *
 * <p><b>Why a closed set.</b> Until 2026-09-24 each reader carried its annotation as two strings, a simple
 * name and a fully qualified one, and matched a use with {@code endsWith("." + simple)}: any {@code Param}
 * from any package was the contract's, and three copies of the match drifted independently. The identity
 * here is the contract's own {@link Class}, and the one extra spelling each constant accepts is the exact
 * name the annotation had in {@code botmaker-plugin-basics} before 2026-09-22, which a bot on an SDK before
 * 2.0.0 still imports. Studio cannot link that class — it must not depend on a plugin — so it is the one
 * name written down, and it is written whole.
 *
 * <p><b>A binding first.</b> A unit parsed against the project's classpath ({@link BotParser}) says which
 * class an annotation is; that answer is final. A unit parsed without one, or an annotation whose class the
 * classpath does not provide, is resolved the way javac would through the unit's imports
 * ({@link SourceNames#refersTo}).
 */
public enum BotAnnotation {

    PARAM(Param.class, "com.botmaker.plugin.basics.params.Param"),
    MANAGED(Managed.class, "com.botmaker.plugin.basics.managed.Managed");

    private final Class<? extends java.lang.annotation.Annotation> type;
    private final Set<String> names;

    BotAnnotation(Class<? extends java.lang.annotation.Annotation> type, String before) {
        this.type = type;
        this.names = Set.of(type.getCanonicalName(), before);
    }

    /** The contract's annotation class. */
    public Class<? extends java.lang.annotation.Annotation> type() {
        return type;
    }

    /** The annotation of this kind among {@code declaration}'s modifiers, or {@code null}. */
    public Annotation on(BodyDeclaration declaration) {
        return declaration == null ? null : on(declaration.modifiers());
    }

    /** The annotation of this kind in a modifier list, or {@code null}. */
    public Annotation on(List<?> modifiers) {
        for (Object modifier : modifiers) {
            if (modifier instanceof Annotation annotation && marks(annotation)) return annotation;
        }
        return null;
    }

    /** Whether any modifier in {@code modifiers} is this annotation. */
    public boolean isOn(List<IExtendedModifier> modifiers) {
        return on(modifiers) != null;
    }

    /** Whether {@code annotation} is a use of this annotation. */
    public boolean marks(Annotation annotation) {
        ITypeBinding resolved = resolved(annotation);
        if (resolved != null) return names.contains(resolved.getQualifiedName());
        for (String name : names) if (SourceNames.refersTo(annotation.getTypeName(), name)) return true;
        return false;
    }

    /**
     * The annotation's members — a {@code String} or a {@code Double} for a single value, a
     * {@code List<String>} for an array — with what could not be read left out.
     *
     * <p>From the binding where there is one, so {@code category = SOME_CONSTANT} reads as the constant's
     * value. Without one, only what the source spells as a constant: a string or number literal, and a
     * constant this annotation itself declares ({@code Param.PUBLIC}), read off the contract's class.
     */
    public Map<String, Object> members(Annotation annotation) {
        IAnnotationBinding binding = annotation.resolveAnnotationBinding();
        if (resolved(annotation) != null) return members(binding);
        Map<String, Object> out = new LinkedHashMap<>();
        if (annotation instanceof SingleMemberAnnotation single) {
            constant(single.getValue()).ifPresent(v -> out.put("value", v));
        } else if (annotation instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                String name = pair.getName().getIdentifier();
                if (pair.getValue() instanceof ArrayInitializer array) {
                    List<String> items = new ArrayList<>();
                    for (Object element : array.expressions()) {
                        constant((Expression) element).filter(String.class::isInstance)
                                .ifPresent(v -> items.add((String) v));
                    }
                    out.put(name, List.copyOf(items));
                } else {
                    constant(pair.getValue()).ifPresent(v -> out.put(name, v));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> members(IAnnotationBinding binding) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (IMemberValuePairBinding pair : binding.getDeclaredMemberValuePairs()) {
            Object value = pair.getValue();
            if (value instanceof Object[] array) {
                List<String> items = new ArrayList<>();
                for (Object item : array) if (item instanceof String text) items.add(text);
                out.put(pair.getName(), List.copyOf(items));
            } else if (value instanceof String || value instanceof Number) {
                out.put(pair.getName(), normal(value));
            }
        }
        return out;
    }

    /** A constant the source spells: a string or number literal, or one of this annotation's own fields. */
    private Optional<Object> constant(Expression expression) {
        return switch (expression) {
            case StringLiteral literal -> Optional.of(literal.getLiteralValue());
            case QualifiedName qualified when names.stream()
                    .anyMatch(name -> SourceNames.refersTo(qualified.getQualifier(), name)) ->
                    ownConstant(qualified.getName().getIdentifier());
            case null -> Optional.empty();
            default -> JdkLiterals.readAny(expression).filter(Number.class::isInstance).map(BotAnnotation::normal);
        };
    }

    /** A {@code static final} field this annotation declares, by reflection on the contract's class. */
    private Optional<Object> ownConstant(String name) {
        try {
            Field field = type.getField(name);
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())) {
                return Optional.empty();
            }
            return Optional.ofNullable(normal(field.get(null)));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return Optional.empty();
        }
    }

    private static Object normal(Object value) {
        return value instanceof Number number ? (Object) number.doubleValue() : value;
    }

    /** The annotation's class as a binding says it, or {@code null} when there is no real one. */
    private static ITypeBinding resolved(Annotation annotation) {
        IAnnotationBinding binding = annotation.resolveAnnotationBinding();
        ITypeBinding type = binding == null ? null : binding.getAnnotationType();
        return type == null || type.isRecovered() ? null : type;
    }
}
