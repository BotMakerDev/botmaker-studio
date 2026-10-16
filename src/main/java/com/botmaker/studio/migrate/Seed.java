package com.botmaker.studio.migrate;

import com.botmaker.plugin.api.meta.Migrate;
import com.botmaker.plugin.api.meta.Refactor;
import com.botmaker.studio.parser.ImportManager;
import com.botmaker.studio.parser.guard.CompileGuard.Problem;
import com.botmaker.studio.parser.helpers.AstRewriteHelper;
import com.botmaker.studio.parser.helpers.SourceFormatter;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.project.source.BotIndex;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;

import javax.lang.model.SourceVersion;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The skeleton of a rule for an old element no rule names yet (2026-10-16): the element's own signature, and a
 * body that compiles and stands for nothing — the type's default value, the receiver itself for a method that
 * gives its own owner back, nothing at all for one that returns nothing — marked {@code @Refactor} for the author
 * to write. Studio never guesses what replaces an element; the author writes it over the default.
 *
 * <p>An element whose signature names a type the new jars lack is not seeded: no rule can be written for it until
 * a {@code TYPE} rule says what that type became. Neither is a whole type: no default stands for a type.
 */
public final class Seed {

    /** What a seeded rule's {@code @Refactor} says, and how the author tells it from one they wrote. */
    public static final String MARK = "write the replacement";

    /** The package of the one source that asks which of the seeds' types the new jars still have. */
    private static final String PROBE_PACKAGE = "botmaker_migrate_seed";

    private Seed() {}

    /**
     * The rules seeded, and the elements that could not be.
     *
     * @param source   the rules' file with every seed in it, or {@code null} when nothing was seeded
     * @param seeded   each element a rule was seeded for
     * @param unseeded each element none could be, and why
     */
    record Seeded(String source, List<OldElement> seeded, Map<OldElement, String> unseeded) {}

