package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * One {@code @Migrate} method in the bot, read and resolved: which old element it names, and the body each use
 * of that element becomes.
 *
 * @param file          the file it is declared in
 * @param className     its class, as the bot writes it ({@code Migrations})
 * @param method        its name
 * @param start         the offset of its declaration, the same in every parse of the same text
 * @param kind          the element it names
 * @param owner         the annotation's type, qualified; empty when the annotation names none
 * @param params        its parameters' names, in order, the {@link Migrate.Self} receiver first when there is one
 * @param types         each parameter's type as written, a qualified erasure, in the same order
 * @param self          whether the first parameter is the receiver
 * @param body         the expression its one {@code return} returns, or its one statement for a {@code void} rule
 * @param statement     whether it returns nothing, so {@code body} is a statement
 * @param returns       its return type, qualified: a {@link Migrate.Kind#TYPE} rule's new type
 * @param imports       the types its body names, each a qualified name a file using the body imports
 * @param staticImports the static members its body names unqualified, each {@code a.B.member}
 * @param seeded        whether it is still the skeleton "Write rule" wrote: an open {@code @Refactor} mark, so it
 *                      is listed and never applied
 * @param element       the old element it resolves to, or {@code null} with a {@code problem}
 * @param problem       why it cannot be applied, or {@code null}
 */
public record Rule(Path file, String className, String method, int start, Migrate.Kind kind, String owner,
                   List<String> params, List<String> types, boolean self, String body, boolean statement,
                   String returns, Set<String> imports, Set<String> staticImports, boolean seeded,
                   OldElement element, String problem) {

    public Rule {
        params = List.copyOf(params);
        types = List.copyOf(types);
        imports = Set.copyOf(imports);
        staticImports = Set.copyOf(staticImports);
    }

    /** What a person reads it as: {@code Migrations.marker}. */
    public String qualified() {
        return className + "." + method;
    }

    /** Whether it is applied: resolved, without a problem, and written rather than seeded. */
    public boolean ready() {
        return element != null && problem == null && !seeded;
    }

    /** The same rule, refused for {@code why}. */
    Rule refused(String why) {
        return problem != null ? this : new Rule(file, className, method, start, kind, owner, params, types, self,
                body, statement, returns, imports, staticImports, seeded, element, why);
    }

    /** The same rule, resolved to {@code to}. */
    Rule resolved(OldElement to) {
        return new Rule(file, className, method, start, kind, owner, params, types, self, body, statement, returns,
                imports, staticImports, seeded, to, problem);
    }

    /** The parameters a site's own parts stand for: every one but the receiver. */
    List<String> parts() {
        return self ? params.subList(1, params.size()) : params;
    }

    /** Their types, in the same order. */
    List<String> partTypes() {
        return self ? types.subList(1, types.size()) : types;
    }
}
