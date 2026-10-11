package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.Conversion;
import com.botmaker.plugin.api.source.Converted;
import com.botmaker.plugin.api.source.Converting;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A plugin's declared {@link Conversion}s run over a bot during that plugin's upgrade (2026-10-11): each retired
 * value the bot holds read through the new jar's declarations, handed to the plugin, the values it answers
 * written, and the retired holders taken out where nothing still names them.
 *
 * <p><b>The host never invents a migration.</b> What an old value means in the new shape is the plugin's answer
 * ({@link Conversion#convert}); this class only reads and writes, the way {@link JavaManagedValues} and
 * {@link ManagedSets} always have, so a converted value is written exactly as an edit in the plugin's window would
 * write it.
 *
 * <p><b>Pure, over the bot's one parse</b>, as {@link ManagedSets} is: a {@link BotIndex} — parsed against the
 * <em>target</em> classpath, so the new holders are checked against the jar they will compile with — in, a
 * {@link Planned plan} or a {@link Refused refusal} out, nothing written. All or nothing: a conversion that throws,
 * or answers a value the host cannot write, refuses the whole plan.
 *
 * <p><b>Kept, never forced.</b> A retired holder that is written by hand (a body that is not one {@code return}, a
 * constant the plugin cannot read), or that the bot's code still names, is left as written and said back in
 * {@link Planned#left()}: deleting it would lose what the user wrote or stop the bot compiling. It is kept
 * <em>unmarked</em> — the user's code now, not the plugin's value — and renamed {@code <Holder>Retired} when the new
 * holder needs its file.
 */
public final class ManagedConversions {

    private ManagedConversions() {}

    /**
     * One conversion that ran, as the upgrade window names it.
     *
     * @param reason the plugin's own sentence ({@link Conversion#reason()})
     * @param from   the marks it read, {@code @SdkValue(SdkValue.Id.OUTCOMES)}
     * @param to     the marks it wrote
     */
    public record Step(String reason, List<String> from, List<String> to) {

        public Step {
            from = List.copyOf(from);
            to = List.copyOf(to);
        }

        /** {@code Each activity is now its own enum of outcomes. (@SdkValue(…) → @SdkValue(…))} */
        public String display() {
            return reason + " (" + String.join(", ", from) + " → " + String.join(", ", to) + ")";
        }
    }

    /** What {@link #plan} decided. */
    public sealed interface Outcome permits Planned, Refused {}

    /**
     * The bot after every conversion that applied.
     *
     * @param rewrites each file written or created, by path, with its whole new text
     * @param deleted  each file deleted: a retired holder that was its file's only class
     * @param steps    the conversions that ran, in the order the plugin lists them; empty when the bot held none
     *                 of what they read
     * @param left     what was kept as written, one sentence each
     */
    public record Planned(Map<Path, String> rewrites, Set<Path> deleted, List<Step> steps, List<String> left)
            implements Outcome {

        public Planned {
            rewrites = Map.copyOf(rewrites);
            deleted = Set.copyOf(deleted);
            steps = List.copyOf(steps);
            left = List.copyOf(left);
        }

        /** Whether the plan changes nothing. */
        public boolean isEmpty() {
            return rewrites.isEmpty() && deleted.isEmpty();
        }
    }

    /** Nothing can be written, and the sentence that says why; the upgrade does not run. */
    public record Refused(String reason) implements Outcome {}

    /** Where a mark is written: its file, and its offset and length there. */
    private record Span(Path file, int start, int length) {}

    /** A retired value the bot holds: what it read as, and where it is written, for taking it out. */
    private record Held(Object value, boolean whole, Path file, String className, String methodName) {}

    /**
     * The conversions of the plugin {@code pluginId} over {@code index}.
     *
     * @param declared    every value the new jar declares, retired ones included
     * @param conversions the new jar's conversions, in the order it lists them
     * @param grammar     the new jar's grammar: what reads an old value and writes a new one
     */
    public static Outcome plan(BotIndex index, ProjectConfig config, String pluginId,
                               List<ManagedValue<?>> declared, List<Conversion> conversions, ValueGrammar grammar) {
        List<ManagedValue<?>> live = declared.stream().filter(v -> v != null && !v.isRetired()).toList();
        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        BotIndex current = index;
        List<Step> steps = new ArrayList<>();
        List<String> left = new ArrayList<>();

        for (Conversion conversion : conversions) {
            // A parameter is a field the bot reads by name, which no conversion here reads or writes yet.
            Optional<ManagedValue<?>> param = java.util.stream.Stream.concat(conversion.sources().stream(),
                    conversion.targets().stream()).filter(ManagedValue::isParam).findFirst();
            if (param.isPresent()) {
                return new Refused(conversion.reason() + " names the parameter "
                        + spelled(param.get(), declared) + ", and BotMaker does not convert a plugin's parameters.");
            }
            Map<String, Object> read = new LinkedHashMap<>();
            Map<ManagedValue<?>, Held> held = new LinkedHashMap<>();
            for (ManagedValue<?> source : conversion.sources()) {
                read(current, source, grammar, declared, left).ifPresent(found -> {
                    held.put(source, found);
                    if (found.value() != null) read.put(source.id(), found.value());
                });
            }
            if (held.isEmpty()) continue;
            // Only hand-written values: there is nothing to convert, and running it would write its defaults over
            // a value the bot may already hold. The holders are kept, as read() said.
            if (read.isEmpty()) {
                for (ManagedValue<?> source : held.keySet()) {
                    sources = unmarked(current, sources, source.id());
                    current = current.replacing(sources);
                }
                continue;
            }

            Converted written;
            try {
                written = conversion.convert(Converting.of(read));
            } catch (IllegalStateException refused) {
                return new Refused(refused.getMessage());
            }

            for (Map.Entry<ManagedValue<?>, Held> each : held.entrySet()) {
                if (each.getValue().whole()) {
                    Map<Path, String> without = without(sources, each.getValue());
                    Optional<String> broken = current.firstNewError(current.replacing(without));
                    if (broken.isEmpty()) {
                        sources = without;
                        current = current.replacing(sources);
                        continue;
                    }
                    left.add(spelled(each.getKey(), declared) + " is still named by the bot (" + broken.get()
                            + "), so it was kept, unmarked; delete it once nothing uses it.");
                }
                // Kept: no longer the plugin's, so no later upgrade counts it as a value nothing converts.
                sources = unmarked(current, sources, each.getKey().id());
                current = current.replacing(sources);
            }

            for (ManagedValue<?> target : conversion.targets()) {
                if (!written.written().containsKey(target.id())) continue;
                Object value = written.written().get(target.id());
                Optional<String> refused = target.isOpenSet()
                        ? writeSet(sources, current, config, pluginId, target, value, live, grammar)
                        : writeMethod(sources, current, config, pluginId, target, value, live, grammar);
                if (refused.isPresent()) return new Refused(refused.get());
                current = current.replacing(sources);
            }
            steps.add(new Step(conversion.reason(),
                    conversion.sources().stream().map(v -> spelled(v, declared)).toList(),
                    conversion.targets().stream().map(v -> spelled(v, declared)).toList()));
        }

        Map<Path, String> rewrites = new LinkedHashMap<>();
        sources.forEach((file, text) -> {
            if (!text.equals(index.sources().get(file))) rewrites.put(file, text);
        });
        Set<Path> deleted = new LinkedHashSet<>(index.sources().keySet());
        deleted.removeAll(sources.keySet());
        return new Planned(rewrites, deleted, steps, left);
    }

    /**
     * The ids of the managed values the bot holds that {@code before} declared and the new jar neither declares
     * live nor converts — what an upgrade would leave unread, so it is refused rather than run (2026-10-11).
     *
     * @param before the ids the plugin's current jar declares
     */
    public static List<String> unconverted(BotIndex index, Set<String> before, List<ManagedValue<?>> declared,
                                            List<Conversion> conversions) {
        Set<String> answered = new LinkedHashSet<>();
        for (ManagedValue<?> value : declared) {
            if (value != null && !value.isRetired()) answered.add(value.id());
        }
        for (Conversion conversion : conversions) {
            conversion.sources().forEach(source -> answered.add(source.id()));
        }
        List<String> out = new ArrayList<>();
        for (String id : held(index)) {
            if (before.contains(id) && !answered.contains(id)) out.add(id);
        }
        return List.copyOf(out);
    }

    /** Every managed id the bot marks: each marked method's, and each marked class's or enum's. */
    public static Set<String> held(BotIndex index) {
        Set<String> ids = new LinkedHashSet<>();
        index.read(units -> {
            units.values().forEach(unit -> {
                for (Object each : unit.types()) {
                    if (!(each instanceof AbstractTypeDeclaration type)) continue;
                    String id = idOf(type);
                    if (!id.isEmpty()) ids.add(id);
                    if (type instanceof TypeDeclaration declared) {
                        for (MethodDeclaration method : declared.getMethods()) {
                            String methodId = idOf(method);
                            if (!methodId.isEmpty()) ids.add(methodId);
                        }
                        // A plugin's parameter: a marked field (2026-10-11).
                        for (org.eclipse.jdt.core.dom.FieldDeclaration field : declared.getFields()) {
                            String fieldId = idOf(field);
                            if (!fieldId.isEmpty()) ids.add(fieldId);
                        }
                    }
                }
            });
            return null;
        });
        return ids;
    }

    // --- reading -----------------------------------------------------------------------------------------------

    /**
     * What the bot holds of the retired {@code source}, or empty when it holds nothing of it. A value written by
     * hand reads as null and is not {@link Held#whole() whole}: the conversion sees it absent and the holder is
     * kept, with a sentence in {@code left}.
     */
    private static Optional<Held> read(BotIndex index, ManagedValue<?> source, ValueGrammar grammar,
                                       List<ManagedValue<?>> declared, List<String> left) {
        if (!source.isOpenSet()) {
            Optional<ManagedMethod> found = JavaManagedValues.over(index, grammar).stream()
                    .filter(m -> m.id().equals(source.id())).findFirst();
            if (found.isEmpty()) return Optional.empty();
            ManagedMethod method = found.get();
            Object value = method.expression().isEmpty() ? null
                    : grammar.valueOf(source.type(), method.expression()).orElse(null);
            if (value == null) {
                left.add(method.qualified() + " is written by hand, so it was not converted and was kept.");
                return Optional.of(new Held(null, false, method.file(), method.className(), method.methodName()));
            }
            return Optional.of(new Held(value, true, method.file(), method.className(), method.methodName()));
        }
        Optional<Map.Entry<Path, String>> holder = holder(index, source.id());
        if (holder.isEmpty()) return Optional.empty();
        Map<String, Object> members = new LinkedHashMap<>();
        boolean whole = true;
        for (ManagedSets.Member member : ManagedSets.members(index, source.id(), grammar)) {
            Object value = memberValue(source, member, grammar);
            if (value == null) {
                left.add(member.qualified() + " could not be read as " + spelled(source, declared)
                        + " holds, so it was not converted and its class was kept.");
                whole = false;
                continue;
            }
            members.put(member.memberName(), value);
        }
        return Optional.of(new Held(members, whole, holder.get().getKey(), holder.get().getValue(), null));
    }

    /** A constant's value: made by the plugin from its name in an enum set, read off its initialiser otherwise. */
    private static Object memberValue(ManagedValue<?> set, ManagedSets.Member member, ValueGrammar grammar) {
        if (set.isGrouped()) return member.group() == null ? null : set.byName(member.group(), member.name());
        if (set.isEnum()) return set.byName(member.name());
        return member.initializer() == null ? null : grammar.valueOf(set.type(), member.initializer()).orElse(null);
    }

    /** The file and simple name of the class or enum marked {@code id}, the first one if two are. */
    private static Optional<Map.Entry<Path, String>> holder(BotIndex index, String id) {
        return index.read(units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (each instanceof AbstractTypeDeclaration type && id.equals(idOf(type))) {
                        return Optional.of(Map.entry(entry.getKey(), type.getName().getIdentifier()));
                    }
                }
            }
            return Optional.empty();
        });
    }

    private static String idOf(org.eclipse.jdt.core.dom.BodyDeclaration declaration) {
        Annotation annotation = JavaManagedSource.managedAnnotation(declaration);
        return annotation == null ? "" : JavaManagedSource.idOf(annotation);
    }

    // --- taking a retired holder out ----------------------------------------------------------------------------

    /**
     * {@code sources} with the mark of the declaration carrying {@code id} removed — a retired holder that is
     * kept is the user's code from then on, not the plugin's value. Unchanged when no declaration carries it.
     */
    private static Map<Path, String> unmarked(BotIndex index, Map<Path, String> sources, String id) {
        Span span = index.read(units -> {
            for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
                for (Object each : entry.getValue().types()) {
                    if (!(each instanceof AbstractTypeDeclaration type)) continue;
                    List<org.eclipse.jdt.core.dom.BodyDeclaration> candidates = new ArrayList<>();
                    candidates.add(type);
                    if (type instanceof TypeDeclaration declared) candidates.addAll(List.of(declared.getMethods()));
                    for (org.eclipse.jdt.core.dom.BodyDeclaration candidate : candidates) {
                        Annotation mark = JavaManagedSource.managedAnnotation(candidate);
                        if (mark != null && id.equals(JavaManagedSource.idOf(mark))) {
                            return new Span(entry.getKey(), mark.getStartPosition(), mark.getLength());
                        }
                    }
                }
            }
            return null;
        });
        if (span == null) return sources;
        Path file = span.file();
        String text = sources.get(file);
        if (text == null) return sources;
        int end = span.start() + span.length();
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        if (end < text.length() && text.charAt(end) == '\n') end++;
        int start = span.start();
        while (start > 0 && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) start--;
        Map<Path, String> out = new LinkedHashMap<>(sources);
        out.put(file, text.substring(0, start) + text.substring(end));
        return out;
    }

    /**
     * {@code sources} without {@code held}: its method, or its whole class or enum — and the file itself when
     * nothing is left in it.
     */
    private static Map<Path, String> without(Map<Path, String> sources, Held held) {
        Map<Path, String> out = new LinkedHashMap<>(sources);
        String text = sources.get(held.file());
        if (text == null) return out;
        String after = held.methodName() == null ? withoutType(text, held.className())
                : withoutMethod(text, held.className(), held.methodName());
        if (after == null) out.remove(held.file());
        else out.put(held.file(), after);
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
     * {@code source} without {@code className.methodName}; null when that leaves the file's only class holding
     * nothing but constructors — a holder of values with no value left in it.
     */
    static String withoutMethod(String source, String className, String methodName) {
        CompilationUnit unit = BotParser.syntax(source);
        if (!(topLevel(unit, className) instanceof TypeDeclaration type)) return source;
        MethodDeclaration found = null;
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getName().getIdentifier().equals(methodName) && !method.isConstructor()) found = method;
        }
        if (found == null) return source;
        MethodDeclaration removed = found;
        boolean emptied = type.bodyDeclarations().stream()
                .allMatch(each -> each == removed || each instanceof MethodDeclaration m && m.isConstructor());
        if (emptied && unit.types().size() == 1) return null;
        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        rewrite.remove(found, null);
        return AstRewriteHelper.applyRewrite(rewrite, source);
    }

    private static AbstractTypeDeclaration topLevel(CompilationUnit unit, String className) {
        for (Object each : unit.types()) {
            if (each instanceof AbstractTypeDeclaration type && type.getName().getIdentifier().equals(className)) {
                return type;
            }
        }
        return null;
    }

    // --- writing the new values --------------------------------------------------------------------------------

    /** {@code value} written as the method-shaped {@code target}, its holder or method created when missing. */
    private static Optional<String> writeMethod(Map<Path, String> sources, BotIndex index, ProjectConfig config,
                                                String pluginId, ManagedValue<?> target, Object value,
                                                List<ManagedValue<?>> live, ValueGrammar grammar) {
        Optional<JavaValue> spelled = grammar.spell(target.type(), value);
        if (spelled.isEmpty()) {
            return Optional.of("BotMaker cannot write the converted " + spelled(target, live) + ": no plugin in "
                    + "this project declares " + target.type().getSimpleName() + ".");
        }
        Optional<ManagedMethod> found = method(index, target, grammar);
        if (found.isEmpty()) {
            Optional<String> refused = createMethod(sources, index, config, pluginId, target, live, grammar);
            if (refused.isPresent()) return refused;
            found = method(index.replacing(sources), target, grammar);
            if (found.isEmpty()) return Optional.of(spelled(target, live) + " could not be written.");
        }
        ManagedMethod method = found.get();
        String text = sources.get(method.file());
        String after = JavaManagedEdits.setValue(text, method.className(), method.methodName(), spelled.get());
        if (after.equals(text) && !spelled.get().source().equals(method.expression())) {
            return Optional.of(method.qualified() + " is written by hand, so the converted value could not be "
                    + "written into it.");
        }
        sources.put(method.file(), after);
        return Optional.empty();
    }

    private static Optional<ManagedMethod> method(BotIndex index, ManagedValue<?> target, ValueGrammar grammar) {
        return JavaManagedValues.over(index, grammar).stream().filter(m -> m.id().equals(target.id())).findFirst();
    }

    /**
     * {@code target}'s marked method, added to the class already holding a sibling of it, or its whole holder
     * written as {@link ManagedHolders} writes one on bind.
     */
    private static Optional<String> createMethod(Map<Path, String> sources, BotIndex index, ProjectConfig config,
                                                 String pluginId, ManagedValue<?> target,
                                                 List<ManagedValue<?>> live, ValueGrammar grammar) {
        for (ManagedMethod sibling : JavaManagedValues.over(index, grammar)) {
            boolean shares = live.stream().anyMatch(v -> v.id().equals(sibling.id())
                    && target.holder() != null && target.holder().equals(v.holder()));
            if (!shares) continue;
            String text = sources.get(sibling.file());
            String after = ManagedHolders.withMethod(text, sibling.className(), target, grammar);
            if (after.equals(text)) return Optional.of(spelled(target, live) + " could not be added to "
                    + sibling.className() + ".");
            sources.put(sibling.file(), after);
            return Optional.empty();
        }
        return createHolder(sources, index, config, pluginId, target, live, grammar);
    }

    /**
     * {@code target}'s holder written into {@code sources}. A file already at its path holding the kept, unmarked
     * retired class of the same name — {@code Activities} the enum, before {@code Activities} the class of enums —
     * is renamed out of the way first, every use with it ({@code ActivitiesRetired}); a file holding anything the
     * plugin still marks refuses.
     */
    private static Optional<String> createHolder(Map<Path, String> sources, BotIndex index, ProjectConfig config,
                                                 String pluginId, ManagedValue<?> target,
                                                 List<ManagedValue<?>> live, ValueGrammar grammar) {
        ManagedHolders.Plan plan = ManagedHolders.plan(config, pluginId, target, live, grammar);
        if (plan instanceof ManagedHolders.Plan.Refused refused) return Optional.of(refused.reason());
        ManagedHolders.Plan.Write write = (ManagedHolders.Plan.Write) plan;
        Path file = write.file().toAbsolutePath().normalize();
        if (sources.containsKey(file)) {
            Optional<String> moved = moveAside(sources, index, file, target.holder());
            if (moved.isPresent()) {
                return Optional.of(write.relative() + " is still in the bot (" + moved.get() + "), so "
                        + spelled(target, live) + " cannot be written there.");
            }
        }
        sources.put(file, write.source());
        return Optional.empty();
    }

    /** The unmarked class {@code className} in {@code file} renamed to {@code <className>Retired}, or why not. */
    private static Optional<String> moveAside(Map<Path, String> sources, BotIndex index, Path file,
                                              String className) {
        Integer start = index.read(units -> {
            CompilationUnit unit = units.get(file);
            AbstractTypeDeclaration type = unit == null ? null : topLevel(unit, className);
            boolean marked = type == null || !idOf(type).isEmpty() || type instanceof TypeDeclaration declared
                    && java.util.Arrays.stream(declared.getMethods()).anyMatch(m -> !idOf(m).isEmpty());
            return marked ? null : type.getName().getStartPosition();
        });
        if (start == null) return Optional.of("it still holds a value the plugin marks");
        return switch (Refactor.rename(index, file, start, className + "Retired")) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned renamed -> {
                sources.putAll(renamed.rewrites());
                renamed.moves().forEach((from, to) -> sources.put(to, sources.remove(from)));
                yield Optional.empty();
            }
        };
    }

    /** {@code value} — member name to value — added to the open set {@code target}; a member it has is kept. */
    private static Optional<String> writeSet(Map<Path, String> sources, BotIndex index, ProjectConfig config,
                                             String pluginId, ManagedValue<?> target, Object value,
                                             List<ManagedValue<?>> live, ValueGrammar grammar) {
        if (holder(index, target.id()).isEmpty()) {
            Optional<String> refused = createHolder(sources, index, config, pluginId, target, live, grammar);
            if (refused.isPresent()) return refused;
            index = index.replacing(sources);
        }
        Map<?, ?> members = (Map<?, ?>) value;
        if (target.isGrouped()) return writeGroups(sources, index, target, members, live);

        Set<String> have = new LinkedHashSet<>();
        ManagedSets.members(index, target.id(), grammar).forEach(m -> have.add(m.memberName()));
        for (Map.Entry<?, ?> member : members.entrySet()) {
            String name = String.valueOf(member.getKey());
            if (have.contains(name)) continue;
            Refactor.Outcome outcome;
            if (target.isEnum()) {
                outcome = ManagedSets.add(index, target.id(), name, null, null);
            } else {
                Optional<JavaValue> initializer = grammar.spell(target.type(), member.getValue());
                if (initializer.isEmpty()) {
                    return Optional.of("BotMaker cannot write " + name + " into " + spelled(target, live) + ".");
                }
                outcome = ManagedSets.add(index, target.id(), name, target.type(), initializer.get());
            }
            Optional<String> refused = apply(sources, outcome);
            if (refused.isPresent()) return refused;
            index = index.replacing(sources);
        }
        return Optional.empty();
    }

    /** A grouped set's members, {@code "Collect.WON"}, written as one nested enum per group. */
    private static Optional<String> writeGroups(Map<Path, String> sources, BotIndex index, ManagedValue<?> target,
                                                Map<?, ?> members, List<ManagedValue<?>> live) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (Object key : members.keySet()) {
            String name = String.valueOf(key);
            int dot = name.indexOf('.');
            if (dot <= 0 || dot != name.lastIndexOf('.') || dot == name.length() - 1) {
                return Optional.of("The conversion wrote " + name + " into " + spelled(target, live)
                        + ", which is not a constant of a nested enum (Group.NAME).");
            }
            groups.computeIfAbsent(name.substring(0, dot), g -> new ArrayList<>()).add(name.substring(dot + 1));
        }
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            Optional<ManagedSets.Group> existing = ManagedSets.group(index, target.id(), group.getKey());
            if (existing.isEmpty()) {
                Optional<String> refused = apply(sources,
                        ManagedSets.addGroup(index, target.id(), group.getKey(), group.getValue(), target.type()));
                if (refused.isPresent()) return refused;
                index = index.replacing(sources);
                continue;
            }
            Set<String> have = new LinkedHashSet<>();
            existing.get().members().forEach(m -> have.add(m.name()));
            for (String constant : group.getValue()) {
                if (have.contains(constant)) continue;
                Optional<String> refused = apply(sources,
                        ManagedSets.add(index, target.id(), group.getKey() + "." + constant, null, null));
                if (refused.isPresent()) return refused;
                index = index.replacing(sources);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> apply(Map<Path, String> sources, Refactor.Outcome outcome) {
        return switch (outcome) {
            case Refactor.Refused refused -> Optional.of(refused.reason());
            case Refactor.Planned plan -> {
                sources.putAll(plan.rewrites());
                yield Optional.empty();
            }
        };
    }

    private static String spelled(ManagedValue<?> value, List<ManagedValue<?>> declared) {
        return ManagedIds.spelled(value.id(), declared);
    }
}
