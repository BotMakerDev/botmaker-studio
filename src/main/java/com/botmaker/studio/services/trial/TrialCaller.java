package com.botmaker.studio.services.trial;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.project.managed.ManagedIds;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The throwaway class ▶ Try runs: a {@code main} that hands one statement, and the locals it reads, to a plugin's
 * trial entry — {@code Bot.trial(() -> …, Sdk.class)} — written beside the bot's classes, never into its sources.
 *
 * <p>Written as text because nothing reads it back: it is compiled once into {@code target/botmaker-trial/} and
 * run, and the next try writes it again. The statement and the computed locals are the bot's own Java, qualified
 * by {@link TrialPlan}; every other value is the grammar's ({@link JavaValue}); the entry is named by the method the
 * plugin's {@code trial(Bot::trial)} resolved to.
 *
 * <p>The statement sits in a method of its own returning {@code Object} when its method returns a value, so a
 * {@code return} in it is reported rather than refused, and inside {@code if (true)} so that a statement that always
 * returns leaves the fall-through reachable, which javac otherwise rejects.
 */
public final class TrialCaller {

    /** The caller's simple name. */
    public static final String SIMPLE_NAME = "BotMakerTry";

    /** A caller: its class, qualified, and its source. */
    public record Source(String className, String text) {

        /** Its file under a source root: the package's folders, then the class. */
        public String path() {
            return className.replace('.', '/') + ".java";
        }
    }

    /** One local's value, as chosen: its initializer run again, or a value. */
    public sealed interface Value permits Computed, Given {}

    public record Computed() implements Value {}

    public record Given(JavaValue value) implements Value {}

    private TrialCaller() {}

    /** The first bound plugin's trial entry ({@code trial(Bot::trial)}); empty when no plugin offers one. */
    public static Optional<Method> entry() {
        for (StudioPlugin plugin : PluginHost.plugins()) {
            try {
                Optional<Method> entry = plugin.trialEntry();
                if (entry != null && entry.isPresent()) return entry;
            } catch (RuntimeException | LinkageError e) {
                // Includes a plugin built against a contract without this method: it offers no trial.
                System.err.println("Warning: " + plugin.id() + " could not offer a trial entry: " + e);
            }
        }
        return Optional.empty();
    }

    /**
     * The caller for {@code plan} with {@code values} for the locals it needs.
     *
     * @param entry   the plugin's trial entry, a {@code public static (Runnable, Class<?>...)} method
     * @param holders the bot's classes holding managed values, qualified, handed to the entry
     * @throws IllegalArgumentException when a needed local has no value
     */
    public static Source write(TrialPlan.Plan plan, Map<String, Value> values, Method entry, List<String> holders) {
        Map<String, TrialPlan.Source> chosen = new java.util.HashMap<>();
        values.forEach((name, value) -> chosen.put(name,
                value instanceof Computed ? TrialPlan.Source.COMPUTE : TrialPlan.Source.ASK));
        Set<String> imports = new LinkedHashSet<>(plan.imports());
        StringBuilder locals = new StringBuilder();
        for (TrialPlan.Local local : plan.needed(chosen)) {
            Value value = values.get(local.name());
            if (value == null) value = local.defaultSource() == TrialPlan.Source.COMPUTE ? new Computed()
                    : local.lastRun() != null ? new Given(local.lastRun()) : null;
            String written = switch (value) {
                case Computed ignored when local.initializer() != null -> local.initializer();
                case Given given -> {
                    for (String each : given.value().imports()) imports.add("import " + each + ";");
                    yield given.value().source();
                }
                case null, default -> throw new IllegalArgumentException(local.name() + " needs a value.");
            };
            locals.append("        ").append(local.type()).append(' ').append(local.name()).append(" = ")
                    .append(written).append(";\n");
        }

        String pkg = plan.packageName();
        StringBuilder out = new StringBuilder();
        if (!pkg.isEmpty()) out.append("package ").append(pkg).append(";\n\n");
        for (String each : imports) out.append(each).append('\n');
        if (!imports.isEmpty()) out.append('\n');
        out.append("/** Written by BotMaker Studio for ▶ Try: ").append(comment(plan.label()))
                .append(". Not part of the bot. */\n");
        out.append("public final class ").append(SIMPLE_NAME).append(" {\n\n");
        out.append("    private static final Object NO_RESULT = new Object();\n\n");
        out.append("    public static void main(String[] args) {\n");
        out.append("        ").append(entry.getDeclaringClass().getCanonicalName()).append('.').append(entry.getName())
                .append("(() -> {\n");
        out.append("            try {\n");
        if (plan.valued()) {
            out.append("                Object result = statement();\n");
            out.append("                if (result != NO_RESULT) report(\"returned\", result);\n");
        } else {
            out.append("                statement();\n");
        }
        out.append("            } catch (RuntimeException | Error e) {\n");
        out.append("                throw e;\n");
        out.append("            } catch (Exception e) {\n");
        out.append("                throw new RuntimeException(e);\n");
        out.append("            }\n");
        out.append("        }");
        for (String holder : holders) out.append(", ").append(holder).append(".class");
        out.append(");\n");
        out.append("    }\n\n");
        out.append("    private static ").append(plan.valued() ? "Object" : "void")
                .append(" statement() throws Exception {\n");
        out.append(locals);
        out.append("        if (true) {\n");
        for (String line : plan.body().split("\n", -1)) out.append("            ").append(line).append('\n');
        out.append("        }\n");
        if (plan.valued()) out.append("        return NO_RESULT;\n");
        out.append("    }\n\n");
        out.append("    private static void report(String what, Object value) {\n");
        out.append("        System.out.println(\"▶ Try: \" + what + \" = \" + value);\n");
        out.append("    }\n");
        out.append("}\n");
        return new Source(pkg.isEmpty() ? SIMPLE_NAME : pkg + "." + SIMPLE_NAME, out.toString());
    }

    /**
     * The bot's classes holding managed values (a plugin's marker on a method) — the ones a run names,
     * {@code Bot.run(…, Sdk.class)} — read off the bindings, in name order.
     */
    public static List<String> holders(BotIndex index) {
        if (index == null) return List.of();
        return index.read(units -> {
            Set<String> found = new TreeSet<>();
            units.values().forEach(unit -> unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration method) {
                    IMethodBinding binding = method.resolveBinding();
                    if (binding == null) return false;
                    for (IAnnotationBinding annotation : binding.getAnnotations()) {
                        ITypeBinding type = annotation.getAnnotationType();
                        if (ManagedIds.isMarker(type)) {
                            found.add(binding.getDeclaringClass().getErasure().getQualifiedName());
                        }
                    }
                    return false;
                }
            }));
            return new ArrayList<>(found);
        });
    }

    /** {@code text} safe inside a Javadoc comment. */
    private static String comment(String text) {
        return text.replace("*/", "* /");
    }
}
