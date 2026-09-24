package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.parser.helpers.JavaSnippets;
import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.services.CodeEditorService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.types.PrimitiveKind;
import com.botmaker.studio.types.ResolvedType;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Type;
import com.botmaker.studio.ui.render.components.TypeChip;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The one menu a type is chosen from — a cast's, a check's, a catch's, a declaration's, a parameter's. A search
 * box on top; with no query, the types in sections by whose they are: primitives, this project's classes, the
 * plugins' types, the common Java ones, and every other library class one level down. A query that matches
 * nothing but is a type Java can read is offered as itself ("Use …"), so a type the lists do not hold is still
 * one step away, and still checked.
 *
 * <p>What fits is the caller's {@link Filter}: a catch offers only what can be thrown, a check only what is not
 * primitive. The filter decides what is <em>offered</em>; the edit it leads to is still compiled before it is
 * written, so a checked exception the body never throws is refused with javac's reason rather than hidden.
 */
public final class TypePicker {

    /** What a place accepts as a type. */
    public enum Filter {
        /** Any type: a cast, a declaration, an array's element. */
        ANY,
        /** No primitive: a type check, a type argument. */
        REFERENCE,
        /** Something a {@code catch} can name. */
        THROWABLE;

        public boolean accepts(ResolvedType type) {
            if (type == null) return false;
            return switch (this) {
                case ANY -> true;
                case REFERENCE -> !type.isPrimitive() && !type.isVoid();
                case THROWABLE -> !type.isPrimitive() && isThrowable(type);
            };
        }

        boolean offersPrimitives() {
            return this == ANY;
        }
    }

    /**
     * How a pick is spelled. {@code arrayDims} offers adding and removing {@code []} and keeps the current
     * depth on every pick; {@code typeArguments} writes a generic class with {@code Object} for each of its
     * parameters ({@code Map<Object, Object>}), each then a part of the chip to pick again — off where Java
     * allows only the raw name (a class literal, a check, an array creation, a catch).
     */
    public record Options(Filter filter, boolean allowVoid, boolean arrayDims, boolean typeArguments) {
        public static Options of(Filter filter) {
            return new Options(filter, false, true, false);
        }

        public Options withVoid() {
            return new Options(filter, true, arrayDims, typeArguments);
        }

        public Options withoutArrays() {
            return new Options(filter, allowVoid, false, typeArguments);
        }

        public Options withTypeArguments() {
            return new Options(filter, allowVoid, arrayDims, true);
        }
    }

    /** A pick: the type, and the Java that names it ({@code List<Object>}, {@code int[][]}). */
    public record Choice(ResolvedType type, String text) {}

    /** The common Java types, offered by class so a rename in the JDK is this build's problem. */
    private static final List<Class<?>> COMMON_JAVA = List.of(
            String.class, Object.class, Integer.class, Double.class, Boolean.class, Long.class, Character.class,
            List.class, java.util.ArrayList.class, Map.class, java.util.HashMap.class, Set.class,
            java.util.HashSet.class, Optional.class, Runnable.class);

    /** The exceptions a catch reaches for first. */
    private static final List<Class<?>> COMMON_EXCEPTIONS = List.of(
            Exception.class, RuntimeException.class, IllegalArgumentException.class, IllegalStateException.class,
            InterruptedException.class, IOException.class, NullPointerException.class,
            IndexOutOfBoundsException.class, ArithmeticException.class, NumberFormatException.class,
            UnsupportedOperationException.class, Error.class, Throwable.class);

    private TypePicker() {}

    /** Opens the picker under {@code anchor}. */
    public static void show(Node anchor, Options options, ResolvedType current, CodeEditorService context,
                            ASTNode contextNode, Consumer<Choice> onPick) {
        create(options, current, context, contextNode, onPick).show(anchor, Side.BOTTOM, 0, 0);
    }

    /**
     * {@code root} as a {@link TypeChip} whose parts each open this picker, and whose pick is handed to
     * {@code commit} as the whole type respelled ({@link TypeChip#replace}). A type argument is picked from
     * reference types only, whatever {@code options} says, since {@code List<int>} is not Java. Read-only — the
     * same chip with nothing to click — when {@code editable} is false.
     */
    public static Node chip(Type root, Options options, boolean editable, CodeEditorService context,
                            ASTNode contextNode, Consumer<String> commit) {
        if (!editable || root == null) return TypeChip.of(root);
        Node[] chip = new Node[1];
        chip[0] = TypeChip.of(root, part -> {
            Options forPart = part.typeArgument()
                    ? new Options(Filter.REFERENCE, false, options.arrayDims(), options.typeArguments())
                    : options;
            show(chip[0], forPart, ProjectAnalyzer.resolveType(part.type()), context, contextNode, choice -> {
                String text = TypeChip.replace(root, part.type(), choice.text());
                if (text != null) commit.accept(text);
            });
        });
        return chip[0];
    }

