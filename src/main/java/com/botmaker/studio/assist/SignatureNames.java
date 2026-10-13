package com.botmaker.studio.assist;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;
import com.botmaker.studio.plugin.grammar.SourceNames;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.source.BotParser;
import com.botmaker.studio.services.BotSources;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A signature an assistant writes — a method's return type and parameters, by type name — read into what the
 * Add Function dialog would have built. Only the types the dialog offers ({@link BotType#values()}, the editor's
 * own and every loaded plugin's), one or a {@code List} of one, so a model names a type and never writes Java.
 */
final class SignatureNames {

    private SignatureNames() {}

    /**
     * The type {@code name} names: {@code int}, {@code String}, {@code Point}, {@code List<Point>}, or a type's
     * label ({@code Whole number}); {@code void} only where {@code returned}.
     *
     * @throws IllegalArgumentException naming the types there are
     */
    static SignatureType type(String name, boolean returned) {
        return type(name, returned, List::of);
    }

    /**
     * The same, also reading a type the bot declares — one of {@code botTypes}, by canonical name — named as
     * written in a file ({@code Activities.Research}, {@code Research}) or in full: an activity's body returns its
     * own enum, which no plugin declares (2026-10-13). They are read only when the name is not one Studio offers,
     * since reading them parses the bot; a bot class named like an offered type is the offered one.
     */
    static SignatureType type(String name, boolean returned, Supplier<? extends Collection<String>> botTypes) {
        String given = name == null ? "" : name.strip();
        if (given.isEmpty() || given.equals("void")) {
            if (returned) return SignatureType.of(BotType.NOTHING);
            throw new IllegalArgumentException("A parameter needs a type.");
        }
        boolean list = given.startsWith("List<") && given.endsWith(">");
        String element = list ? given.substring(5, given.length() - 1).strip() : given;
        Optional<BotType> offered = find(element);
        if (offered.isEmpty()) {
            Collection<String> declared = botTypes.get();
            List<String> own = declared.contains(element) ? List.of(element)
                    : declared.stream().filter(t -> t.endsWith("." + element)).toList();
            if (own.size() > 1) {
                throw new IllegalArgumentException(element + " names several of the bot's types: " + own
                        + ". Give one of them.");
            }
            if (own.size() == 1) {
                ValueTypes.BotClass bot = new ValueTypes.BotClass(own.getFirst(), List.of());
                return new SignatureType.Typed(list ? ValueTypes.listOf(bot) : bot);
            }
        }
        BotType type = offered.orElseThrow(() -> new IllegalArgumentException("Studio offers no type "
                + element + ". The types are " + offered() + ", or one the bot declares (Activities.Collect), "
                + "each alone or as List<…>."));
        if (type == BotType.NOTHING) {
            if (returned && !list) return SignatureType.of(BotType.NOTHING);
            throw new IllegalArgumentException("void is only a return type.");
        }
        if (list && !type.listable()) throw new IllegalArgumentException("There is no List of " + element + ".");
        return SignatureType.of(list ? BotType.Choice.listOf(type) : BotType.Choice.of(type));
    }

    /**
     * {@code params}, each {@code {name, type}}, as a draft's parameters. A name the method already has keeps
     * its place in its calls ({@code origin}); a new one is added to them.
     *
     * @param existing the method's parameter names now, in order; empty for a new method
     */
    static List<FunctionDraft.Parameter> parameters(List<?> params, List<String> existing) {
        return parameters(params, existing, List::of);
    }

    /** The same, a parameter's type also one of {@code botTypes}, as {@link #type(String, boolean, Supplier)}. */
    static List<FunctionDraft.Parameter> parameters(List<?> params, List<String> existing,
                                                    Supplier<? extends Collection<String>> botTypes) {
        return params.stream().map(each -> {
            if (!(each instanceof Map<?, ?> param)) {
                throw new IllegalArgumentException("A parameter is {\"name\": …, \"type\": …}, not " + each + ".");
            }
            String name = String.valueOf(param.get("name") == null ? "" : param.get("name")).strip();
            FunctionDraft.parameterNameProblem(name, List.of()).ifPresent(problem -> {
                throw new IllegalArgumentException(problem);
            });
            SignatureType type = type(String.valueOf(param.get("type") == null ? "" : param.get("type")), false,
                    botTypes);
            int origin = existing.indexOf(name);
            return new FunctionDraft.Parameter(name, type, origin < 0 ? FunctionDraft.Parameter.NEW : origin);
        }).toList();
    }

    /**
     * Every type the bot declares — classes, records, interfaces and enums, member types too — by canonical
     * name, read off the sources {@code state} holds and the rest of the bot on disk.
     */
    static List<String> botTypes(ProjectConfig config, ProjectState state) {
        List<String> names = new ArrayList<>();
        if (config == null) return names;
        BotSources.scan(config, state, (file, source) -> BotParser.syntax(source).accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration declaration) {
                return add(declaration);
            }

            @Override
            public boolean visit(EnumDeclaration declaration) {
                return add(declaration);
            }

            @Override
            public boolean visit(RecordDeclaration declaration) {
                return add(declaration);
            }

            private boolean add(AbstractTypeDeclaration declaration) {
                // A class declared inside a method has no name another file can write.
                if (!(declaration.getParent() instanceof CompilationUnit
                        || declaration.getParent() instanceof AbstractTypeDeclaration)) {
                    return true;
                }
                String canonical = SourceNames.canonicalOf(declaration);
                if (!names.contains(canonical)) names.add(canonical);
                return true;
            }
        }));
        return names;
    }

    private static Optional<BotType> find(String name) {
        List<BotType> all = BotType.values();
        return all.stream().filter(t -> name.equals(t.typeName()) || name.equals(t.boxedName())).findFirst()
                .or(() -> all.stream().filter(t -> simple(t.typeName()).equals(name)).findFirst())
                .or(() -> all.stream().filter(t -> t.label().toLowerCase(Locale.ROOT)
                        .equals(name.toLowerCase(Locale.ROOT))).findFirst());
    }

    private static List<String> offered() {
        return BotType.values().stream().filter(t -> t != BotType.NOTHING).map(t -> simple(t.typeName())).toList();
    }

    private static String simple(String typeName) {
        int dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }
}
