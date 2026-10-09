package com.botmaker.studio.services.overlay;

import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.MethodInvocation;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A call a plugin names by reference, with argument values, written as a statement the host inserts — what an
 * overlay tool's {@code insert} asks for.
 *
 * <p>The values are spelled by the grammar, so the Java is the canvas's; a value equal to a managed
 * constant of the bot is written as that constant ({@code Pictures.COLLECT}). The plugin writes no Java.
 */
public final class OverlayCalls {

    /**
     * One statement as a tree, and the classes it names by simple name. {@link #source()} is for showing it;
     * what is inserted is {@link #node()}.
     */
    public record Statement(org.eclipse.jdt.core.dom.Statement node, List<String> imports, String source) {

        public Statement {
            imports = List.copyOf(imports);
        }
    }

    private OverlayCalls() {}

    /**
     * {@code Owner.method(arguments…);}. Refused, with the sentence to show, for a call that is not a static
     * method, a count of arguments the method does not take, or a value neither the bot's constants nor the
     * grammar can write.
     *
     * @throws IllegalArgumentException with the sentence saying why it cannot be written
     */
    public static Statement call(Executable call, List<?> arguments, ValueGrammar grammar,
                                 ManagedConstants.Lookup constants) {
        if (!(call instanceof Method method) || !Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(describe(call) + " is not a static method, so it cannot be "
                    + "inserted as a statement.");
        }
        if (method.getParameterCount() != arguments.size()) {
            throw new IllegalArgumentException(describe(call) + " takes " + method.getParameterCount()
                    + " argument(s), not " + arguments.size() + ".");
        }
        List<JavaValue> written = new ArrayList<>();
        for (Object argument : arguments) {
            Optional<JavaValue> spelled = argument == null ? Optional.empty()
                    : constants.spell(argument).or(() -> grammar.spellAny(argument));
            if (spelled.isEmpty()) {
                throw new IllegalArgumentException("Studio cannot write " + (argument == null ? "null"
                        : "a " + argument.getClass().getSimpleName()) + " as an argument of " + describe(call)
                        + ".");
            }
            written.add(spelled.get());
        }
        return of(method, written);
    }

    /** {@code Owner.method(arguments…);}, each argument's tree copied in and its imports carried. */
    static Statement of(Method method, List<JavaValue> arguments) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        Set<String> imports = new LinkedHashSet<>();
        Class<?> owner = method.getDeclaringClass();
        String importName = JavaNames.importName(owner);
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newName(JavaNames.simple(owner)));
        call.setName(ast.newSimpleName(method.getName()));
        @SuppressWarnings("unchecked")
        List<Expression> list = call.arguments();
        for (JavaValue argument : arguments) {
            list.add(argument.copyInto(ast));
            imports.addAll(argument.imports());
        }
        if (!importName.isEmpty()) imports.add(importName);
        ExpressionStatement statement = ast.newExpressionStatement(call);
        return new Statement(statement, List.copyOf(imports), SourceFormatter.statement(statement.toString()));
    }

    private static String describe(Executable call) {
        return call == null ? "nothing" : call.getDeclaringClass().getSimpleName() + "." + call.getName();
    }
}