    /** The picker, not yet shown — what a test reads. */
    public static ContextMenu create(Options options, ResolvedType current, CodeEditorService context,
                                     ASTNode contextNode, Consumer<Choice> onPick) {
        ContextMenu menu = MenuTracker.track(new ContextMenu());
        Sections sections = Sections.of(options, context, contextNode);
        MenuBuilders.withSearch(menu, "Search types…",
                (m, query) -> rebuild(m, query, options, current, sections, onPick));
        return menu;
    }

    /** Everything offered, grouped once per open: a search only filters it. */
    private record Sections(List<ResolvedType> primitives, List<ResolvedType> project,
                            List<ResolvedType> plugins, List<ResolvedType> java, List<ResolvedType> libraries) {

        static Sections of(Options options, CodeEditorService context, ASTNode contextNode) {
            Filter filter = options.filter();
            Set<String> seen = new HashSet<>();
            List<ResolvedType> primitives = new ArrayList<>();
            if (filter.offersPrimitives()) {
                for (PrimitiveKind kind : PrimitiveKind.values()) {
                    if (kind == PrimitiveKind.VOID && !options.allowVoid()) continue;
                    if (seen.add(kind.keyword())) primitives.add(ResolvedType.primitive(kind));
                }
            }

            Set<String> pluginNames = new HashSet<>(PluginHost.grammar().names());
            List<ResolvedType> project = new ArrayList<>();
            List<ResolvedType> libraries = new ArrayList<>();
            ProjectAnalyzer analyzer = context == null ? null : context.getProjectAnalyzer();
            List<ResolvedType> available = analyzer == null ? List.of() : analyzer.getAvailableTypes(contextNode);
            for (ResolvedType type : available) {
                if (type.isPrimitive() || type.isVoid() || type.isArray() || pluginNames.contains(type.qualifiedName())
                        || isJavaLang(type) || !filter.accepts(type)) {
                    continue;
                }
                boolean own = type instanceof ResolvedType.Named
                        || (type instanceof ResolvedType.Bound bound && bound.binding().isFromSource());
                if (!seen.add(type.qualifiedName())) continue;
                (own ? project : libraries).add(type);
            }

            List<ResolvedType> plugins = new ArrayList<>();
            for (String name : pluginNames) {
                ResolvedType type = ResolvedType.named(name);
                if (filter.accepts(type) && seen.add(name)) plugins.add(type);
            }

            List<ResolvedType> java = new ArrayList<>();
            for (Class<?> type : filter == Filter.THROWABLE ? COMMON_EXCEPTIONS : COMMON_JAVA) {
                ResolvedType resolved = ResolvedType.of(type);
                if (filter.accepts(resolved) && seen.add(type.getName())) java.add(resolved);
            }
            libraries.removeIf(t -> java.stream().anyMatch(j -> j.qualifiedName().equals(t.qualifiedName())));

            Comparator<ResolvedType> byName = Comparator.comparing(ResolvedType::simpleName, String.CASE_INSENSITIVE_ORDER);
            project.sort(byName);
            plugins.sort(byName);
            libraries.sort(byName);
            return new Sections(primitives, project, plugins, java, libraries);
        }

        List<ResolvedType> all() {
            List<ResolvedType> out = new ArrayList<>(primitives);
            out.addAll(project);
            out.addAll(plugins);
            out.addAll(java);
            out.addAll(libraries);
            return out;
        }
    }

