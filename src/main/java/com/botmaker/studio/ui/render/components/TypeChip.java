package com.botmaker.studio.ui.render.components;

import com.botmaker.studio.parser.helpers.JavaSnippets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.IntersectionType;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.StructuralPropertyDescriptor;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.WildcardType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * A type drawn by its structure rather than as its spelling: {@code int[]} is an {@code int} chip with one
 * {@code [ ]} after it, {@code Map<String, List<int[]>>} a {@code Map} chip holding two nested chips. It
 * replaces the bold word and the free-text field every type on a block used to be, where {@code int[]} and
 * {@code Map<String, List<int[]>>} read as one run of punctuation.
 *
 * <p>With an {@code onPart} handler each named part is a click that reports <em>which</em> part: the element of
 * an array reports the array (so a pick keeps the brackets), the name of a generic type reports the whole
 * generic type, a type argument reports itself. What to do with the part is the caller's — usually
 * {@link com.botmaker.studio.ui.render.menu.TypePicker} over that part, then {@link #replace} to respell the
 * whole type with it.
 */
public final class TypeChip {

    /** One clickable part of a type, and whether it sits in a type argument, which cannot be primitive. */
    public record Part(Type type, boolean typeArgument) {}

    private TypeChip() {}

    /** {@code type}, read-only. */
    public static Node of(Type type) {
        return of(type, null);
    }

    /** {@code text} parsed as a type, read-only; a text Java cannot read as one is shown as written. */
    public static Node of(String text) {
        Type parsed = JavaSnippets.type(text);
        if (parsed != null) return of(parsed);
        HBox chip = frame();
        chip.getChildren().add(part(text == null || text.isBlank() ? "?" : text, "type-chip-class", null));
        return chip;
    }

    /** {@code type}, each named part a click reported to {@code onPart} (read-only when it is null). */
    public static Node of(Type type, Consumer<Part> onPart) {
        HBox chip = frame();
        if (type == null) {
            chip.getChildren().add(part("?", "type-chip-class", null));
        } else {
            chip.getChildren().addAll(parts(type, type, false, onPart));
        }
        if (onPart != null) chip.getStyleClass().add("type-chip-editable");
        return chip;
    }

    private static HBox frame() {
        HBox chip = new HBox(1);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().add("type-chip");
        return chip;
    }

    /**
     * The nodes for {@code type}. {@code reported} is the part a click on its name reports: itself, or the
     * array or generic type it is the element or the base of.
     */
    private static List<Node> parts(Type type, Type reported, boolean typeArgument, Consumer<Part> onPart) {
        Part self = new Part(reported, typeArgument);
        return switch (type) {
            case PrimitiveType primitive ->
                    List.of(part(primitive.getPrimitiveTypeCode().toString(), "type-chip-primitive", clickOf(self, onPart)));
            case ArrayType array -> {
                List<Node> out = new ArrayList<>(parts(array.getElementType(), reported, typeArgument, onPart));
                for (int i = 0; i < array.getDimensions(); i++) out.add(punctuation("[ ]", "type-chip-dimension"));
                yield out;
            }
            case ParameterizedType generic -> {
                List<Node> out = new ArrayList<>(parts(generic.getType(), reported, typeArgument, onPart));
                out.add(punctuation("‹", "type-chip-bracket"));
                List<?> arguments = generic.typeArguments();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i > 0) out.add(punctuation(",", "type-chip-bracket"));
                    Type argument = (Type) arguments.get(i);
                    HBox nested = frame();
                    nested.getStyleClass().add("type-chip-nested");
                    nested.getChildren().addAll(parts(argument, argument, true, onPart));
                    out.add(nested);
                }
                out.add(punctuation("›", "type-chip-bracket"));
                yield out;
            }
            case WildcardType wildcard -> {
                List<Node> out = new ArrayList<>();
                out.add(punctuation("?", "type-chip-bracket"));
                if (wildcard.getBound() != null) {
                    out.add(punctuation(wildcard.isUpperBound() ? "extends" : "super", "type-chip-keyword"));
                    out.addAll(parts(wildcard.getBound(), wildcard.getBound(), true, onPart));
                }
                yield out;
            }
            case UnionType union -> joined(union.types(), "|", typeArgument, onPart);
            case IntersectionType intersection -> joined(intersection.types(), "&", typeArgument, onPart);
            case SimpleType simple when simple.isVar() -> List.of(part("var", "type-chip-keyword", null));
            case SimpleType simple -> List.of(named(simple.getName().getFullyQualifiedName(), clickOf(self, onPart)));
            case QualifiedType qualified -> List.of(named(qualified.toString(), clickOf(self, onPart)));
            case NameQualifiedType qualified -> List.of(named(qualified.toString(), clickOf(self, onPart)));
            default -> List.of(part(type.toString(), "type-chip-class", clickOf(self, onPart)));
        };
    }

    /** Each alternative its own part, with {@code separator} between: a multi-catch reads {@code A | B}. */
    private static List<Node> joined(List<?> types, String separator, boolean typeArgument, Consumer<Part> onPart) {
        List<Node> out = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) out.add(punctuation(separator, "type-chip-bracket"));
            Type alternative = (Type) types.get(i);
            out.addAll(parts(alternative, alternative, typeArgument, onPart));
        }
        return out;
    }

    private static Runnable clickOf(Part part, Consumer<Part> onPart) {
        return onPart == null ? null : () -> onPart.accept(part);
    }

    /** A class by its simple name, the qualified one in a tooltip when the source wrote it qualified. */
    private static Label named(String written, Runnable onClick) {
        int dot = written.lastIndexOf('.');
        Label label = part(dot < 0 ? written : written.substring(dot + 1), "type-chip-class", onClick);
        if (dot >= 0) Tooltip.install(label, new Tooltip(written));
        return label;
    }

    private static Label part(String text, String styleClass, Runnable onClick) {
        Label label = new Label(text);
        label.getStyleClass().addAll("type-chip-part", styleClass);
        if (onClick != null) {
            label.getStyleClass().add("type-chip-clickable");
            label.setCursor(Cursor.HAND);
            label.setOnMouseClicked(e -> {
                e.consume();
                onClick.run();
            });
        }
        return label;
    }

    private static Label punctuation(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("type-chip-punctuation", styleClass);
        return label;
    }

    /**
     * {@code root} spelled with {@code part} replaced by the type {@code replacement} names, or {@code null}
     * when {@code replacement} is not a type or {@code part} is not under {@code root}. The replacement is
     * made on a copy, so the tree the canvas reads is never touched.
     */
    public static String replace(Type root, Type part, String replacement) {
        if (root == null || part == null) return null;
        if (part == root) return JavaSnippets.type(replacement) == null ? null : replacement.strip();
        Type parsed = JavaSnippets.type(replacement);
        if (parsed == null) return null;

        // The way down from root to part, as (property, list index or -1) steps.
        Deque<StructuralPropertyDescriptor> properties = new ArrayDeque<>();
        Deque<Integer> indices = new ArrayDeque<>();
        ASTNode at = part;
        while (at != root) {
            if (at == null) return null;
            StructuralPropertyDescriptor property = at.getLocationInParent();
            ASTNode parent = at.getParent();
            properties.push(property);
            indices.push(property.isChildListProperty()
                    ? ((List<?>) parent.getStructuralProperty(property)).indexOf(at) : -1);
            at = parent;
        }

        AST ast = AST.newAST(AST.getJLSLatest(), false);
        Type copy = (Type) ASTNode.copySubtree(ast, root);
        ASTNode walk = copy;
        while (properties.size() > 1) {
            StructuralPropertyDescriptor property = properties.pop();
            int index = indices.pop();
            Object child = walk.getStructuralProperty(property);
            walk = (ASTNode) (index >= 0 ? ((List<?>) child).get(index) : child);
        }
        StructuralPropertyDescriptor property = properties.pop();
        int index = indices.pop();
        Type fresh = (Type) ASTNode.copySubtree(ast, parsed);
        if (index >= 0) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) walk.getStructuralProperty(property);
            list.set(index, fresh);
        } else {
            walk.setStructuralProperty(property, fresh);
        }
        return copy.toString();
    }
}
