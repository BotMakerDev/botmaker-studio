package com.botmaker.studio.project.models;

import com.botmaker.plugin.api.value.ValueCatalog;
import com.botmaker.plugin.api.value.ValueContainer;
import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.studio.project.params.JavaParameterSource;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A generated model file read back into the record instance it was written from.
 *
 * <p><b>Why this is decidable at all.</b> Reading arbitrary Java into a value is not a thing a host can do;
 * reading <em>this</em> Java is, because {@link ModelWriter}'s output is this reader's whole input domain.
 * Every node is one of five shapes, written one way, fully qualified. Anything else in the file — an
 * expression a person typed, a constant folded by hand, a call to something else — is not a reading this
 * class will invent, and it answers empty. That is the same discipline {@code project/params/} keeps for a
 * user's own field, with the difference that here the host wrote the file, so an empty answer means the file
 * was edited rather than that a codec was missing.
 *
 * <h2>The AST, not a split</h2>
 *
 * <p>The arguments of a call are taken from a real parse, exactly as {@code BotRecords.partsOf} takes a
 * record's: a comma count says three arguments for {@code Map.entry("a,b", x)} and is wrong. The file is
 * generated, so a comma inside a string is the case that occurs, not a hypothetical one.
 *
 * <h2>Pure</h2>
 *
 * <p>Source text and a class in, a value out. No project, no filesystem, no JavaFX —
 * {@link ModelGrammar} states the reason, and this class is the third of the trio that has to hold it for
 * the round trip to be testable as one law.
 */
public final class ModelReader {

    private ModelReader() {
    }

    /**
     * The model held by a generated compilation unit, or empty.
     *
     * <p>Empty covers every way this can fail to be a reading: a file that declares no such class, a class
     * with no {@link ModelWriter#FIELD} constant, a constant whose initialiser this grammar did not write,
     * and a record the grammar refuses. A project that never had a model is the ordinary case of the first
     * one, which is why nothing here throws.
     */
    public static <T extends Record> Optional<T> read(String source, String simpleName, Class<T> type,
                                                      ValueCatalog catalog) {
        if (source == null || type == null || !ModelGrammar.isLegal(type, catalog)) return Optional.empty();
        return initializerIn(source, simpleName)
                .flatMap(written -> valueOf(new ModelForm.Declared(type), written, catalog))
                .filter(type::isInstance)
                .map(type::cast);
    }

