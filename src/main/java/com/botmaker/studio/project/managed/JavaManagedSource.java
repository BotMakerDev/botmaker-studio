package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.JavaParameterSource;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code @Managed} methods out of <b>one</b> Java source, with no project, no filesystem and no host.
 *
 * <p><b>The same shape as {@code JavaParameterSource}, deliberately.</b> Pure, so the reading can be tested
 * over source text headlessly; syntax only, no bindings, so a bot whose dependencies do not resolve still
 * shows its plugin's values instead of an empty window. The form derivation, the parser configuration and
 * the annotation match are that class's — asked here rather than copied, because a second set of compiler
 * options is a second thing to keep in step.
 *
 * <p><b>What is different is the member and the body rule.</b> A parameter is a field whose initialiser is
 * the value. A managed value is a method whose <em>whole body</em> must be exactly one
 * {@code return <expression>;} for the expression to be rewritable — anything else is code the author wrote
 * on purpose, and it is kept, shown and never replaced. That is the same answer
 * {@code whyNotEditable} already gives a computed {@code @Param} initialiser, applied one level up.
 */
public final class JavaManagedSource {

    /** The annotation's simple name, which is how it is matched. Its package is not resolvable here. */
    static final String ANNOTATION = "Managed";

    /**
     * The annotation's fully qualified name, which is what an import or a qualified use spells. The
     * contract's since 2026-09-22; a use spelling plugin-basics' older one still matches by simple name.
     */
    public static final String ANNOTATION_FQN = "com.botmaker.plugin.api.managed.Managed";

    private JavaManagedSource() {}

    /** Every {@code @Managed} method in {@code source}, in the order they are written. */
    public static List<ManagedMethod> read(Path file, String source, ValueGrammar grammar) {
        return read(file, source, grammar, BotRecords.none());
    }

    /**
     * The same, told what the bot declares itself — a managed value may be typed with one of the bot's own
     * records exactly as a parameter may.
     */
    public static List<ManagedMethod> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records) {
        List<ManagedMethod> out = new ArrayList<>();
        JavaParameterSource.parse(source).accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                Annotation annotation = managedAnnotation(method);
                if (annotation == null) return false;
                String id = idOf(annotation);
                if (id.isEmpty()) return false;
                String className = JavaParameterSource.enclosingTypeName(method);
                if (className == null) return false;
                out.add(read(file, source, grammar, records, className, id, method));
                return false;
            }
        });
        return List.copyOf(out);
    }

    private static ManagedMethod read(Path file, String source, ValueGrammar grammar, BotRecords records,
                                      String className, String id, MethodDeclaration method) {
        ValueForm form = JavaParameterSource.formOf(grammar, method.getReturnType2(),
                method.getExtraDimensions(), records::qualifyDeclared);
        Expression returned = returnedExpression(method);
        String expression = returned == null ? "" : JavaParameterSource.text(source, returned);
        // The grammar declines a declared form by design — only the host's view of the bot's own source can
        // read one — so the second reader is asked for exactly that case and for no other.
        boolean readable = !expression.isEmpty() && (form instanceof ValueForm.Declared declared
                ? records.partsOf(declared, expression).isPresent()
                : grammar.valueOf(form, expression).isPresent());
        String note = whyNotEditable(grammar, method, form, records, readable, expression);
        return new ManagedMethod(file, className, method.getName().getIdentifier(), id, form, expression,
                note.isEmpty(), note);
    }

    /**
     * The expression a body of exactly one {@code return <expression>;} returns, or {@code null}.
     *
     * <p><b>Exactly one statement, and that is the whole readability rule.</b> A body with a local variable
     * in it is a value assembled by code the plugin did not write, and there is no expression to replace
     * without deleting what the author did. An abstract or interface method has no body at all and reads the
     * same way.
     */
    public static Expression returnedExpression(MethodDeclaration method) {
        if (method.getBody() == null) return null;
        List<?> statements = method.getBody().statements();
        if (statements.size() != 1) return null;
        return statements.getFirst() instanceof ReturnStatement returned ? returned.getExpression() : null;
    }

    /**
     * Why this value may not be rewritten, or {@code ""} when it may.
     *
     * <p>Each answer is a sentence a person can act on, for the reason the parameters window gives them: a
     * value shown as read-only with no reason is the failure this design exists to avoid. A method is never
     * <em>rejected</em> — it is still found by its id, still reports what it holds, and still says what to
     * change.
     */
    private static String whyNotEditable(ValueGrammar grammar, MethodDeclaration method, ValueForm form,
                                         BotRecords records, boolean readable, String expression) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers)) {
            return "not public: the bot can call it, the plugin's window cannot show it being changed";
        }
        if (!Modifier.isStatic(modifiers)) {
            return "not static: a plugin's value belongs to the bot, not to an instance of a class";
        }
        if (!method.parameters().isEmpty()) {
            return "takes parameters: a value is not computed from arguments";
        }
        if (form instanceof ValueForm.Declared declared) {
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) {
            return form instanceof ValueForm.Leaf
                    ? "no installed plugin declares " + unknown + " as a value type"
                    : "type argument " + unknown + " is not a known value type";
        }
        if (expression.isEmpty()) {
            return "written by hand: the body is not a single return, so there is no one expression in it "
                    + "to replace";
        }
        if (!readable) {
            return "written by hand as " + expression + ", which is kept rather than replaced";
        }
        return "";
    }

    /**
     * The {@code @Managed} annotation on this declaration, or {@code null}.
     *
     * <p>Takes a {@link BodyDeclaration} rather than a method because the annotation has two targets: a
     * method holds one value, and a type holds a set of them. {@code LockResolver} asks about both, and what
     * makes either one managed is stated here once.
     */
    public static Annotation managedAnnotation(BodyDeclaration declaration) {
        for (Object modifier : declaration.modifiers()) {
            if (!(modifier instanceof Annotation annotation)) continue;
            String name = annotation.getTypeName().getFullyQualifiedName();
            if (name.equals(ANNOTATION) || name.equals(ANNOTATION_FQN) || name.endsWith("." + ANNOTATION)) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * The id an annotation names, or {@code ""} for one that names nothing constant.
     *
     * <p>Only a string literal is read, the rule every annotation reader in this project follows:
     * {@code @Managed(SOME_CONSTANT)} compiles and means something a parser without bindings cannot know,
     * and a guessed id is a value edited under a name nobody wrote.
     */
    public static String idOf(Annotation annotation) {
        if (annotation instanceof SingleMemberAnnotation single) {
            return literal(single.getValue());
        }
        if (annotation instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                if (pair.getName().getIdentifier().equals("value")) return literal(pair.getValue());
            }
        }
        return "";
    }

    private static String literal(Expression expression) {
        return expression instanceof StringLiteral string ? string.getLiteralValue() : "";
    }
}
