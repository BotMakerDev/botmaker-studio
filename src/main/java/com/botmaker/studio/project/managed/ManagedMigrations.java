package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.api.meta.Migrated;
import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.migrate.Template;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.SourceNode;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.project.source.ValueTypeResolver;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.YieldStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bot's author moving one type's values to another, through the {@code @Migrate} methods they keep in the
 * bot (2026-10-12, {@code docs/refactor/25-compatibility.md} §12).
 *
 * <p><b>Substituted, never run.</b> A {@code @Migrate(Old.class) static New m(A a, B b)} method's parameters are
 * {@code Old}'s parts, in its declaration's order. For each constant of an open set of {@code Old}, the value is
 * read through the grammar, taken apart by its declaration, each part written back as Java and put where its
 * parameter stands in the method's one {@code return}; the result is read as {@code New} and spelled by the
 * grammar. A parameter declared as another type than its part goes through the {@code @Migrate} method from the
 * part's type to the parameter's first; an enum source's method is a {@code switch} over the constant, whose arm
 * is taken. A part that only a wither writes is not a parameter, and is lost.
 *
 * <p><b>Copied, never moved.</b> Each result is written into the open set of {@code New} under the original's
 * name, marked {@link Migrated}; the original is never touched. A constant of that name already marked is
 * rewritten — a rerun — and one not marked is the author's and kept. Editing a value through its window removes
 * the mark ({@link #unmarked}).
 *
 * <p><b>Pure, over the bot's one parse</b>, like {@link ManagedSets}: a {@link Refactor.Outcome} out, compiled as
 * the whole bot before it is offered, plus what happened to each constant.
 */
public final class ManagedMigrations {

    private ManagedMigrations() {}

    /** One parameter of a {@code @Migrate} method: its name and the type it is declared as. */
    public record Param(String name, Type form) {}

    /**
     * One {@code @Migrate} method as written.
     *
     * @param source  the annotation's class, as a value's type
     * @param target  its return type
     * @param body    the expression its one {@code return} returns, as written; empty for an enum source
     * @param arms    an enum source's {@code switch}: each constant's arm, as written, {@code ""} for
     *                {@code default}; empty otherwise
     * @param problem why the method cannot be used, or {@code null}
     */
    public record Method(Path file, String className, String name, Type source, Type target, List<Param> params,
                         String body, Map<String, String> arms, String problem) {

        /** Whether this method maps {@code from} to {@code to}, by class name. */
        public boolean maps(Class<?> from, Class<?> to) {
            return named(source, from) && named(target, to);
        }

        /** What a person reads it as: {@code Migrations.picture}. */
        public String qualified() {
            return className + "." + name;
        }
    }

    /** What a migration did, constant by constant, each as the bot writes it ({@code Pictures.ORE}). */
    public record Report(List<String> written, List<String> kept, List<String> failed) {}

    /** A migration's plan, or why there is none, and what it did to each constant. */
    public record Run(Refactor.Outcome outcome, Report report) {}

    // --- reading the author's methods ----------------------------------------------------------------------

    /** Every {@code @Migrate} method in the bot, in file order; a method that cannot be used says why. */
    public static List<Method> methods(BotIndex index, ValueGrammar grammar) {
        return index.read(units -> {
            List<Method> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                String source = index.sources().get(file);
                unit.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration method) {
                        Annotation mark = BotAnnotation.MIGRATE.on(method);
                        if (mark != null && wholeType(mark)) out.add(method(file, source, method, mark, grammar));
                        return false;
                    }
                });
            });
            return twinsRefused(out);
        });
    }

    /** {@code methods} with each one mapping the same pair as another refused: which one counts is not clear. */
    private static List<Method> twinsRefused(List<Method> methods) {
        List<Method> out = new ArrayList<>();
        for (Method method : methods) {
            Optional<Method> twin = methods.stream().filter(other -> other != method
                    && other.source() instanceof Class<?> from && other.target() instanceof Class<?> to
                    && method.maps(from, to)).findFirst();
            out.add(twin.isEmpty() || method.problem() != null ? method
                    : new Method(method.file(), method.className(), method.name(), method.source(), method.target(),
                            method.params(), method.body(), method.arms(),
                            "maps the same types as " + twin.get().qualified() + "; keep one"));
        }
        return List.copyOf(out);
    }

    private static Method method(Path file, String source, MethodDeclaration method, Annotation mark,
                                 ValueGrammar grammar) {
        String className = JavaParameterSource.enclosingTypeName(method);
        String name = method.getName().getIdentifier();
        Type from = sourceOf(mark, grammar);
        Type to = ValueTypeResolver.of(grammar, method.getReturnType2(), method.getExtraDimensions(), Set.of());
        List<Param> params = new ArrayList<>();
        for (Object each : method.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) each;
            params.add(new Param(parameter.getName().getIdentifier(),
                    ValueTypeResolver.of(grammar, parameter.getType(), parameter.getExtraDimensions(), Set.of())));
        }
        String problem = null;
        Expression returned = returned(method);
        Map<String, String> arms = new LinkedHashMap<>();
        if (!Modifier.isStatic(method.getModifiers())) problem = "is not static";
        else if (!(from instanceof Class<?>)) problem = "names no source type a plugin declares";
        else if (!(to instanceof Class<?>)) problem = "returns no type a plugin declares";
        else if (returned == null) problem = "is not one return of a value";
        else if (((Class<?>) from).isEnum()) {
            if (params.size() != 1 || !named(params.getFirst().form(), (Class<?>) from)) {
                problem = "maps an enum, so it takes the one constant";
            } else if (!(returned instanceof SwitchExpression choice)
                    || !(choice.getExpression() instanceof SimpleName over)
                    || !over.getIdentifier().equals(params.getFirst().name())
                    || !arms(source, choice, arms)) {
                problem = "maps an enum, so it returns a switch over the constant with one value per arm";
            }
        }
        return new Method(file, className, name, from, to, List.copyOf(params),
                returned == null || !arms.isEmpty() ? "" : JavaParameterSource.text(source, returned),
                Map.copyOf(arms), problem);
    }

    /**
     * Whether {@code mark} is a whole type's rule, {@code kind = Migrate.Kind.TYPE}: the only kind whose values are
     * copied; a member's rule is the engine's ({@code migrate/}, 2026-10-16).
     */
    private static boolean wholeType(Annotation mark) {
        if (!(mark instanceof NormalAnnotation normal)) return false;
        for (Object each : normal.values()) {
            MemberValuePair pair = (MemberValuePair) each;
            if (pair.getName().getIdentifier().equals("kind")) {
                String constant = switch (pair.getValue()) {
                    case QualifiedName qualified -> qualified.getName().getIdentifier();
                    case SimpleName simple -> simple.getIdentifier();
                    default -> "";
                };
                return Migrate.Kind.TYPE.name().equals(constant);
            }
        }
        return false;
    }

    /** The class literal {@code @Migrate(value = Old.class, kind = TYPE)} names, as a value's type. */
    private static Type sourceOf(Annotation mark, ValueGrammar grammar) {
        Expression value = null;
        if (mark instanceof SingleMemberAnnotation single) value = single.getValue();
        if (mark instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                if (pair.getName().getIdentifier().equals("value")) value = pair.getValue();
            }
        }
        return value instanceof TypeLiteral literal
                ? ValueTypeResolver.of(grammar, literal.getType(), 0, Set.of()) : ValueTypes.NONE;
    }

    /** The expression of the method's one statement when it is a {@code return} of one, else {@code null}. */
    private static Expression returned(MethodDeclaration method) {
        if (method.getBody() == null || method.getBody().statements().size() != 1) return null;
        return method.getBody().statements().getFirst() instanceof ReturnStatement ret ? ret.getExpression() : null;
    }

    /** Fills {@code arms} from an arrow {@code switch}, one expression per arm; false for any other shape. */
    private static boolean arms(String source, SwitchExpression choice, Map<String, String> arms) {
        List<String> labels = new ArrayList<>();
        for (Object each : choice.statements()) {
            Statement statement = (Statement) each;
            if (statement instanceof SwitchCase label) {
                if (!label.isSwitchLabeledRule()) return false;
                if (label.isDefault()) labels.add("");
                for (Object expression : label.expressions()) {
                    if (!(expression instanceof SimpleName constant)) return false;
                    labels.add(constant.getIdentifier());
                }
                continue;
            }
            Expression arm = switch (statement) {
                case ExpressionStatement expression -> expression.getExpression();
                case YieldStatement yield -> yield.getExpression();
                default -> null;
            };
            if (arm == null || labels.isEmpty()) return false;
            String written = JavaParameterSource.text(source, arm);
            for (String label : labels) arms.put(label, written);
            labels.clear();
        }
        return !arms.isEmpty() && labels.isEmpty();
    }

    // --- running one --------------------------------------------------------------------------------------

    /**
     * Every constant of {@code from}'s open sets, written into {@code to}'s open set through the bot's
     * {@code @Migrate} method from one to the other. Refused when there is no such method or it cannot be used,
     * when no single open set of {@code to} has a class in the bot, or when the result would not compile.
     */
    public static Run migrate(BotIndex index, ValueGrammar grammar, List<ManagedValue<?>> declared, Class<?> from,
                              Class<?> to) {
        List<Method> methods = methods(index, grammar);
        Report none = new Report(List.of(), List.of(), List.of());
        Optional<Method> top = find(methods, from, to);
        if (top.isEmpty()) {
            return new Run(new Refactor.Refused("No @Migrate method maps " + from.getSimpleName() + " to "
                    + to.getSimpleName() + "."), none);
        }
        if (top.get().problem() != null) {
            return new Run(new Refactor.Refused(top.get().qualified() + " " + top.get().problem() + "."), none);
        }
        List<ManagedValue<?>> targets = declared.stream()
                .filter(value -> holdsValues(value) && named(value.type(), to))
                .toList();
        if (targets.size() != 1) {
            return new Run(new Refactor.Refused(targets.isEmpty()
                    ? "No plugin declares an open set of " + to.getSimpleName() + " to migrate into."
                    : "Several open sets hold " + to.getSimpleName() + ", so where the values go is not clear."), none);
        }
        List<ManagedSets.Holder> holders = ManagedSets.holders(index, targets.getFirst().id());
        if (holders.size() != 1) {
            String mark = ManagedIds.spelled(targets.getFirst().id(), declared);
            return new Run(new Refactor.Refused(holders.isEmpty()
                    ? "The project has no class marked " + mark + " to write the migrated values in."
                    : "Several classes are marked " + mark + ", so where the values go is not clear."), none);
        }
        ManagedSets.Holder holder = holders.getFirst();

        Map<String, JavaValue> results = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (ManagedValue<?> set : declared) {
            if (!holdsValues(set) || !named(set.type(), from)) continue;
            for (ManagedSets.Member member : ManagedSets.members(index, set.id(), grammar)) {
                if (member.initializer() == null) {
                    failed.add(member.qualified() + ": it has no initialiser to read");
                    continue;
                }
                List<String> why = new ArrayList<>();
                Optional<JavaValue> written = grammar.valueOf(member.form(), member.initializer())
                        .flatMap(value -> converted(value, from, to, methods, grammar, why, 0))
                        .flatMap(value -> grammar.spell(to, value));
                if (written.isPresent() && !results.containsKey(member.name())) {
                    results.put(member.name(), written.get());
                } else {
                    failed.add(member.qualified() + (written.isPresent() ? ": another constant has its name"
                            : why.isEmpty() ? ": its value could not be read" : ": " + why.getFirst()));
                }
            }
        }

        String source = index.sources().get(holder.file());
        List<String> written = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        String migrated = results.isEmpty() ? source : written(source, holder.className(), to, results, written, kept);
        Report report = new Report(List.copyOf(written), List.copyOf(kept), List.copyOf(failed));
        if (migrated == null) {
            return new Run(new Refactor.Refused(holder.className() + " could not be written: its class or the type "
                    + to.getSimpleName() + " could not be spelled there."), report);
        }
        if (migrated.equals(source)) {
            return new Run(new Refactor.Refused(results.isEmpty()
                    ? "No value of " + from.getSimpleName() + " could be migrated."
                    : "Nothing to write: every name is already the author's."), report);
        }
        String what = "Migrated " + written.size() + " " + from.getSimpleName() + " to " + to.getSimpleName();
        return new Run(Refactor.checked(index, new Refactor.Planned(what, Map.of(holder.file(), migrated)), what),
                report);
    }

    private static Optional<Method> find(List<Method> methods, Class<?> from, Class<?> to) {
        return methods.stream().filter(m -> m.maps(from, to)).findFirst();
    }

    /**
     * {@code value}, an {@code from}, as the {@code to} the author's method makes of it — or empty, with the
     * reason added to {@code why}. Nested migrations recurse; a cycle stops at a depth no real chain reaches.
     */
    private static Optional<Object> converted(Object value, Class<?> from, Class<?> to, List<Method> methods,
                                              ValueGrammar grammar, List<String> why, int depth) {
        if (depth > 16) return fail(why, "the @Migrate methods call each other in a circle");
        Optional<Method> found = find(methods, from, to);
        if (found.isEmpty()) {
            return fail(why, "no @Migrate method maps " + from.getSimpleName() + " to " + to.getSimpleName());
        }
        Method method = found.get();
        if (method.problem() != null) return fail(why, method.qualified() + " " + method.problem());

        if (from.isEnum()) {
            String arm = method.arms().getOrDefault(((Enum<?>) value).name(), method.arms().get(""));
            if (arm == null) return fail(why, method.qualified() + " has no arm for " + ((Enum<?>) value).name());
            Optional<JavaValue> constant = grammar.initializer(from, value);
            if (constant.isEmpty()) return fail(why, ((Enum<?>) value).name() + " could not be written");
            return result(arm, Map.of(method.params().getFirst().name(), constant.get().node()), to, grammar, method,
                    why);
        }

        Optional<ComponentType<?>> component = grammar.componentOf(value);
        if (component.isEmpty()) return fail(why, from.getSimpleName() + " is not written as a call");
        List<Object> parts;
        List<Class<?>> types;
        try {
            parts = components(component.get(), value);
            types = component.get().componentTypes();
        } catch (RuntimeException | LinkageError e) {
            return fail(why, from.getSimpleName() + " could not be taken apart: " + e.getMessage());
        }
        if (parts.size() != method.params().size() || types.size() != parts.size()) {
            return fail(why, method.qualified() + " takes " + method.params().size() + " parameters, and "
                    + from.getSimpleName() + " has " + parts.size() + " parts");
        }
        Map<String, Expression> bound = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            Param param = method.params().get(i);
            Class<?> part = types.get(i);
            Object written = parts.get(i);
            Class<?> as = part;
            if (!named(param.form(), part)) {
                if (!(param.form() instanceof Class<?> wanted)) {
                    return fail(why, param.name() + " is of no type a plugin declares");
                }
                Optional<Object> nested = converted(written, part, wanted, methods, grammar, why, depth + 1);
                if (nested.isEmpty()) return Optional.empty();
                written = nested.get();
                as = wanted;
            }
            Optional<JavaValue> java = grammar.initializer(as, written);
            if (java.isEmpty()) return fail(why, "part " + param.name() + " could not be written");
            bound.put(param.name(), java.get().node());
        }
        return result(method.body(), bound, to, grammar, method, why);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> components(ComponentType<?> component, Object value) {
        return ((ComponentType<Object>) component).components(value);
    }

    /** {@code body} with each parameter replaced by its part, read as {@code to}. */
    private static Optional<Object> result(String body, Map<String, Expression> bound, Class<?> to,
                                           ValueGrammar grammar, Method method, List<String> why) {
        Optional<Expression> substituted = substituted(body, bound);
        if (substituted.isEmpty()) {
            return fail(why, method.qualified() + "'s body could not be filled in: it declares a name one of its"
                    + " parameters has, or uses a parameter where only a name fits");
        }
        Optional<Object> read = grammar.valueOf(to, new SourceNode(substituted.get(), null));
        return read.isPresent() ? read
                : fail(why, method.qualified() + " does not return a " + to.getSimpleName() + " the grammar reads");
    }

    /**
     * {@code body} with every use of a parameter replaced by its part ({@link Template}, the rewrite of the bot's
     * other code fills a rule's body the same way), parsed. A body that declares a name a parameter has — a
     * lambda's own {@code file} — is empty: which is which is not clear; so is one whose result does not parse.
     */
    static Optional<Expression> substituted(String body, Map<String, Expression> bound) {
        Map<String, Template.Part> parts = new LinkedHashMap<>();
        bound.forEach((name, part) -> parts.put(name, Template.Part.of(part, part.toString())));
        return Template.filled(body, false, parts).map(JavaExpressions::parse);
    }

    // --- writing -----------------------------------------------------------------------------------------

    /**
     * {@code source} with each result written into {@code className}: a new {@code @Migrated public static final}
     * constant, or the initialiser of one still marked; a constant of that name not marked is the author's and
     * is reported in {@code kept}. Unchanged when every name is kept; {@code null}, with both lists emptied, when
     * the class is not in it or the type cannot be spelled there.
     */
    static String written(String source, String className, Class<?> type, Map<String, JavaValue> results,
                          List<String> written, List<String> kept) {
        CompilationUnit unit = BotParser.syntax(source);
        if (!(ManagedSets.topLevel(unit, className) instanceof TypeDeclaration holder)) return null;
        AST ast = unit.getAST();
        ASTRewrite rewrite = ASTRewrite.create(ast);
        ListRewrite body = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        Set<String> imports = new LinkedHashSet<>();
        FieldDeclaration[] fields = holder.getFields();
        ASTNode last = fields.length == 0 ? null : fields[fields.length - 1];
        boolean marked = false;
        for (Map.Entry<String, JavaValue> result : results.entrySet()) {
            String name = result.getKey();
            String qualified = className + "." + name;
            FieldDeclaration existing = field(holder, name);
            if (existing != null) {
                VariableDeclarationFragment fragment = fragment(existing, name);
                if (BotAnnotation.MIGRATED.on(existing) == null || existing.fragments().size() != 1) {
                    kept.add(qualified);
                    continue;
                }
                rewrite.set(fragment, VariableDeclarationFragment.INITIALIZER_PROPERTY,
                        result.getValue().copyInto(ast), null);
            } else {
                Optional<org.eclipse.jdt.core.dom.Type> declared = ValueTypes.node(ast, type, imports);
                if (declared.isEmpty()) {
                    written.clear();
                    kept.clear();
                    return null;
                }
                VariableDeclarationFragment fragment = ast.newVariableDeclarationFragment();
                fragment.setName(ast.newSimpleName(name));
                fragment.setInitializer(result.getValue().copyInto(ast));
                FieldDeclaration field = ast.newFieldDeclaration(fragment);
                field.setType(declared.get());
                MarkerAnnotation mark = ast.newMarkerAnnotation();
                mark.setTypeName(ast.newSimpleName(Migrated.class.getSimpleName()));
                @SuppressWarnings("unchecked")
                List<Object> modifiers = field.modifiers();
                modifiers.add(mark);
                modifiers.addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL));
                if (last == null) body.insertFirst(field, null);
                else body.insertAfter(field, last, null);
                last = field;
                marked = true;
            }
            imports.addAll(result.getValue().imports());
            written.add(qualified);
        }
        if (written.isEmpty()) return source;
        if (marked) imports.add(Migrated.class.getName());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    /**
     * {@code source} with the {@link Migrated} mark taken off the constant {@code className.name}: what an edit of
     * a migrated value does, so a rerun leaves the edit alone. Unchanged when it carries no mark.
     */
    public static String unmarked(String source, String className, String name) {
        // Nearly no file carries the mark: spare every edit a second parse.
        if (source == null || !source.contains(Migrated.class.getSimpleName())) return source;
        CompilationUnit unit = BotParser.syntax(source);
        if (!(ManagedSets.topLevel(unit, className) instanceof TypeDeclaration holder)) return source;
        FieldDeclaration field = field(holder, name);
        Annotation mark = field == null ? null : BotAnnotation.MIGRATED.on(field);
        if (mark == null) return source;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.getListRewrite(field, FieldDeclaration.MODIFIERS2_PROPERTY).remove(mark, null);
        return SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
    }

    private static FieldDeclaration field(TypeDeclaration holder, String name) {
        for (FieldDeclaration field : holder.getFields()) if (fragment(field, name) != null) return field;
        return null;
    }

    private static VariableDeclarationFragment fragment(FieldDeclaration field, String name) {
        for (Object each : field.fragments()) {
            VariableDeclarationFragment fragment = (VariableDeclarationFragment) each;
            if (fragment.getName().getIdentifier().equals(name)) return fragment;
        }
        return null;
    }

    /** Whether {@code value} is an open set whose members hold values — constants or nested classes, not enums. */
    static boolean holdsValues(ManagedValue<?> value) {
        return value instanceof ManagedOpenSet.OfConstants<?> || value instanceof ManagedClasses<?>;
    }

    /** Whether {@code type} is {@code cls}, by name, a primitive and its box alike. */
    static boolean named(Type type, Class<?> cls) {
        return type instanceof Class<?> declared && cls != null
                && JavaNames.boxed(JavaNames.canonical(declared)).equals(JavaNames.boxed(JavaNames.canonical(cls)));
    }

    private static <T> Optional<T> fail(List<String> why, String reason) {
        why.add(reason);
        return Optional.empty();
    }
}