    /**
     * The source of the model constant's initialiser, as written.
     *
     * <p>The class is found by name and the field by {@link ModelWriter#FIELD}, because that pair is what
     * the writer promises. A file holding two constants, or one holding a differently named class, is not
     * something the writer produces, and guessing which one was meant is how a reader starts accepting
     * things the writer cannot regenerate.
     */
    public static Optional<String> initializerIn(String source, String simpleName) {
        if (source == null || simpleName == null || simpleName.isBlank()) return Optional.empty();
        CompilationUnit unit = JavaParameterSource.parse(source);
        List<String> found = new ArrayList<>(1);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration declaration) {
                if (!simpleName.equals(declaration.getName().getIdentifier())) return true;
                for (FieldDeclaration field : declaration.getFields()) {
                    for (Object each : field.fragments()) {
                        VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
                        if (!ModelWriter.FIELD.equals(fragment.getName().getIdentifier())) continue;
                        Expression initializer = fragment.getInitializer();
                        if (initializer != null) found.add(text(source, initializer));
                    }
                }
                return true;
            }
        });
        return found.stream().filter(written -> !written.isBlank()).findFirst();
    }

    /**
     * One initialiser as the value it was written from, or empty.
     *
     * <p>The five cases of {@link ModelWriter#initializer} read backwards, one for one. Empty propagates for
     * the reason it propagates there: a model half read is a model with a component the editor never chose,
     * and the file as it stands is a better answer than that.
     */
    public static Optional<Object> valueOf(ModelForm form, String initializer, ValueCatalog catalog) {
        if (form == null || initializer == null) return Optional.empty();
        Expression expression = JavaParameterSource.expression(initializer.strip());
        return expression == null ? Optional.empty() : valueOf(form, expression, initializer.strip(), catalog);
    }

    private static Optional<Object> valueOf(ModelForm form, Expression expression, String source,
                                            ValueCatalog catalog) {
        Expression node = unwrap(expression);
        return switch (form) {
            case ModelForm.Builtin builtin -> builtin(builtin.type(), node, source);
            case ModelForm.Constant constant -> constant(constant.type(), node);
            case ModelForm.Catalogued catalogued -> catalog == null ? Optional.empty()
                    : catalog.valueOf(ValueForm.of(catalogued.type()), text(source, node));
            case ModelForm.Composite composite -> composite(composite, node, source, catalog);
            case ModelForm.Declared declared -> declared(declared, node, source, catalog);
        };
    }

    private static Optional<Object> composite(ModelForm.Composite form, Expression node, String source,
                                              ValueCatalog catalog) {
        if (!(node instanceof MethodInvocation call)) return Optional.empty();
        ValueContainer<?> container = form.container();
        if (!container.factorySource().equals(calleeOf(call))) return Optional.empty();

        List<?> arguments = call.arguments();
        Optional<List<ModelForm>> forms = ModelGrammar.partForms(form, arguments.size());
        if (forms.isEmpty()) return Optional.empty();

        Optional<List<Object>> parts = parts(forms.get(), arguments, source, catalog);
        return parts.map(container::build);
    }

    private static Optional<Object> declared(ModelForm.Declared form, Expression node, String source,
                                             ValueCatalog catalog) {
        if (!(node instanceof ClassInstanceCreation creation)) return Optional.empty();
        if (!named(creation.getType()).equals(form.type().getCanonicalName())) return Optional.empty();

        Optional<List<ModelGrammar.Component>> components =
                ModelGrammar.componentsOf(form.type(), catalog);
        if (components.isEmpty()) return Optional.empty();
        List<?> arguments = creation.arguments();
        if (arguments.size() != components.get().size()) return Optional.empty();

        Optional<List<Object>> parts = parts(
                components.get().stream().map(ModelGrammar.Component::form).toList(), arguments, source,
                catalog);
        if (parts.isEmpty()) return Optional.empty();
        return construct(form.type(), parts.get());
    }

    private static Optional<List<Object>> parts(List<ModelForm> forms, List<?> arguments, String source,
                                                ValueCatalog catalog) {
        if (forms.size() != arguments.size()) return Optional.empty();
        List<Object> parts = new ArrayList<>(forms.size());
        for (int i = 0; i < forms.size(); i++) {
            Optional<Object> part =
                    valueOf(forms.get(i), (Expression) arguments.get(i), source, catalog);
            if (part.isEmpty()) return Optional.empty();
            parts.add(part.get());
        }
        return Optional.of(parts);
    }

    /**
     * The record built through its canonical constructor.
     *
     * <p>Positional and canonical, because that is what the writer wrote. A record whose compact constructor
     * refuses the parts throws, and the throw is caught here: a model that was legal when written and is
     * rejected now is a plugin whose rules changed, which is an empty read and not a crashed editor.
     */
    private static Optional<Object> construct(Class<?> type, List<Object> parts) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) types[i] = components[i].getType();
        try {
            Constructor<?> canonical = type.getDeclaredConstructor(types);
            return Optional.ofNullable(canonical.newInstance(parts.toArray()));
        } catch (ReflectiveOperationException | IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    /** {@code E.CONSTANT} read as the constant, or empty for a name that is not one of this enum's. */
    private static Optional<Object> constant(Class<?> type, Expression node) {
        if (!(node instanceof Name name)) return Optional.empty();
        String written = name.getFullyQualifiedName();
        String simple = written.substring(written.lastIndexOf('.') + 1);
        for (Object constant : type.getEnumConstants()) {
            if (constant instanceof Enum<?> value && value.name().equals(simple)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    /**
     * A {@code String}, a primitive or a box read back from its literal.
     *
     * <p>The target type decides, not the literal's own shape: {@code 42} is an {@code Integer} in a
     * component declared {@code int} and a {@code Long} in one declared {@code long}, and a record's
     * canonical constructor takes exactly one of those. The suffixes and casts the writer added are stripped
     * here, which is the other half of the reason they are written at all.
     */
    private static Optional<Object> builtin(Class<?> type, Expression node, String source) {
        if (type == String.class) {
            return node instanceof StringLiteral literal ? Optional.of(literal.getLiteralValue())
                    : Optional.empty();
        }
        if (type == char.class || type == Character.class) {
            return node instanceof CharacterLiteral literal ? Optional.of(literal.charValue())
                    : Optional.empty();
        }
        if (type == boolean.class || type == Boolean.class) {
            return node instanceof BooleanLiteral literal ? Optional.of(literal.booleanValue())
                    : Optional.empty();
        }
        return number(type, numberText(node, source));
    }

    /** The digits of a number, with the writer's cast and the parentheses of a negation taken off. */
    private static String numberText(Expression node, String source) {
        Expression inner = node instanceof CastExpression cast ? unwrap(cast.getExpression()) : node;
        return text(source, inner).strip();
    }

    private static Optional<Object> number(Class<?> type, String written) {
        if (written.isEmpty()) return Optional.empty();
        try {
            if (type == byte.class || type == Byte.class) return Optional.of(Byte.parseByte(written));
            if (type == short.class || type == Short.class) return Optional.of(Short.parseShort(written));
            if (type == int.class || type == Integer.class) return Optional.of(Integer.parseInt(written));
            if (type == long.class || type == Long.class) {
                return Optional.of(Long.parseLong(strip(written, 'L')));
            }
            if (type == float.class || type == Float.class) {
                Optional<Double> named = special(written);
                return Optional.of(named.isPresent() ? named.get().floatValue()
                        : Float.parseFloat(strip(written, 'F')));
            }
            if (type == double.class || type == Double.class) {
                Optional<Double> named = special(written);
                return Optional.of(named.isPresent() ? named.get()
                        : Double.parseDouble(strip(written, 'D')));
            }
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** {@code Float.NaN} and the two infinities, which are constants rather than literals in any Java. */
    private static Optional<Double> special(String written) {
        String name = written.substring(written.lastIndexOf('.') + 1);
        return switch (name) {
            case "NaN" -> Optional.of(Double.NaN);
            case "POSITIVE_INFINITY" -> Optional.of(Double.POSITIVE_INFINITY);
            case "NEGATIVE_INFINITY" -> Optional.of(Double.NEGATIVE_INFINITY);
            default -> Optional.empty();
        };
    }

    private static String strip(String written, char suffix) {
        char last = written.charAt(written.length() - 1);
        return Character.toUpperCase(last) == suffix ? written.substring(0, written.length() - 1) : written;
    }

    /** {@code java.util.Map.ofEntries} out of the call that writes it — receiver and method name. */
    private static String calleeOf(MethodInvocation call) {
        Expression receiver = call.getExpression();
        String owner = receiver == null ? "" : receiver.toString().strip();
        return owner.isEmpty() ? call.getName().getIdentifier()
                : owner + "." + call.getName().getIdentifier();
    }

    /** The created type's canonical name with any diamond dropped — {@code Box<>} and {@code Box} are one. */
    private static String named(Type type) {
        String written = type == null ? "" : type.toString().strip();
        int angle = written.indexOf('<');
        return angle < 0 ? written : written.substring(0, angle).strip();
    }

    /**
     * Brackets taken off, and nothing else.
     *
     * <p>A negation is deliberately left standing: {@code -5} is the whole literal as far as a number
     * parser is concerned, and unwrapping it would hand {@code 5} to a component that holds {@code -5}.
     */
    private static Expression unwrap(Expression node) {
        Expression inner = node;
        while (inner instanceof ParenthesizedExpression parenthesized) {
            inner = parenthesized.getExpression();
        }
        return inner;
    }

    /** One node's own source, taken by offset so an author's spelling survives the reading. */
    private static String text(String source, ASTNode node) {
        int start = node.getStartPosition();
        int end = start + node.getLength();
        if (start < 0 || end > source.length() || end < start) return "";
        return source.substring(start, end);
    }
}
