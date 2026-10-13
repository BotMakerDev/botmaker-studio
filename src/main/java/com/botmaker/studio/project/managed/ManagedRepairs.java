package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.refactor.ReviewMarker;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.lang.reflect.ParameterizedType;
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
 * A plugin's managed values in a bot, repaired during that plugin's upgrade the way the rest of the bot's code is
 * (2026-10-11): nothing converts them. Read against the version the bot moves to, each value the plugin marks is
 * one of three things.
 *
 * <ul>
 *   <li><b>Still the plugin's, and it compiles</b>: left as written.</li>
 *   <li><b>No longer the plugin's</b> — its id is gone, or it is declared another way now (a method that became
 *       a parameter, an enum that became a class of enums, a parameter whose field has another type now —
 *       {@code List<String>} for {@code List<Launcher>}, 2026-10-13): the declaration is removed, its file with it when
 *       nothing else is left there. While the bot's own code still names it, it is kept instead, <em>unmarked</em>
 *       — the user's code from then on — and said back in {@link Planned#left()}; a kept class whose name the
 *       plugin's own holder takes is renamed {@code <Class>Retired}, every use with it.</li>
 *   <li><b>Still the plugin's, but its expression no longer compiles</b>: it takes the declaration's first value
 *       ({@link ManagedValue#initial()}), marked {@code @Refactor} so the Review tab lists it.</li>
 * </ul>
 *
 * <p>A value the plugin declares and the bot lacks is not written here: a missing parameter is written on bind
 * and the rest by the plugin's own windows, as for any bot.
 *
 * <p><b>Pure, over the bot's one parse</b>: a {@link BotIndex} parsed against the <em>target</em> classpath in, a
 * {@link Planned plan} out, nothing written.
 */
public final class ManagedRepairs {

    private ManagedRepairs() {}

    /**
     * The bot after the repair.
     *
     * @param rewrites each file written, by path, with its whole new text
     * @param deleted  each file deleted: a removed holder that was its file's only class
     * @param repaired what was removed or reset, one sentence each
     * @param left     what was kept as written, one sentence each
     */
    public record Planned(Map<Path, String> rewrites, Set<Path> deleted, List<String> repaired, List<String> left) {

        public Planned {
            rewrites = Map.copyOf(rewrites);
            deleted = Set.copyOf(deleted);
            repaired = List.copyOf(repaired);
            left = List.copyOf(left);
        }

        /** Whether the plan changes nothing. */
        public boolean isEmpty() {
            return rewrites.isEmpty() && deleted.isEmpty();
        }

        /** Everything the window says about the bot's values: what was repaired, then what was kept. */
        public List<String> sentences() {
            List<String> out = new ArrayList<>(repaired);
            out.addAll(left);
            return List.copyOf(out);
        }
    }

    /** What a mark sits on. */
    private enum Kind { TYPE, METHOD, FIELD }

    /**
     * A plugin's mark in the bot.
     *
     * @param enumType whether a marked type is an enum
     * @param member   the marked method or field, null for a marked type
     * @param returns  a marked method's return type, by binary name with a primitive boxed, or a marked field's
     *                 whole type ({@link #spelled(org.eclipse.jdt.core.dom.ITypeBinding)}); null when it does not
     *                 resolve against the target classpath
     * @param start    the declaration's offset in its file
     * @param length   the declaration's length
     */
    private record Mark(String id, Kind kind, boolean enumType, Path file, String className, String member,
                        String returns, int start, int length) {

        boolean same(Mark other) {
            return id.equals(other.id) && kind == other.kind && file.equals(other.file)
                    && className.equals(other.className) && java.util.Objects.equals(member, other.member);
        }

        String qualified() {
            return member == null ? className : className + "." + member;
        }
    }

    /**
     * The repair of {@code index}'s values for the plugin declaring {@code live}.
     *
     * @param live    every value the target version declares
     * @param grammar the target version's grammar, which writes a first value
     * @param note    the {@code @Refactor} entry a reset value is marked with; null marks nothing (a bot without
     *                the contract on its classpath, {@link ReviewMarker#available})
     */
    public static Planned plan(BotIndex index, List<ManagedValue<?>> live, ValueGrammar grammar, String note) {
        Map<String, ManagedValue<?>> byId = new LinkedHashMap<>();
        Set<String> markers = new LinkedHashSet<>();
        Set<String> holders = new LinkedHashSet<>();
        for (ManagedValue<?> value : live) {
            if (value == null) continue;
            byId.putIfAbsent(value.id(), value);
            markers.add(value.marker());
            if (value.holder() != null) holders.add(value.holder());
        }
        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        BotIndex current = index;
        List<String> repaired = new ArrayList<>();
        List<String> left = new ArrayList<>();

        for (Mark first : marks(index, live, markers)) {
            Mark mark = marks(current, live, markers).stream().filter(first::same).findFirst().orElse(null);
            if (mark == null) continue;
            ManagedValue<?> value = byId.get(mark.id());
            String spelled = ManagedIds.spelled(mark.id(), live);
            if (value == null || !fits(mark, value)) {
                String why = value == null ? "is no longer one of the plugin's values" : "is declared another way now";
                Map<Path, String> without = without(sources, mark);
                BotIndex after = current.replacing(without);
                Optional<String> broken = current.firstNewError(after);
                if (broken.isEmpty()) {
                    sources = without;
                    current = after;
                    repaired.add(mark.qualified() + " (" + spelled + ") " + why + ", so it was removed.");
                    continue;
                }
                sources = unmarked(sources, current, mark);
                current = current.replacing(sources);
                String kept = mark.qualified();
                // A kept class in the file the plugin's own holder needs is moved out of its way, every use with it.
                if (mark.kind() == Kind.TYPE && holders.contains(mark.className())) {
                    Optional<String> moved = moveAside(sources, current, mark);
                    if (moved.isEmpty()) {
                        kept = mark.className() + "Retired";
                        current = current.replacing(sources);
                    }
                }
                left.add(mark.qualified() + " (" + spelled + ") " + why + " and the bot still names it ("
                        + broken.get() + "), so it was kept, unmarked" + (kept.equals(mark.qualified()) ? ""
                        : ", as " + kept) + "; delete it once nothing uses it.");
                continue;
            }
            if (mark.kind() != Kind.METHOD || !failsToCompile(current, mark)) continue;
            String text = sources.get(mark.file());
            Optional<JavaValue> initial = value.initial() == null ? Optional.empty()
                    : grammar.spell(value.type(), value.initial());
            String reset = initial.map(v -> JavaManagedEdits.setValue(text, mark.className(), mark.member(), v))
                    .orElse(text);
            Map<Path, String> tried = new LinkedHashMap<>(sources);
            tried.put(mark.file(), reset);
            BotIndex after = current.replacing(tried);
            boolean compiles = !reset.equals(text) && marks(after, live, markers).stream().filter(mark::same)
                    .findFirst().map(m -> !failsToCompile(after, m)).orElse(false);
            if (!compiles) {
                left.add(mark.qualified() + " (" + spelled + ") no longer compiles, and its first value does not"
                        + " either, so it was kept as written; fix it by hand.");
                continue;
            }
            String marked = note == null ? reset : ReviewMarker.markLines(reset, List.of(lineOf(reset, mark)), note);
            sources.put(mark.file(), marked);
            current = marked.equals(reset) ? after : current.replacing(sources);
            repaired.add(mark.qualified() + " (" + spelled + ") no longer compiled, so it takes its first value"
                    + " again" + (marked.equals(reset) ? "." : "; it is marked for review."));
        }

        Map<Path, String> rewrites = new LinkedHashMap<>();
        sources.forEach((file, text) -> {
            if (!text.equals(index.sources().get(file))) rewrites.put(file, text);
        });
        Set<Path> deleted = new LinkedHashSet<>(index.sources().keySet());
        deleted.removeAll(sources.keySet());
        return new Planned(rewrites, deleted, repaired, left);
    }

    /** Every managed id the bot marks: each marked method's, field's, and each marked class's or enum's. */
    public static Set<String> held(BotIndex index) {
        Set<String> ids = new LinkedHashSet<>();
        marks(index, null, null).forEach(mark -> ids.add(mark.id()));
        return ids;
    }

    /**
     * Whether the field {@code className} of {@code file} carrying the parameter {@code value}'s mark is written
     * the way its plugin declares it now — the upgrade's own test, so a {@code List<String>} left for a
     * {@code List<Launcher>} does not fit; empty when that class's fields carry no such mark (a nested class's are
     * not read).
     */
    public static Optional<Boolean> fieldFits(BotIndex index, ManagedValue<?> value, List<ManagedValue<?>> live,
                                              Path file, String className) {
        return marks(index, live, Set.of(value.marker())).stream()
                .filter(mark -> mark.kind() == Kind.FIELD && mark.id().equals(value.id())
                        && mark.file().equals(file) && mark.className().equals(className))
                .findFirst().map(mark -> fits(mark, value));
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /**
     * Every mark in the bot whose annotation is one of {@code markers}, its id read through {@code known} — so a
     * constant the target's id enum dropped still names the value it marked. Both null: every mark the bound
     * plugins know.
     */
    private static List<Mark> marks(BotIndex index, List<ManagedValue<?>> known, Set<String> markers) {
        return index.read(units -> {
            List<Mark> out = new ArrayList<>();
            units.forEach((file, unit) -> {
                for (Object each : unit.types()) {
                    if (!(each instanceof AbstractTypeDeclaration type)) continue;
                    String className = type.getName().getIdentifier();
                    add(out, known, markers, type, Kind.TYPE, type instanceof EnumDeclaration, file, className,
                            null, null);
                    if (!(type instanceof TypeDeclaration declared)) continue;
                    for (MethodDeclaration method : declared.getMethods()) {
                        add(out, known, markers, method, Kind.METHOD, false, file, className,
                                method.getName().getIdentifier(), returns(method));
                    }
                    for (FieldDeclaration field : declared.getFields()) {
                        add(out, known, markers, field, Kind.FIELD, false, file, className, fieldName(field),
                                spelled(fieldType(field)));
                    }
                }
            });
            return List.copyOf(out);
        });
    }

    private static void add(List<Mark> out, List<ManagedValue<?>> known, Set<String> markers,
                            BodyDeclaration declaration, Kind kind, boolean enumType, Path file, String className,
                            String member, String returns) {
        Annotation annotation = known == null ? JavaManagedSource.managedAnnotation(declaration)
                : ManagedIds.on(declaration, known);
        if (annotation == null) return;
        String id = known == null ? JavaManagedSource.idOf(annotation) : ManagedIds.idOf(annotation, known);
        if (id.isEmpty()) return;
        if (markers != null) {
            int dollar = id.lastIndexOf('$');
            if (dollar <= 0 || !markers.contains(id.substring(0, dollar))) return;
        }
        out.add(new Mark(id, kind, enumType, file, className, member, returns, declaration.getStartPosition(),
                declaration.getLength()));
    }

    /** {@code method}'s return type by binary name, a primitive boxed; null when it does not resolve. */
    private static String returns(MethodDeclaration method) {
        org.eclipse.jdt.core.dom.IMethodBinding binding = method.resolveBinding();
        org.eclipse.jdt.core.dom.ITypeBinding type = binding == null ? null : binding.getReturnType();
        if (type == null || type.isRecovered()) return null;
        if (type.isPrimitive()) return boxed(type.getName());
        return type.getErasure().getBinaryName();
    }

    private static String fieldName(FieldDeclaration field) {
        return field.fragments().isEmpty() ? ""
                : ((VariableDeclarationFragment) field.fragments().getFirst()).getName().getIdentifier();
    }

    /** Whether {@code mark} is written the way {@code value} is declared now. */
    private static boolean fits(Mark mark, ManagedValue<?> value) {
        return switch (mark.kind()) {
            case TYPE -> value.isOpenSet() && mark.enumType() == (value.isEnum() && !value.isGrouped());
            // A method returning another type is the old shape: resetting its expression would not compile.
            case METHOD -> !value.isOpenSet() && !value.isParam() && value.type().getName().equals(mark.returns());
            // A field of another type is the old shape too: the plugin reads it as its own type, and finds nothing.
            case FIELD -> value.isParam() && (mark.returns() == null || spelled(value.form()) == null
                    || mark.returns().equals(spelled(value.form())));
        };
    }

    /**
     * {@code type}'s whole name, type arguments included, nested classes dotted and a primitive boxed — the same
     * spelling {@link #spelled(Type)} gives the plugin's declared form; null when it does not resolve.
     */
    private static String spelled(org.eclipse.jdt.core.dom.ITypeBinding type) {
        if (!resolved(type)) return null;
        String name = type.isPrimitive() ? boxed(type.getName()) : type.getQualifiedName();
        return name.replaceAll("\\s", "");
    }

    /**
     * Whether {@code type} and every type in it resolve: a type argument missing from the classpath spells
     * differently, and a classpath problem must not read as a value declared another way.
     */
    private static boolean resolved(org.eclipse.jdt.core.dom.ITypeBinding type) {
        if (type == null || type.isRecovered()) return false;
        if (type.isArray()) return resolved(type.getElementType());
        if (type.isWildcardType()) return type.getBound() == null || resolved(type.getBound());
        for (org.eclipse.jdt.core.dom.ITypeBinding argument : type.getTypeArguments()) {
            if (!resolved(argument)) return false;
        }
        return true;
    }

    /** A field's type as its first variable has it, so {@code int x[]} reads as {@code int[]}. */
    private static org.eclipse.jdt.core.dom.ITypeBinding fieldType(FieldDeclaration field) {
        if (field.fragments().isEmpty()) return null;
        org.eclipse.jdt.core.dom.IVariableBinding variable =
                ((VariableDeclarationFragment) field.fragments().getFirst()).resolveBinding();
        return variable == null ? null : variable.getType();
    }

    /** A plugin's declared form, spelled as above; null for a form that is neither a class nor a generic one. */
    private static String spelled(Type form) {
        String name;
        if (form instanceof Class<?> type) {
            // getTypeName, not getName: an array is java.lang.String[] there, as JDT spells it.
            name = type.isPrimitive() ? boxed(type.getName()) : type.getTypeName();
        } else if (form instanceof ParameterizedType) {
            name = form.getTypeName();
        } else {
            return null;
        }
        return name.replace('$', '.').replaceAll("\\s", "");
    }

    private static String boxed(String primitive) {
        return switch (primitive) {
            case "boolean" -> Boolean.class.getName();
            case "int" -> Integer.class.getName();
            case "long" -> Long.class.getName();
            case "double" -> Double.class.getName();
            case "float" -> Float.class.getName();
            case "short" -> Short.class.getName();
            case "byte" -> Byte.class.getName();
            case "char" -> Character.class.getName();
            default -> primitive;
        };
    }

    /** Whether the compiler reports an error inside {@code mark}'s declaration. */
    private static boolean failsToCompile(BotIndex index, Mark mark) {
        return index.errors().getOrDefault(mark.file(), List.of()).stream()
                .anyMatch(p -> p.offset() >= mark.start() && p.offset() < mark.start() + mark.length());
    }

    /** The line {@code mark}'s method is named on in {@code source}. */
    private static int lineOf(String source, Mark mark) {
        CompilationUnit unit = BotParser.syntax(source);
        if (topLevel(unit, mark.className()) instanceof TypeDeclaration type) {
            for (MethodDeclaration method : type.getMethods()) {
                if (method.getName().getIdentifier().equals(mark.member())) {
                    return unit.getLineNumber(method.getName().getStartPosition());
                }
            }
        }
        return -1;
    }

    // --- taking a value out -------------------------------------------------------------------------------------

    /** {@code sources} with {@code mark}'s annotation removed: the declaration is the user's code from then on. */
    private static Map<Path, String> unmarked(Map<Path, String> sources, BotIndex index, Mark mark) {
        int[] span = index.read(units -> {
            CompilationUnit unit = units.get(mark.file());
            BodyDeclaration found = unit == null ? null : declaration(unit, mark);
            Annotation annotation = found == null ? null : JavaManagedSource.managedAnnotation(found);
            return annotation == null ? null : new int[] {annotation.getStartPosition(), annotation.getLength()};
        });
        String text = sources.get(mark.file());
        if (span == null || text == null) return sources;
        int end = span[0] + span[1];
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        if (end < text.length() && text.charAt(end) == '\n') end++;
        int start = span[0];
        while (start > 0 && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) start--;
        Map<Path, String> out = new LinkedHashMap<>(sources);
        out.put(mark.file(), text.substring(0, start) + text.substring(end));
        return out;
    }

    /**
     * The kept, unmarked class of {@code mark} renamed {@code <Class>Retired} in {@code sources}, every use with it
     * — the file is the one the plugin's holder of that name is written to; empty when done, else why not.
     */
    private static Optional<String> moveAside(Map<Path, String> sources, BotIndex index, Mark mark) {
        // Read again: taking the mark off moved the name.
        Integer nameAt = index.read(units -> {
            CompilationUnit unit = units.get(mark.file());
            AbstractTypeDeclaration type = unit == null ? null : topLevel(unit, mark.className());
            return type == null ? null : type.getName().getStartPosition();
        });
        if (nameAt == null) return Optional.of(mark.className() + " is not in " + mark.file().getFileName());
        return switch (Refactor.rename(index, mark.file(), nameAt, mark.className() + "Retired")) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned renamed -> {
                sources.putAll(renamed.rewrites());
                renamed.moves().forEach((from, to) -> sources.put(to, sources.remove(from)));
                yield Optional.empty();
            }
        };
    }

    /** {@code sources} without {@code mark}'s declaration, and without its file when nothing is left in it. */
    private static Map<Path, String> without(Map<Path, String> sources, Mark mark) {
        Map<Path, String> out = new LinkedHashMap<>(sources);
        String text = sources.get(mark.file());
        if (text == null) return out;
        String after = mark.kind() == Kind.TYPE ? withoutType(text, mark.className()) : withoutMember(text, mark);
        if (after == null) out.remove(mark.file());
        else out.put(mark.file(), after);
        return out;
    }

    /** {@code source} without the top-level type {@code className}; null when it was the file's only type. */
    static String withoutType(String source, String className) {
        CompilationUnit unit = BotParser.syntax(source);
        AbstractTypeDeclaration found = topLevel(unit, className);
        if (found == null) return source;
        if (unit.types().size() == 1) return null;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(found, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /**
     * {@code source} without {@code mark}'s method or field; null when that leaves the file's only class holding
     * nothing but constructors — a holder of values with no value left in it.
     */
    private static String withoutMember(String source, Mark mark) {
        CompilationUnit unit = BotParser.syntax(source);
        if (!(topLevel(unit, mark.className()) instanceof TypeDeclaration type)) return source;
        BodyDeclaration found = declaration(unit, mark);
        if (found == null) return source;
        boolean emptied = type.bodyDeclarations().stream()
                .allMatch(each -> each == found || each instanceof MethodDeclaration m && m.isConstructor());
        if (emptied && unit.types().size() == 1) return null;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(found, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    /** {@code mark}'s declaration in {@code unit}, or null. */
    private static BodyDeclaration declaration(CompilationUnit unit, Mark mark) {
        AbstractTypeDeclaration type = topLevel(unit, mark.className());
        if (type == null || mark.kind() == Kind.TYPE) return type;
        if (!(type instanceof TypeDeclaration declared)) return null;
        if (mark.kind() == Kind.METHOD) {
            for (MethodDeclaration method : declared.getMethods()) {
                if (!method.isConstructor() && method.getName().getIdentifier().equals(mark.member())) return method;
            }
            return null;
        }
        for (FieldDeclaration field : declared.getFields()) {
            if (fieldName(field).equals(mark.member())) return field;
        }
        return null;
    }

    private static AbstractTypeDeclaration topLevel(CompilationUnit unit, String className) {
        for (Object each : unit.types()) {
            if (each instanceof AbstractTypeDeclaration type && type.getName().getIdentifier().equals(className)) {
                return type;
            }
        }
        return null;
    }
}
