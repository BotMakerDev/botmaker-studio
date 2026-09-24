package com.botmaker.studio.project.managed;

import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.source.ValueTypeResolver;
import com.botmaker.studio.project.params.BotRecords;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code @Managed} methods out of <b>one</b> Java source, with no project, no filesystem and no host.
 *
 * <p><b>The same shape as {@code JavaParameterSource}, deliberately.</b> Pure, so the reading can be tested
 * over source text headlessly; parsed by the caller's {@link BotParser}, and {@code @Managed} identified by
 * class ({@link BotAnnotation#MANAGED}), so a bot whose dependencies do not resolve still shows its plugin's
 * values instead of an empty window. The form derivation, the parser configuration and
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
        return read(file, source, grammar, records, BotParser.SYNTAX);
    }

    /** The same, parsed by {@code parser} — the project's, so {@code @Managed} and its id are resolved. */
    public static List<ManagedMethod> read(Path file, String source, ValueGrammar grammar,
                                           BotRecords records, BotParser parser) {
        List<ManagedMethod> out = new ArrayList<>();
        parser.parse(file, source).accept(new ASTVisitor() {
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
        Type form = ValueTypeResolver.of(grammar, method.getReturnType2(), method.getExtraDimensions(),
                records.declaredNames());
        Expression returned = returnedExpression(method);
        String expression = returned == null ? "" : JavaParameterSource.text(source, returned);
        // The grammar declines a declared form by design — only the host's view of the bot's own source can
        // read one — so the second reader is asked for exactly that case and for no other.
        boolean readable = !expression.isEmpty() && (form instanceof ValueTypes.BotClass declared
                ? records.partsOf(declared, expression).isPresent()
                : grammar.valueOf(form, new SourceNode(returned, source)).isPresent());
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
    private static String whyNotEditable(ValueGrammar grammar, MethodDeclaration method, Type form,
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
        if (form instanceof ValueTypes.BotClass declared) {
            String why = records.whyNotEditable(declared);
            if (why != null) return why;
        }
        String unknown = grammar.firstUnknown(form);
        if (unknown != null) return JavaParameterSource.unknownReason(form, unknown);
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
        return BotAnnotation.MANAGED.on(declaration);
    }

    /**
     * The id an annotation names, or {@code ""} for one that names nothing constant.
     *
     * <p>A constant the classpath resolves is read as its value; without bindings only a string literal is,
     * because a guessed id is a value edited under a name nobody wrote.
     */
    public static String idOf(Annotation annotation) {
        return BotAnnotation.MANAGED.members(annotation).get("value") instanceof String id ? id : "";
    }
}
