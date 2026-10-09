package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.managed.ManagedMarker;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.grammar.SourceNames;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;

import java.util.Collection;
import java.util.Optional;

/**
 * Which of a plugin's values a declaration in the bot's source is marked as: a plugin's own
 * {@link ManagedMarker} annotation — {@code @SdkValue(SdkValue.Id.FLOW)}.
 *
 * <p><b>The id is the contract's</b>, {@link ManagedValue#idOf}: the enum's binary name and the constant, so a
 * binding answers {@code getBinaryName()} — {@code SdkValue$Id}, never the qualified {@code SdkValue.Id}.
 *
 * <p><b>A binding first, the plugins' declarations second.</b> A unit parsed against the classpath says that
 * an annotation's class is marked, and which constant it holds. A unit parsed without one cannot tell a
 * marker from any other annotation, so it is matched against the markers the bound plugins declare
 * ({@code known}), through the unit's imports ({@link SourceNames#refersTo}) — never a suffix match. The
 * constant is then the last name the source wrote: the marker's {@code value()} is its one enum, so javac
 * already refuses any other.
 */
public final class ManagedIds {

    private static final String MARKER = ManagedMarker.class.getCanonicalName();

    private ManagedIds() {}

    /**
     * A value's names, taken apart once: what a holder's annotation is written as, and what a source is
     * matched against.
     *
     * @param id           {@link ManagedValue#id()}, {@code com.botmaker.sdk.api.bot.SdkValue$Id.FLOW}
     * @param markerBinary the annotation's binary name, {@code com.botmaker.sdk.api.bot.SdkValue}
     * @param enumBinary   the enum's, {@code com.botmaker.sdk.api.bot.SdkValue$Id}
     * @param constant     {@code FLOW}
     */
    public record Typed(String id, String markerBinary, String enumBinary, String constant) {

        /** {@code value}'s names. */
        public static Typed of(ManagedValue<?> value) {
            int dot = value.id().lastIndexOf('.');
            return new Typed(value.id(), value.marker(), value.id().substring(0, dot), value.id().substring(dot + 1));
        }

        /**
         * The names an id says itself, {@code pkg.Marker$Enum.CONSTANT}, for one no bound plugin declares; empty
         * for a string of any other shape.
         */
        static Optional<Typed> parse(String id) {
            int dollar = id == null ? -1 : id.lastIndexOf('$');
            int dot = id == null ? -1 : id.lastIndexOf('.');
            if (dollar <= 0 || dot <= dollar) return Optional.empty();
            return Optional.of(new Typed(id, id.substring(0, dollar), id.substring(0, dot), id.substring(dot + 1)));
        }

        /** The annotation as source names it, {@code com.botmaker.sdk.api.bot.SdkValue}. */
        public String markerCanonical() {
            return markerBinary.replace('$', '.');
        }

        /** {@code SdkValue}. */
        public String markerSimple() {
            return simple(markerCanonical());
        }

        /** {@code Id}. */
        public String enumSimple() {
            return enumBinary.substring(enumBinary.lastIndexOf('$') + 1);
        }

        private static String simple(String canonical) {
            return canonical.substring(canonical.lastIndexOf('.') + 1);
        }
    }

    /**
     * The mark a bot writes for {@code id}, for a sentence: {@code @SdkValue(SdkValue.Id.FLOW)}, from the
     * plugin in {@code known} that declares it, else from the id's own shape, else the id as it is.
     */
    public static String spelled(String id, Collection<ManagedValue<?>> known) {
        if (known != null) {
            for (ManagedValue<?> value : known) {
                if (value != null && value.id().equals(id)) return spelled(Typed.of(value));
            }
        }
        return Typed.parse(id).map(ManagedIds::spelled).orElse(String.valueOf(id));
    }

    private static String spelled(Typed typed) {
        return "@" + typed.markerSimple() + "(" + typed.markerSimple() + "." + typed.enumSimple() + "."
                + typed.constant() + ")";
    }

    /** The annotation marking {@code declaration} as a managed value, or {@code null}. */
    public static Annotation on(BodyDeclaration declaration, Collection<ManagedValue<?>> known) {
        if (declaration == null) return null;
        for (Object modifier : declaration.modifiers()) {
            if (modifier instanceof Annotation annotation && marks(annotation, known)) return annotation;
        }
        return null;
    }

    /** The id {@code annotation} names, or {@code ""} when it is no marker or names nothing constant. */
    public static String idOf(Annotation annotation, Collection<ManagedValue<?>> known) {
        return annotation == null ? "" : typedIdOf(annotation, known);
    }

    /** Whether {@code annotation} marks a managed value. */
    public static boolean marks(Annotation annotation, Collection<ManagedValue<?>> known) {
        return !idOf(annotation, known).isEmpty();
    }

    /** Whether the class {@code annotationType} is an annotation a plugin marked {@link ManagedMarker}. */
    public static boolean isMarker(ITypeBinding annotationType) {
        if (annotationType == null || annotationType.isRecovered()) return false;
        for (IAnnotationBinding meta : annotationType.getAnnotations()) {
            ITypeBinding type = meta.getAnnotationType();
            if (type != null && MARKER.equals(type.getQualifiedName())) return true;
        }
        return false;
    }

    /** The id {@code annotation} holds, or {@code ""} when it is no plugin's marker. */
    private static String typedIdOf(Annotation annotation, Collection<ManagedValue<?>> known) {
        IAnnotationBinding binding = annotation.resolveAnnotationBinding();
        ITypeBinding type = binding == null ? null : binding.getAnnotationType();
        if (isMarker(type)) {
            for (IMemberValuePairBinding pair : binding.getDeclaredMemberValuePairs()) {
                if ("value".equals(pair.getName()) && pair.getValue() instanceof IVariableBinding constant
                        && constant.isEnumConstant() && constant.getDeclaringClass() != null) {
                    return constant.getDeclaringClass().getBinaryName() + "." + constant.getName();
                }
            }
            return "";
        }
        // No binding, or one whose classpath lacks the contract's meta-annotation: the declarations decide.
        if (known == null) return "";
        String constant = constantName(valueOf(annotation));
        if (constant == null) return "";
        for (ManagedValue<?> value : known) {
            if (value == null) continue;
            Typed typed = Typed.of(value);
            if (SourceNames.refersTo(annotation.getTypeName(), typed.markerCanonical())) {
                return typed.enumBinary() + "." + constant;
            }
        }
        return "";
    }

    private static Expression valueOf(Annotation annotation) {
        if (annotation instanceof SingleMemberAnnotation single) return single.getValue();
        if (annotation instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                if (pair.getName().getIdentifier().equals("value")) return pair.getValue();
            }
        }
        return null;
    }

    /** The constant a name ends in — {@code FLOW} of {@code SdkValue.Id.FLOW} — or null for anything else. */
    private static String constantName(Expression value) {
        return switch (value) {
            case QualifiedName qualified -> qualified.getName().getIdentifier();
            case SimpleName simple -> simple.getIdentifier();
            case null, default -> null;
        };
    }
}
