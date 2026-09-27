package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.Set;

/**
 * Renaming a file from the explorer (2026-09-26): a {@code .java} file is its top-level class, so the class is
 * renamed with it, and every name in the bot bound to that class — an import, a field's type, a {@code new},
 * a static call's qualifier, the constructors — is rewritten with it.
 *
 * <p>It is {@link Refactor#rename} on the class's name since 2026-09-27, which moves the file with a public
 * top-level class and refuses a plan that would not compile. What is left here is what only a file rename
 * has to check: that the new file does not exist already, and that the file declares the class its name says.
 */
public final class TypeRename {

    private TypeRename() {}

    /**
     * Plans renaming {@code file}'s class to {@code newName} ({@code .java} optional).
     *
     * @param existing the paths that exist on disk, so the new name cannot land on one
     */
    public static Refactor.Outcome plan(BotIndex index, Path file, String newName, Set<Path> existing) {
        String name = newName == null ? "" : newName.strip().replaceFirst("\\.java$", "");
        Path at = file.toAbsolutePath().normalize();
        String oldName = at.getFileName().toString().replaceFirst("\\.java$", "");
        Path to = at.resolveSibling(name + ".java");
        if (!name.equals(oldName) && (index.sources().containsKey(to) || existing.contains(to))) {
            return new Refactor.Refused(to.getFileName() + " already exists.");
        }
        Integer start = index.read(units -> startOf(units.get(at), oldName));
        if (start == null) return new Refactor.Refused(file.getFileName() + " declares no class named " + oldName + ".");
        Refactor.Outcome outcome = Refactor.rename(index, at, start, name);
        // The file goes with its class even when javac would not insist — a class that is not public.
        if (outcome instanceof Refactor.Planned plan && plan.moves().isEmpty()) {
            return new Refactor.Planned(plan.summary(), plan.rewrites(), java.util.Map.of(at, to));
        }
        return outcome;
    }

    private static Integer startOf(CompilationUnit unit, String name) {
        if (unit == null) return null;
        for (Object type : unit.types()) {
            if (type instanceof AbstractTypeDeclaration t && t.getName().getIdentifier().equals(name)) {
                return t.getName().getStartPosition();
            }
        }
        return null;
    }
}