    private static void rebuild(ContextMenu menu, String query, Options options, ResolvedType current,
                                Sections sections, Consumer<Choice> onPick) {
        MenuBuilders.clearBody(menu);
        String q = query == null ? "" : query.strip();
        int dims = options.arrayDims() && current != null ? current.arrayDimensions() : 0;
        ResolvedType leaf = current == null ? null : current.leafType();

        if (!q.isEmpty()) {
            String lower = q.toLowerCase();
            List<ResolvedType> matches = sections.all().stream()
                    .filter(t -> t.simpleName().toLowerCase().contains(lower))
                    .toList();
            menu.getItems().add(MenuRows.resultCount(matches.size()));
            for (ResolvedType type : matches) menu.getItems().add(item(type, dims, options, onPick));
            boolean exact = matches.stream().anyMatch(t -> t.simpleName().equals(q) || t.qualifiedName().equals(q));
            if (!exact && JavaSnippets.type(q) != null) {
                MenuItem typed = new MenuItem("Use “" + q + "”");
                typed.getStyleClass().add("type-picker-typed");
                typed.setOnAction(e -> onPick.accept(new Choice(ResolvedType.named(q), q)));
                menu.getItems().add(typed);
            } else if (matches.isEmpty()) {
                menu.getItems().add(MenuBuilders.disabledItem("Not a type Java can read"));
            }
            return;
        }

        if (options.arrayDims() && leaf != null && !leaf.isVoid()) {
            MenuItem add = new MenuItem("Add a dimension  [ ]");
            add.setOnAction(e -> onPick.accept(choice(leaf, dims + 1, options)));
            menu.getItems().add(add);
            if (dims > 0) {
                MenuItem remove = new MenuItem("Remove a dimension  [ ]");
                remove.setOnAction(e -> onPick.accept(choice(leaf, dims - 1, options)));
                menu.getItems().add(remove);
            }
        }
        section(menu, "PRIMITIVES", sections.primitives(), dims, options, onPick);
        section(menu, "THIS PROJECT", sections.project(), dims, options, onPick);
        section(menu, "FROM PLUGINS", sections.plugins(), dims, options, onPick);
        section(menu, "JAVA", sections.java(), dims, options, onPick);
        if (!sections.libraries().isEmpty()) {
            if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
            Menu libraries = new Menu("Libraries (" + sections.libraries().size() + ")");
            for (ResolvedType type : sections.libraries()) libraries.getItems().add(item(type, dims, options, onPick));
            menu.getItems().add(libraries);
        }
        if (menu.getItems().size() == 1) menu.getItems().add(MenuBuilders.disabledItem("(No types fit here)"));
    }

    private static void section(ContextMenu menu, String title, List<ResolvedType> types, int dims, Options options,
                                Consumer<Choice> onPick) {
        if (types.isEmpty()) return;
        if (menu.getItems().size() > 1) menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(MenuBuilders.sectionHeader(title));
        for (ResolvedType type : types) menu.getItems().add(item(type, dims, options, onPick));
    }

    private static MenuItem item(ResolvedType type, int dims, Options options, Consumer<Choice> onPick) {
        MenuItem item = new MenuItem(type.simpleName());
        item.setOnAction(e -> onPick.accept(choice(type, dims, options)));
        return item;
    }

    /** {@code type} at {@code dims} dimensions, spelled with {@code Object} for each type parameter when asked. */
    static Choice choice(ResolvedType type, int dims, Options options) {
        ResolvedType leaf = type.leafType();
        // A generic binding's name carries its parameters (`Box<T>`), which are no type here.
        String name = leaf.simpleName();
        int generic = name.indexOf('<');
        StringBuilder text = new StringBuilder(generic > 0 ? name.substring(0, generic) : name);
        int arity = options.typeArguments() ? typeParameterCount(leaf) : 0;
        if (arity > 0) text.append('<').append(String.join(", ", java.util.Collections.nCopies(arity, "Object"))).append('>');
        text.append("[]".repeat(Math.max(0, dims)));
        return new Choice(dims > 0 ? leaf.asArray(dims) : leaf, text.toString());
    }

    /** How many type parameters {@code type} declares: {@code 1} for {@code List}, {@code 0} for {@code String}. */
    static int typeParameterCount(ResolvedType type) {
        return switch (type) {
            case ResolvedType.Bound bound -> bound.binding().getTypeDeclaration().getTypeParameters().length;
            case ResolvedType.FromIndex index -> index.info().getTypeSignature() == null
                    ? 0 : index.info().getTypeSignature().getTypeParameters().size();
            case ResolvedType.Named named -> classOf(named.qualifiedName()).map(c -> c.getTypeParameters().length).orElse(0);
            case ResolvedType.Primitive primitive -> 0;
        };
    }

    private static boolean isJavaLang(ResolvedType type) {
        return type.qualifiedName().startsWith("java.lang.") && type.qualifiedName().indexOf('.', 10) < 0;
    }

    private static boolean isThrowable(ResolvedType type) {
        return switch (type) {
            case ResolvedType.Bound bound -> {
                for (ITypeBinding at = bound.binding(); at != null; at = at.getSuperclass()) {
                    if ("java.lang.Throwable".equals(at.getErasure().getQualifiedName())) yield true;
                }
                yield false;
            }
            case ResolvedType.FromIndex index -> "java.lang.Throwable".equals(index.info().getName())
                    || index.info().extendsSuperclass("java.lang.Throwable");
            case ResolvedType.Named named -> classOf(named.qualifiedName()).map(Throwable.class::isAssignableFrom).orElse(false);
            case ResolvedType.Primitive primitive -> false;
        };
    }

    /** The class named {@code qualifiedName} off the plugins' loader, which sees the JDK too; empty when none. */
    private static Optional<Class<?>> classOf(String qualifiedName) {
        try {
            return Optional.of(Class.forName(qualifiedName, false, PluginHost.classLoader()));
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return Optional.empty();
        }
    }
}