    /**
     * {@code elements}' rules, written into the class of {@code file} — the bot's {@code Migrations}, created in
     * {@code mainPackage} when the bot has none.
     */
    static Seeded of(BotIndex now, List<OldElement> elements, Path file, String mainPackage) {
        Map<OldElement, String> unseeded = new LinkedHashMap<>();
        Set<String> gone = gone(now, elements);
        String source = now.sources().getOrDefault(file, skeleton(mainPackage));
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration holder = unit == null ? null : holder(unit, file);
        if (holder == null) {
            elements.forEach(element -> unseeded.put(element, "the bot's " + file.getFileName() + " has no class"
                    + " to write its rule into"));
            return new Seeded(null, List.of(), unseeded);
        }
        Map<String, String> imported = imported(unit);
        Set<String> taken = new HashSet<>();
        for (MethodDeclaration method : holder.getMethods()) taken.add(signature(method));

        ASTRewrite rewrite = ASTRewrite.create(unit.getAST());
        ListRewrite body = rewrite.getListRewrite(holder, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
        Set<String> imports = new LinkedHashSet<>();
        List<OldElement> seeded = new ArrayList<>();
        for (OldElement element : elements) {
            String why = unseedable(element, gone);
            if (why != null) {
                unseeded.put(element, why);
                continue;
            }
            // The seed's own imports, kept only once it is written: a refused one leaves no import behind.
            Map<String, String> names = new HashMap<>(imported);
            Set<String> needs = new LinkedHashSet<>();
            Writer writer = new Writer(names, needs);
            String method = writer.method(element, gone);
            if (!taken.add(writer.signature)) {
                unseeded.put(element, "the bot's " + holder.getName().getIdentifier() + " already has a method "
                        + writer.signature + ", and a rule is named by its old element");
                continue;
            }
            imported.putAll(names);
            imports.addAll(needs);
            body.insertLast(rewrite.createStringPlaceholder(method, ASTNode.METHOD_DECLARATION), null);
            seeded.add(element);
        }
        if (seeded.isEmpty()) return new Seeded(null, List.of(), unseeded);
        imports.add(Migrate.class.getName());
        imports.add(Refactor.class.getName());
        for (String each : imports) ImportManager.addImport(unit, rewrite, each);
        String written = SourceFormatter.formatChanged(source, AstRewriteHelper.applyRewrite(rewrite, source));
        return new Seeded(written, List.copyOf(seeded), unseeded);
    }

    /** Why no rule can be seeded for {@code element}, or {@code null}. */
    private static String unseedable(OldElement element, Set<String> gone) {
        if (element.kind() == Migrate.Kind.TYPE) {
            return "a whole type has no default; write a TYPE rule saying what " + element.display() + " became";
        }
        if (element.instance() && gone.contains(element.owner())) {
            return "its owner " + OldElement.simple(element.owner()) + " is gone, so no rule can take it as the receiver";
        }
        for (String param : element.params()) {
            if (gone.contains(base(param))) {
                return "it takes a " + OldElement.simple(param) + ", which the new API no longer has; write a TYPE"
                        + " rule for it first";
            }
        }
        return null;
    }

    /** One seed's text, and the imports it needs. */
    private static final class Writer {

        private final Map<String, String> imported;
        private final Set<String> imports;
        private String signature = "";

        Writer(Map<String, String> imported, Set<String> imports) {
            this.imported = imported;
            this.imports = imports;
        }

        String method(OldElement element, Set<String> gone) {
            boolean ownerGone = gone.contains(element.owner());
            String owner = ownerGone ? null : name(element.owner());
            String kind = element.kind() == Migrate.Kind.METHOD ? "" : ", kind = Migrate.Kind." + element.kind().name();
            String mark = owner == null
                    ? "@Migrate(name = \"" + element.owner() + "\"" + kind + ")"
                    : kind.isEmpty() ? "@Migrate(" + owner + ".class)" : "@Migrate(value = " + owner + ".class" + kind
                    + ")";

            List<String> params = new ArrayList<>();
            List<String> erased = new ArrayList<>();
            Set<String> names = new HashSet<>();
            if (element.instance()) {
                params.add("@Migrate.Self " + owner + " self");
                erased.add(element.owner());
                names.add("self");
            }
            for (String param : element.params()) {
                params.add(name(param) + " " + free(param, names));
                erased.add(param);
            }

            String returns = element.kind() == Migrate.Kind.CONSTRUCTOR ? element.owner() : element.type();
            boolean nothing = "void".equals(returns);
            String body;
            String type;
            if (nothing) {
                type = "void";
                body = "";
            } else if (element.instance() && returns.equals(element.owner())) {
                type = owner;
                body = "return self;";
            } else if (gone.contains(base(returns))) {
                type = "Object";
                body = "return null;";
            } else {
                type = name(returns);
                body = "return " + CallMigrator.literalDefaultText(type) + ";";
            }
            String method = element.kind() == Migrate.Kind.CONSTRUCTOR ? constructorName(element) : element.name();
            signature = signature(method, erased.stream().map(OldElement::simple).toList());
            return "@Refactor(\"" + MARK + "\")\n" + mark + "\nstatic " + type + " " + method + "("
                    + String.join(", ", params) + ") {" + (body.isEmpty() ? "" : "\n    " + body + "\n") + "}";
        }

        /**
         * {@code qualified} as the seed writes it: its simple name, imported, unless the file already imports
         * another type by that name — then whole, as Java text seeded into a file it cannot be imported into.
         */
        private String name(String qualified) {
            String base = base(qualified);
            String dims = qualified.substring(base.length());
            if (!base.contains(".")) return qualified;
            String simple = OldElement.simple(base);
            if (base.equals("java.lang." + simple)) return simple + dims;
            String held = imported.putIfAbsent(simple, base);
            if (held != null && !held.equals(base)) return base + dims;
            imports.add(base);
            return simple + dims;
        }
    }

    /** A parameter name for a {@code type}: its simple name, lower-cased first, numbered past those taken. */
    private static String free(String type, Set<String> taken) {
        String simple = OldElement.simple(base(type));
        String name = Character.toLowerCase(simple.charAt(0)) + simple.substring(1)
                + (type.endsWith("[]") ? "s" : "");
        if (!SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name) || simple.equals(name)) name = "value";
        String unique = name;
        for (int n = 2; !taken.add(unique); n++) unique = name + n;
        return unique;
    }

    /** {@code new Rect(…)}'s rule name: {@code rect}. */
    private static String constructorName(OldElement element) {
        String simple = OldElement.simple(element.owner());
        String name = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        return SourceVersion.isKeyword(name) ? "create" + simple : name;
    }

    /** {@code type} without its array dimensions. */
    private static String base(String type) {
        int bracket = type.indexOf('[');
        return bracket < 0 ? type : type.substring(0, bracket);
    }

