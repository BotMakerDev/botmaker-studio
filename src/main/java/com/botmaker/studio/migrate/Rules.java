package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.project.source.BotAnnotation;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MemberValuePair;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeLiteral;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bot's {@code @Migrate} rules, read off its source and resolved against the API it used to compile with
 * (2026-10-16).
 *
 * <p><b>Named by signature, never by text.</b> A rule's own name is the old member's, its parameter types pick
 * the overload and a first parameter marked {@link Migrate.Self} the receiver; nothing is matched by a string
 * the author typed but {@link Migrate#name()}, which stands in for an owner the new API no longer has. A rule is
 * read in the bot as it compiles now, and its owner and element are looked up in the same sources parsed against
 * the old jars: that is where the element still exists.
 *
 * <p><b>Refused, never guessed.</b> A rule with neither or both of {@code value} and {@code name}, one whose
 * signature matches nothing in the old API, and two naming the same element each carry their {@link
 * Rule#problem()} and are applied nowhere.
 */
final class Rules {

    /** The package of the one extra source the old parse carries, naming each owner the bot can no longer name. */
    static final String PROBE_PACKAGE = "botmaker_migrate_probe";

    private Rules() {}

    // --- reading, as the bot compiles now ---------------------------------------------------------------------

    /** Every {@code @Migrate} method in {@code now}, in file order, its {@link Rule#element()} not yet known. */
    static List<Rule> declared(BotIndex now) {
        return now.read(units -> {
            List<Rule> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                String source = now.sources().get(file);
                if (source == null || !source.contains(Migrate.class.getSimpleName())) return;
                unit.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodDeclaration method) {
                        Annotation mark = BotAnnotation.MIGRATE.on(method);
                        if (mark != null) out.add(read(file, source, method, mark));
                        return false;
                    }
                });
            });
            return List.copyOf(out);
        });
    }

    private static Rule read(Path file, String source, MethodDeclaration method, Annotation mark) {
        Map<String, Expression> members = members(mark);
        Expression value = members.get("value");
        Expression named = members.get("name");
        Migrate.Kind kind = kindOf(members.get("kind"));
        String owner = "";
        String gone = null;
        if (value instanceof TypeLiteral literal) {
            ITypeBinding type = literal.getType().resolveBinding();
            owner = type == null || type.isRecovered() ? "" : OldElement.erased(type);
            if (owner.isEmpty()) gone = literal.getType().toString();
        } else if (named instanceof StringLiteral literal) {
            owner = literal.getLiteralValue().strip().replace('$', '.');
        }

        List<String> params = new ArrayList<>();
        List<String> types = new ArrayList<>();
        boolean self = false;
        String misplacedSelf = null;
        for (int i = 0; i < method.parameters().size(); i++) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) method.parameters().get(i);
            params.add(parameter.getName().getIdentifier());
            types.add(typeOf(parameter.getType(), parameter.getExtraDimensions() + (parameter.isVarargs() ? 1 : 0)));
            if (BotAnnotation.MIGRATE_SELF.on(parameter.modifiers()) != null) {
                if (i == 0) self = true;
                else misplacedSelf = parameter.getName().getIdentifier();
            }
        }

        boolean statement = method.getReturnType2() instanceof PrimitiveType primitive
                && primitive.getPrimitiveTypeCode() == PrimitiveType.VOID;
        ASTNode body = bodyOf(method, statement);
        Set<String> imports = new LinkedHashSet<>();
        Set<String> staticImports = new LinkedHashSet<>();
        String className = JavaParameterSource.enclosingTypeName(method);
        String ownCall = body == null ? null : imported(body, enclosingType(method), imports, staticImports);
        ReviewMarks.Mark review = ReviewMarks.markOn(method);

        String problem = null;
        if (value == null && named == null) problem = "names no old element: give @Migrate a class or a name";
        else if (value != null && named != null) problem = "gives both a class and a name; keep one";
        else if (value != null && !(value instanceof TypeLiteral)) problem = "names its owner with no class literal";
        else if (named != null && !(named instanceof StringLiteral)) problem = "names its owner with no string";
        else if (owner.isEmpty() && named != null) problem = "names an empty owner";
        else if (gone != null) problem = "names " + gone + ".class, which the new API no longer has; write"
                + " @Migrate(name = \"<its binary name>\") instead";
        else if (!Modifier.isStatic(method.getModifiers())) problem = "is not static";
        else if (body == null) problem = statement ? "is not one statement" : "is not one return of a value";
        else if (misplacedSelf != null) problem = "marks " + misplacedSelf + " @Migrate.Self, and only the first"
                + " parameter can be the receiver";
        else if (self && (kind == Migrate.Kind.CONSTRUCTOR || kind == Migrate.Kind.TYPE)) {
            problem = "has a receiver, and a " + kind.name().toLowerCase() + " has none";
        } else if (kind == Migrate.Kind.FIELD && params.size() != (self ? 1 : 0)) {
            problem = "takes parameters, and a field rule takes none but its receiver";
        } else if (ownCall != null) {
            problem = "calls " + ownCall + " without its class; write " + className + "." + ownCall;
        }
        String returns = statement ? "void" : typeOf(method.getReturnType2(), method.getExtraDimensions());
        return new Rule(file, className, method.getName().getIdentifier(), method.getStartPosition(), kind, owner,
                params, types, self, body == null ? "" : JavaParameterSource.text(source, body), statement, returns,
                imports, staticImports, review != null && !review.done(), null, problem);
    }

    /** The annotation's members, by name; a single member is {@code value}. */
    private static Map<String, Expression> members(Annotation mark) {
        Map<String, Expression> out = new LinkedHashMap<>();
        if (mark instanceof SingleMemberAnnotation single) out.put("value", single.getValue());
        if (mark instanceof NormalAnnotation normal) {
            for (Object each : normal.values()) {
                MemberValuePair pair = (MemberValuePair) each;
                out.put(pair.getName().getIdentifier(), pair.getValue());
            }
        }
        return out;
    }

    /** {@code Migrate.Kind.TYPE}, a statically imported {@code TYPE}, or the default. */
    private static Migrate.Kind kindOf(Expression written) {
        String constant = switch (written) {
            case QualifiedName qualified -> qualified.getName().getIdentifier();
            case SimpleName simple -> simple.getIdentifier();
            case null, default -> null;
        };
        for (Migrate.Kind kind : Migrate.Kind.values()) if (kind.name().equals(constant)) return kind;
        return Migrate.Kind.METHOD;
    }

    /** {@code type}'s erasure, qualified where it resolves and as written where it does not. */
    private static String typeOf(Type type, int dimensions) {
        ITypeBinding binding = type == null ? null : type.resolveBinding();
        String base = binding == null || binding.isRecovered() ? String.valueOf(type) : OldElement.erased(binding);
        return base + "[]".repeat(dimensions);
    }

    /** The one statement a {@code void} rule is, or the expression a valued rule's one {@code return} returns. */
    private static ASTNode bodyOf(MethodDeclaration method, boolean statement) {
        if (method.getBody() == null || method.getBody().statements().size() != 1) return null;
        Statement only = (Statement) method.getBody().statements().getFirst();
        if (statement) return only instanceof ReturnStatement ? null : only;
        return only instanceof ReturnStatement ret ? ret.getExpression() : null;
    }

    private static ITypeBinding enclosingType(MethodDeclaration method) {
        for (ASTNode at = method.getParent(); at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration type) return type.resolveBinding();
        }
        return null;
    }

    /**
     * Fills {@code imports} with every type {@code body} names by its simple name, and {@code staticImports} with
     * every static member it names bare — what a file the body is written into must import. Answers the first
     * member of the rule's own class the body names bare, which no other file can reach so: null when none.
     */
    private static String imported(ASTNode body, ITypeBinding own, Set<String> imports, Set<String> staticImports) {
        String[] ownCall = {null};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.getLocationInParent() == QualifiedName.NAME_PROPERTY
                        || name.getLocationInParent() == FieldAccess.NAME_PROPERTY) {
                    return false;
                }
                IBinding binding = name.resolveBinding();
                if (binding instanceof ITypeBinding type) {
                    ITypeBinding declared = type.getErasure();
                    if (!declared.isPrimitive() && !declared.isTypeVariable() && !declared.isLocal()
                            && !declared.isAnonymous() && !declared.isRecovered()) {
                        imports.add(declared.getQualifiedName());
                    }
                } else if (bare(name) && isStatic(binding)) {
                    ITypeBinding owner = binding instanceof IMethodBinding method ? method.getDeclaringClass()
                            : ((IVariableBinding) binding).getDeclaringClass();
                    if (owner != null && own != null && owner.getErasure().isEqualTo(own.getErasure())) {
                        if (ownCall[0] == null) ownCall[0] = name.getIdentifier();
                    } else if (owner != null) {
                        staticImports.add(owner.getErasure().getQualifiedName() + "." + name.getIdentifier());
                    }
                }
                return false;
            }
        });
        return ownCall[0];
    }

    /** A name with nothing before it: a call with no receiver, or a variable read by its simple name. */
    private static boolean bare(SimpleName name) {
        if (name.getLocationInParent() == MethodInvocation.NAME_PROPERTY) {
            return ((MethodInvocation) name.getParent()).getExpression() == null;
        }
        return !(name.getParent() instanceof Name);
    }

    private static boolean isStatic(IBinding binding) {
        return (binding instanceof IMethodBinding || binding instanceof IVariableBinding variable && variable.isField())
                && Modifier.isStatic(binding.getModifiers());
    }

    // --- the probe -----------------------------------------------------------------------------------------------

    /**
     * The one source the old parse adds: a field of each type a rule names by {@link Migrate#name()}, so the
     * owner the bot can no longer name resolves there. Empty when no rule names one.
     */
    static Map<Path, String> probe(List<Rule> rules, Path root) {
        Set<String> owners = new LinkedHashSet<>();
        for (Rule rule : rules) if (!rule.owner().isEmpty()) owners.add(rule.owner());
        if (owners.isEmpty()) return Map.of();
        StringBuilder text = new StringBuilder("package " + PROBE_PACKAGE + ";\n\nfinal class Probe {\n");
        int i = 0;
        for (String owner : owners) text.append("    ").append(owner).append(" t").append(i++).append(";\n");
        text.append("}\n");
        return Map.of(probePath(root), text.toString());
    }

    static Path probePath(Path root) {
        Path base = root == null ? Path.of("").toAbsolutePath() : root;
        return base.resolve(PROBE_PACKAGE).resolve("Probe.java").toAbsolutePath().normalize();
    }

    // --- resolving, against the old API ------------------------------------------------------------------------

    /**
     * {@code rules}, each resolved to the element of {@code old} its signature names, or refused. Two that name
     * the same element are both refused.
     */
    static List<Rule> resolved(List<Rule> rules, BotIndex old, Path probe) {
        Map<String, ITypeBinding> owners = old.read(units -> owners(units.get(probe)));
        Map<String, String> oldTypes = new LinkedHashMap<>();
        for (Rule rule : rules) {
            if (rule.kind() == Migrate.Kind.TYPE && rule.problem() == null) oldTypes.put(rule.returns(), rule.owner());
        }
        List<Rule> out = old.read(units -> {
            List<Rule> each = new ArrayList<>();
            for (Rule rule : rules) {
                if (rule.problem() != null) {
                    each.add(rule);
                    continue;
                }
                ITypeBinding owner = owners.get(rule.owner());
                if (owner == null) owner = literalOwner(units.get(rule.file()), rule.start());
                each.add(owner == null
                        ? rule.refused(rule.owner().isEmpty()
                                ? "names an owner neither API has"
                                : "names " + OldElement.simple(rule.owner()) + ", which the old API does not have")
                        : resolved(rule, owner, oldTypes));
            }
            return each;
        });
        return twinsRefused(out);
    }

    /** Each probe field's type, by the qualified name the probe wrote. */
    private static Map<String, ITypeBinding> owners(CompilationUnit probe) {
        Map<String, ITypeBinding> out = new LinkedHashMap<>();
        if (probe == null) return out;
        for (Object type : probe.types()) {
            if (!(type instanceof TypeDeclaration declaration)) continue;
            for (FieldDeclaration field : declaration.getFields()) {
                ITypeBinding binding = field.getType().resolveBinding();
                if (binding != null && !binding.isRecovered()) out.put(field.getType().toString(), binding);
            }
        }
        return out;
    }

    /** The class literal of the rule declared at {@code start}, as the old parse resolves it. */
    private static ITypeBinding literalOwner(CompilationUnit unit, int start) {
        if (unit == null) return null;
        ITypeBinding[] found = {null};
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getStartPosition() != start) return true;
                Annotation mark = BotAnnotation.MIGRATE.on(method);
                if (mark != null && members(mark).get("value") instanceof TypeLiteral literal) {
                    ITypeBinding type = literal.getType().resolveBinding();
                    if (type != null && !type.isRecovered()) found[0] = type;
                }
                return false;
            }
        });
        return found[0];
    }

    private static Rule resolved(Rule rule, ITypeBinding owner, Map<String, String> oldTypes) {
        String ownerName = OldElement.simple(OldElement.erased(owner));
        if (rule.kind() == Migrate.Kind.TYPE) {
            return OldElement.of(owner).map(rule::resolved)
                    .orElseGet(() -> rule.refused("names " + ownerName + ", which is no type of a library"));
        }
        List<OldElement> matches = new ArrayList<>();
        List<String> near = new ArrayList<>();
        boolean field = rule.kind() == Migrate.Kind.FIELD;
        boolean constructor = rule.kind() == Migrate.Kind.CONSTRUCTOR;
        List<ITypeBinding> hierarchy = hierarchy(owner);
        for (ITypeBinding type : hierarchy) {
            if (field) {
                for (IVariableBinding candidate : type.getDeclaredFields()) {
                    if (!candidate.getName().equals(rule.method())) continue;
                    OldElement.of(candidate).ifPresent(element -> {
                        if (element.instance() == rule.self()) matches.add(element);
                        else near.add(element.display() + (element.instance() ? ", an instance field" : ", static"));
                    });
                }
                continue;
            }
            for (IMethodBinding candidate : type.getDeclaredMethods()) {
                if (candidate.isConstructor() != constructor) continue;
                if (!constructor && !candidate.getName().equals(rule.method())) continue;
                if (constructor && type != hierarchy.getFirst()) continue;
                OldElement.of(candidate).ifPresent(element -> {
                    if (element.instance() == rule.self() && sameParams(element.params(), rule.partTypes(), oldTypes)) {
                        matches.add(element);
                    } else {
                        near.add(element.display() + (element.instance() ? ", an instance method" : ""));
                    }
                });
            }
        }
        Map<String, OldElement> byKey = new LinkedHashMap<>();
        matches.forEach(element -> byKey.putIfAbsent(element.key(), element));
        List<OldElement> distinct = List.copyOf(byKey.values());
        if (distinct.size() == 1) return rule.resolved(distinct.getFirst());
        if (distinct.size() > 1) return rule.refused("matches " + distinct.size() + " elements of " + ownerName);
        String what = field ? "field " + rule.method() : constructor ? "constructor"
                : "method " + rule.method();
        return rule.refused("matches no " + what + " of the old " + ownerName
                + (near.isEmpty() ? "" : "; it has " + String.join(" / ", near.stream().distinct().toList())
                + (near.stream().anyMatch(n -> n.endsWith("instance method") || n.endsWith("instance field"))
                        && !rule.self() ? " (an instance member's rule takes a first @Migrate.Self parameter)" : "")));
    }

    /** The type and every class and interface above it, the type first. */
    private static List<ITypeBinding> hierarchy(ITypeBinding type) {
        List<ITypeBinding> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ITypeBinding> queue = new ArrayList<>(List.of(type.getErasure()));
        while (!queue.isEmpty()) {
            ITypeBinding at = queue.removeFirst().getErasure();
            if (!seen.add(at.getKey())) continue;
            out.add(at);
            if (at.getSuperclass() != null) queue.add(at.getSuperclass());
            queue.addAll(List.of(at.getInterfaces()));
        }
        return out;
    }

    /**
     * Whether a rule's parameter types are the old element's: each the same type, or a type a {@code TYPE} rule
     * returns standing for the old type it replaces.
     */
    private static boolean sameParams(List<String> old, List<String> written, Map<String, String> oldTypes) {
        if (old.size() != written.size()) return false;
        for (int i = 0; i < old.size(); i++) {
            String type = written.get(i);
            if (type.equals(old.get(i))) continue;
            String base = type.endsWith("[]") ? type.substring(0, type.indexOf('[')) : type;
            String mapped = oldTypes.get(base);
            if (mapped == null || !(mapped + type.substring(base.length())).equals(old.get(i))) return false;
        }
        return true;
    }

    private static List<Rule> twinsRefused(List<Rule> rules) {
        List<Rule> out = new ArrayList<>();
        for (Rule rule : rules) {
            Rule twin = rule.element() == null ? null : rules.stream()
                    .filter(other -> other != rule && other.element() != null
                            && other.element().key().equals(rule.element().key()))
                    .findFirst().orElse(null);
            out.add(twin == null ? rule : rule.refused("names the same element as " + twin.qualified()
                    + "; keep one"));
        }
        return List.copyOf(out);
    }
}
