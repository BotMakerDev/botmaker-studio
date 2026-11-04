package com.botmaker.studio.project.params;

import com.botmaker.plugin.host.grammar.ValuesPruner;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This machine's values of the bot's {@code @Param} schemas (2026-11-04): each class in a {@code values} package,
 * {@code values/<Schema>Values.java}, and what each of its methods returns.
 *
 * <p>A values class is never committed ({@code ProjectVcs} excludes {@code values/}), and it overrides only the
 * methods this machine has a value for; a method it leaves alone answers the schema's {@code default}.
 *
 * @param bySchema each values class, by the simple name of the schema it implements
 */
public record ValuesClasses(Map<String, Held> bySchema) {

    /** No values class: every schema method shows its default. */
    public static final ValuesClasses NONE = new ValuesClasses(Map.of());

    /** The package segment a values class sits in. */
    public static final String PACKAGE = ValuesPruner.PACKAGE;

    /** The suffix of a values class's name: {@code RunChoices} → {@code RunChoicesValues}. */
    public static final String SUFFIX = ValuesPruner.SUFFIX;

    public ValuesClasses {
        bySchema = Map.copyOf(bySchema);
    }

    /**
     * One values class.
     *
     * @param file    where it is
     * @param source  its text, which each override's expression is read against
     * @param returns each overriding method's returned expression, by method name
     */
    public record Held(Path file, String source, Map<String, Expression> returns) {
        public Held {
            returns = Map.copyOf(returns);
        }
    }

    /**
     * Every values class among {@code units}: a top-level class in a package whose last segment is
     * {@value #PACKAGE}, named after a schema it implements plus {@value #SUFFIX}.
     */
    public static ValuesClasses over(Map<Path, CompilationUnit> units, Map<Path, String> sources) {
        Map<String, Held> out = new LinkedHashMap<>();
        units.forEach((file, unit) -> {
            if (!inValuesPackage(unit)) return;
            for (Object each : unit.types()) {
                if (!(each instanceof TypeDeclaration type) || type.isInterface()) continue;
                String name = type.getName().getIdentifier();
                if (!name.endsWith(SUFFIX)) continue;
                String schema = name.substring(0, name.length() - SUFFIX.length());
                if (implemented(type).stream().noneMatch(schema::equals)) continue;
                out.putIfAbsent(schema, new Held(file, sources.getOrDefault(file, ""), returns(type)));
            }
        });
        return new ValuesClasses(out);
    }

    /** The values class of the schema called {@code schema}, if this machine has one. */
    public Optional<Held> of(String schema) {
        return Optional.ofNullable(bySchema.get(schema));
    }

    /** Whether {@code unit} is in a {@code …values} package. */
    static boolean inValuesPackage(CompilationUnit unit) {
        if (unit.getPackage() == null) return false;
        String name = unit.getPackage().getName().getFullyQualifiedName();
        return name.equals(PACKAGE) || name.endsWith("." + PACKAGE);
    }

    /** The simple names of the interfaces {@code type} implements. */
    private static List<String> implemented(TypeDeclaration type) {
        return ((List<?>) type.superInterfaceTypes()).stream().map(Type.class::cast)
                .map(ValuesClasses::simpleName).toList();
    }

    private static String simpleName(Type type) {
        String written = type.toString();
        int generic = written.indexOf('<');
        if (generic >= 0) written = written.substring(0, generic);
        return written.substring(written.lastIndexOf('.') + 1);
    }

    /** Each method of {@code type} whose body is one {@code return <expr>;}, by name. */
    private static Map<String, Expression> returns(TypeDeclaration type) {
        Map<String, Expression> out = new LinkedHashMap<>();
        type.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                returned(method).ifPresent(value -> out.put(method.getName().getIdentifier(), value));
                return false;
            }

            @Override
            public boolean visit(TypeDeclaration nested) {
                return nested == type;
            }
        });
        return out;
    }

    /** The expression a method's body is exactly {@code return <expr>;} of, when it is no parameter's and no more. */
    static Optional<Expression> returned(MethodDeclaration method) {
        Block body = method.getBody();
        if (body == null || !method.parameters().isEmpty() || body.statements().size() != 1) return Optional.empty();
        return body.statements().getFirst() instanceof ReturnStatement ret && ret.getExpression() != null
                ? Optional.of(ret.getExpression()) : Optional.empty();
    }
}
