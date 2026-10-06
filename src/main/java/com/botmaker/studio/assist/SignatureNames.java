package com.botmaker.studio.assist;

import com.botmaker.studio.palette.BotType;
import com.botmaker.studio.palette.FunctionDraft;
import com.botmaker.studio.palette.SignatureType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
        String given = name == null ? "" : name.strip();
        if (given.isEmpty() || given.equals("void")) {
            if (returned) return SignatureType.of(BotType.NOTHING);
            throw new IllegalArgumentException("A parameter needs a type.");
        }
        boolean list = given.startsWith("List<") && given.endsWith(">");
        String element = list ? given.substring(5, given.length() - 1).strip() : given;
        BotType type = find(element).orElseThrow(() -> new IllegalArgumentException("Studio offers no type "
                + element + ". The types are " + offered() + ", each alone or as List<…>."));
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
        return params.stream().map(each -> {
            if (!(each instanceof Map<?, ?> param)) {
                throw new IllegalArgumentException("A parameter is {\"name\": …, \"type\": …}, not " + each + ".");
            }
            String name = String.valueOf(param.get("name") == null ? "" : param.get("name")).strip();
            FunctionDraft.parameterNameProblem(name, List.of()).ifPresent(problem -> {
                throw new IllegalArgumentException(problem);
            });
            SignatureType type = type(String.valueOf(param.get("type") == null ? "" : param.get("type")), false);
            int origin = existing.indexOf(name);
            return new FunctionDraft.Parameter(name, type, origin < 0 ? FunctionDraft.Parameter.NEW : origin);
        }).toList();
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
