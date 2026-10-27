package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where each plugin's holder is in the bot, found by the plugin and the class name together, never by the class
 * name alone (2026-10-21): two plugins may each hold a {@code Pictures}, and a bot may have a {@code Tags} of its
 * own, so a simple name found somewhere is no plugin's holder by itself.
 *
 * <p>A holder is the top-level class {@code Name} in {@code plugins/<segment>/}, as {@link ManagedHolders} writes
 * it, or else a class of that name anywhere that carries the plugin's mark of a value it holds: a holder the user
 * moved. The written place wins over a moved one.
 */
public final class HolderFiles {

    private HolderFiles() {
    }

    /** One plugin's holder: its package segment ({@link ManagedHolders#segment}) and its class name. */
    public record Key(String segment, String name) {

        /** The holder {@code name} of the plugin {@code plugin}, or {@code null} when it spells no package. */
        public static Key of(PluginCoordinate plugin, String name) {
            String segment = ManagedHolders.segment(plugin);
            return segment == null || name == null ? null : new Key(segment, name);
        }
    }

    /**
     * Every holder of {@code byPlugin} the bot declares, and its file.
     *
     * @param units    each bot file and its tree
     * @param byPlugin each plugin's coordinate and every value it declares
     */
    public static Map<Key, Path> find(Map<Path, CompilationUnit> units,
                                      Map<PluginCoordinate, List<ManagedValue<?>>> byPlugin) {
        Map<Key, Path> written = new LinkedHashMap<>();
        Map<Key, Path> moved = new HashMap<>();
        units.forEach((file, unit) -> {
            if (unit == null) return;
            String segment = writtenSegment(file);
            for (Object each : unit.types()) {
                if (!(each instanceof AbstractTypeDeclaration type)) continue;
                String name = type.getName().getIdentifier();
                if (segment != null) written.putIfAbsent(new Key(segment, name), file);
                byPlugin.forEach((plugin, declared) -> {
                    Key key = Key.of(plugin, name);
                    if (key != null && !moved.containsKey(key) && marksHeldValue(type, name, declared)) {
                        moved.put(key, file);
                    }
                });
            }
        });
        moved.forEach(written::putIfAbsent);
        return Map.copyOf(written);
    }

    /** The segment of {@code file} when it lies in {@code plugins/<segment>/}, else {@code null}. */
    private static String writtenSegment(Path file) {
        Path folder = file == null ? null : file.getParent();
        Path plugins = folder == null ? null : folder.getParent();
        return plugins != null && plugins.getFileName() != null && plugins.getFileName().toString().equals("plugins")
                ? folder.getFileName().toString() : null;
    }

    /** Whether {@code type}, or one of its members, carries the mark of a value {@code declared} holds in {@code name}. */
    private static boolean marksHeldValue(AbstractTypeDeclaration type, String name, List<ManagedValue<?>> declared) {
        List<ManagedValue<?>> held = declared.stream().filter(v -> v != null && name.equals(v.holder())).toList();
        if (held.isEmpty()) return false;
        Set<String> ids = held.stream().map(ManagedValue::id).collect(Collectors.toSet());
        if (ids.contains(ManagedIds.idOf(ManagedIds.on(type, held), held))) return true;
        List<Object> members = new ArrayList<>(type.bodyDeclarations());
        if (type instanceof EnumDeclaration declaration) members.addAll(declaration.enumConstants());
        for (Object member : members) {
            if (member instanceof BodyDeclaration body && ids.contains(ManagedIds.idOf(ManagedIds.on(body, held), held))) {
                return true;
            }
        }
        return false;
    }
}