    /** A method's name and its parameters' simple types, for telling a seed from a rule already there. */
    private static String signature(MethodDeclaration method) {
        List<String> types = new ArrayList<>();
        for (Object each : method.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) each;
            String type = parameter.getType().toString().replaceAll("<.*>", "");
            types.add(OldElement.simple(type) + "[]".repeat(parameter.getExtraDimensions())
                    + (parameter.isVarargs() ? "[]" : ""));
        }
        return signature(method.getName().getIdentifier(), types);
    }

    private static String signature(String name, List<String> types) {
        return name + "(" + String.join(", ", types) + ")";
    }

    /** Each simple name {@code unit} imports, and the type it imports by it. */
    private static Map<String, String> imported(CompilationUnit unit) {
        Map<String, String> out = new HashMap<>();
        for (Object each : unit.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (declaration.isStatic() || declaration.isOnDemand()) continue;
            String name = declaration.getName().getFullyQualifiedName();
            out.put(OldElement.simple(name), name);
        }
        return out;
    }

    /** The top-level class of {@code unit} named as {@code file} is. */
    private static TypeDeclaration holder(CompilationUnit unit, Path file) {
        String name = file.getFileName().toString().replaceFirst("\\.java$", "");
        for (Object each : unit.types()) {
            if (each instanceof TypeDeclaration type && !type.isInterface()
                    && type.getName().getIdentifier().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * The types {@code elements}' signatures name that the new jars lack: one source declaring a field of each,
     * parsed against the bot's classpath, and every field that does not compile.
     */
    private static Set<String> gone(BotIndex now, List<OldElement> elements) {
        List<String> types = new ArrayList<>();
        for (OldElement element : elements) {
            Set<String> named = new LinkedHashSet<>();
            named.add(element.owner());
            named.add(base(element.type()));
            element.params().forEach(param -> named.add(base(param)));
            for (String type : named) if (type.contains(".") && !types.contains(type)) types.add(type);
        }
        if (types.isEmpty()) return Set.of();
        Path root = now.parser().sourceRoot();
        Path probe = (root == null ? Path.of("") : root).resolve(PROBE_PACKAGE).resolve("Probe.java")
                .toAbsolutePath().normalize();
        StringBuilder text = new StringBuilder("package " + PROBE_PACKAGE + ";\n\nclass Probe {\n");
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            String line = "    " + types.get(i) + " f" + i + ";\n";
            ranges.add(new int[] {text.length(), text.length() + line.length()});
            text.append(line);
        }
        text.append("}\n");
        // The probe alone, against the bot's own classpath.
        List<Problem> problems = now.replacing(Map.of(probe, text.toString())).errors().getOrDefault(probe, List.of());
        Set<String> out = new HashSet<>();
        for (Problem problem : problems) {
            for (int i = 0; i < ranges.size(); i++) {
                if (ranges.get(i)[0] <= problem.offset() && problem.offset() < ranges.get(i)[1]) out.add(types.get(i));
            }
        }
        return out;
    }

    /**
     * A new {@code Migrations} class in {@code mainPackage}: the class, its doc comment saying what it is for, and
     * nothing else.
     */
    @SuppressWarnings("unchecked")
    public static String skeleton(String mainPackage) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        CompilationUnit unit = ast.newCompilationUnit();
        PackageDeclaration declaration = ast.newPackageDeclaration();
        declaration.setName(ast.newName(mainPackage));
        unit.setPackage(declaration);
        TypeDeclaration type = ast.newTypeDeclaration();
        type.setName(ast.newSimpleName(Migration.CLASS));
        type.modifiers().addAll(ast.newModifiers(Modifier.PUBLIC | Modifier.FINAL));
        Javadoc javadoc = ast.newJavadoc();
        for (String line : List.of("How this bot moves off the API its plugins changed (Review ▸ Migrate…).",
                "<p>Each @Migrate method names one old method, constructor, field or type by its own signature, and its",
                "body is what each use becomes. BotMaker reads it and never runs it; the bot never calls it. Delete a",
                "method, or this class, once nothing uses its old element.")) {
            TagElement tag = ast.newTagElement();
            TextElement text = ast.newTextElement();
            text.setText(line);
            tag.fragments().add(text);
            javadoc.tags().add(tag);
        }
        type.setJavadoc(javadoc);
        MethodDeclaration constructor = ast.newMethodDeclaration();
        constructor.setConstructor(true);
        constructor.setName(ast.newSimpleName(Migration.CLASS));
        constructor.modifiers().addAll(ast.newModifiers(Modifier.PRIVATE));
        constructor.setBody(ast.newBlock());
        type.bodyDeclarations().add(constructor);
        unit.types().add(type);
        StringBuilder out = new StringBuilder();
        SourceFormatter.format(unit.toString()).lines().forEach(line -> out.append(line.stripTrailing()).append('\n'));
        return out.toString();
    }
}
