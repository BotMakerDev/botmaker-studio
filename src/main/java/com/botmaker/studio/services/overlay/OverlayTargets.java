package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.TypeMethodReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the overlay editor's blocks go: every method of the bot passed by method reference where a plugin's
 * declared target type is expected ({@link OverlayPart#targets()}).
 *
 * <p>Read off the compiler's bindings ({@link BotIndex}), never off text: {@code Flow.activity(COLLECT,
 * Collect::body, …)} is a target because javac types {@code Collect::body} as the SDK's {@code ActivityBody}
 * there, whatever the call around it is called. A lambda names no method and is not a target; a reference to a
 * library's method has no file in the bot and is left out, since there is nothing to open.
 *
 * <p>Replaces {@code ManagedTargets} (2026-10-06), which offered every method reference inside a
 * managed value and so could not tell an activity's body from any other reference a value held.
 */
public final class OverlayTargets {

    /**
     * One method blocks can go into.
     *
     * @param group     the heading its chip sits under, as the plugin declared it
     * @param label     the chip: the declaring class's simple name, or {@code Class.method} when the group has
     *                  two methods of one class
     * @param className the declaring class, qualified
     * @param method    the method's name
     * @param file      the bot's file declaring it
     */
    public record Target(String group, String label, String className, String method, Path file) {

        /** {@code com.bot.Collect#body}: how a target is remembered across openings. */
        public String key() {
            return className + "#" + method;
        }
    }

    private OverlayTargets() {}

    /**
     * The targets {@code types} find in {@code index}, grouped in declared order and, within a group, in the
     * order the walk meets them; each method once.
     */
    public static List<Target> find(BotIndex index, List<OverlayPart.TargetType> types) {
        if (index == null || types == null || types.isEmpty()) return List.of();
        Map<String, String> groupOf = new LinkedHashMap<>();
        for (OverlayPart.TargetType type : types) groupOf.putIfAbsent(type.typeName(), type.group());
        Map<String, List<Found>> byGroup = new LinkedHashMap<>();
        for (String group : groupOf.values()) byGroup.putIfAbsent(group, new ArrayList<>());
        index.read(units -> {
            Map<String, Boolean> seen = new HashMap<>();
            units.forEach((file, unit) -> unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(ExpressionMethodReference reference) {
                    add(reference);
                    return true;
                }

                @Override
                public boolean visit(TypeMethodReference reference) {
                    add(reference);
                    return true;
                }

                private void add(MethodReference reference) {
                    ITypeBinding expected = reference.resolveTypeBinding();
                    if (expected == null) return;
                    String group = groupOf.get(expected.getErasure().getBinaryName());
                    if (group == null) return;
                    IMethodBinding binding = reference.resolveMethodBinding();
                    if (binding == null) return;
                    IMethodBinding declared = binding.getMethodDeclaration();
                    Path declaring = declaring(units, declared.getKey());
                    if (declaring == null || seen.putIfAbsent(declared.getKey(), true) != null) return;
                    ITypeBinding owner = declared.getDeclaringClass().getErasure();
                    byGroup.get(group).add(new Found(owner.getQualifiedName(), owner.getName(),
                            declared.getName(), declaring));
                }
            }));
            return null;
        });
        List<Target> out = new ArrayList<>();
        byGroup.forEach((group, found) -> {
            Map<String, Integer> perClass = new HashMap<>();
            for (Found f : found) perClass.merge(f.className, 1, Integer::sum);
            for (Found f : found) {
                String label = perClass.get(f.className) > 1 ? f.simpleName + "." + f.method : f.simpleName;
                out.add(new Target(group, label, f.className, f.method, f.file));
            }
        });
        return List.copyOf(out);
    }

    private record Found(String className, String simpleName, String method, Path file) {}

    /** The file whose unit declares the binding {@code key}, or {@code null} when none of the bot's does. */
    private static Path declaring(Map<Path, CompilationUnit> units, String key) {
        for (Map.Entry<Path, CompilationUnit> entry : units.entrySet()) {
            if (entry.getValue().findDeclaringNode(key) != null) return entry.getKey();
        }
        return null;
    }
}
