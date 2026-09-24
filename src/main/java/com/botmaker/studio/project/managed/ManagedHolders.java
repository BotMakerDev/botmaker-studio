package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;

import javax.lang.model.SourceVersion;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * The class a plugin's {@code @Managed} values live in, written by the host when a project has none
 * ({@code PluginValues.create}).
 *
 * <p>Written once and then the user's, as a file a template shipped is: {@code ProjectWrites.create} never
 * overwrites. The content is the least that compiles — the class, and per method-shaped value one
 * {@code @Managed} method returning the value's {@code initial}, or else its type's fresh value, written by
 * the host grammar. A type-level value is an
 * empty class carrying the annotation. Nothing here reads or merges a file that exists.
 */
public final class ManagedHolders {

    /** The annotation every holder names, by the class the contract ships it as. */
    static final String MANAGED = "com.botmaker.plugin.api.managed.Managed";

    private ManagedHolders() {}

    /** What {@link #plan} decided: the file and its source, or why there is none. */
    public sealed interface Plan {
        /** The holder to write. */
        record Write(Path file, String relative, String source) implements Plan {}

        /** No holder can be written, and the sentence to show. */
        record Refused(String reason) implements Plan {}
    }

    /**
     * The holder for {@code value}, declared by the plugin {@code pluginId} beside {@code declared}.
     *
     * @param declared every value the same plugin declares; those sharing {@code value}'s holder go in the
     *                 same class
     */
    public static Plan plan(ProjectConfig config, String pluginId, ManagedValue value, List<ManagedValue> declared,
                            ValueGrammar grammar) {
        String holder = value.holder();
        if (holder == null || !SourceVersion.isName(holder) || holder.contains(".")) {
            return new Plan.Refused("\"" + value.id() + "\" is not a value this project can be given a file for.");
        }
        String segment = segment(pluginId);
        if (segment == null) {
            return new Plan.Refused("The plugin " + pluginId + " has no name a package can be spelled with.");
        }
        String pkg = config.mainPackage() + ".plugins." + segment;
        TreeSet<String> imports = new TreeSet<>();
        imports.add(MANAGED);
        List<String> methods = new ArrayList<>();
        boolean typeLevel = value.valueType() == null;
        if (!typeLevel) {
            for (ManagedValue sibling : declared) {
                if (!holder.equals(sibling.holder()) || sibling.valueType() == null) continue;
                String method = method(sibling, grammar, imports);
                if (method == null) {
                    return new Plan.Refused("BotMaker cannot write a starting value for \"" + sibling.id()
                            + "\": no plugin in this project declares " + ValueTypes.sourceName(sibling.valueType())
                            + ".");
                }
                methods.add(method);
            }
        }
        StringBuilder source = new StringBuilder("package ").append(pkg).append(";\n\n");
        for (String name : imports) {
            if (!isImplicit(name, pkg)) source.append("import ").append(name).append(";\n");
        }
        source.append("\n/**\n * Values ").append(pluginId).append(" keeps in step with its own windows.\n")
                .append(" *\n * <p>Written by BotMaker because this project had none, and <b>yours from that moment</b>:"
                        + " it is never\n * rewritten. BotMaker changes the expression a {@code @Managed} method"
                        + " returns and nothing else.\n */\n");
        if (typeLevel) {
            source.append("@Managed(\"").append(value.id()).append("\")\n");
        }
        source.append("public final class ").append(holder).append(" {\n");
        for (String method : methods) source.append('\n').append(method);
        source.append('\n').append("    private ").append(holder).append("() {}\n}\n");
        String relative = "plugins/" + segment + "/" + holder + ".java";
        return new Plan.Write(config.mainPackageDir().resolve("plugins").resolve(segment).resolve(holder + ".java"),
                relative, source.toString());
    }

    /** One {@code @Managed} method, its imports added to {@code imports}; null when no fresh value exists. */
    private static String method(ManagedValue value, ValueGrammar grammar, TreeSet<String> imports) {
        Type type = value.valueType();
        JavaValue fresh = (value.initial() != null ? grammar.spell(type, value.initial())
                : grammar.freshSpelling(type)).orElse(null);
        if (fresh == null) return null;
        String name = methodName(value.id());
        imports.addAll(ValueTypes.imports(type));
        imports.addAll(fresh.imports());
        return "    @Managed(\"" + value.id() + "\")\n"
                + "    public static " + ValueTypes.sourceName(type) + " " + name + "() {\n"
                + "        return " + fresh.source() + ";\n"
                + "    }\n";
    }

    /** The id as a method name: itself when it is one, else its letters and digits in lower camel case. */
    static String methodName(String id) {
        if (SourceVersion.isIdentifier(id) && !SourceVersion.isKeyword(id)) return id;
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : id.toCharArray()) {
            if (!Character.isLetterOrDigit(c)) {
                upper = out.length() > 0;
                continue;
            }
            if (out.isEmpty() && Character.isDigit(c)) out.append("value");
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        String name = out.isEmpty() ? "value" : out.toString();
        return SourceVersion.isKeyword(name) ? name + "Value" : name;
    }

    /** The last segment of a plugin id, lower-cased, or null when it is no package name. */
    static String segment(String pluginId) {
        if (pluginId == null) return null;
        String last = pluginId.substring(pluginId.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SourceVersion.isIdentifier(last) && !SourceVersion.isKeyword(last) ? last : null;
    }

    private static boolean isImplicit(String name, String pkg) {
        int dot = name.lastIndexOf('.');
        String owner = dot < 0 ? "" : name.substring(0, dot);
        return owner.equals("java.lang") || owner.equals(pkg);
    }
}
