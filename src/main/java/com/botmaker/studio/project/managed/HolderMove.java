package com.botmaker.studio.project.managed;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.project.PluginFiles;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Moving a plugin's holders written in an older folder into the one Studio writes now (2026-10-30, the user's
 * call): {@code plugins/sdk/Sdk.java} becomes {@code plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java}. The
 * holders are the plugin's by their mark wherever they are ({@link PluginFiles}), so the move is offered, never
 * needed.
 *
 * <p>Each file keeps its text but its package; every import, qualified name and same-package use in the bot
 * follows it. The plan is a {@link Refactor.Planned}, checked like a rename: one that would stop the bot
 * compiling — a holder that uses a class of the old package that does not move, say — is refused.
 */
public final class HolderMove {

    private HolderMove() {}

    /** One holder file to move: where it is, where it goes, and its class's old and new qualified names. */
    record Moved(Path from, Path to, String oldName, String newName) {

        String oldPackage() {
            return packageOf(oldName);
        }

        String newPackage() {
            return packageOf(newName);
        }
    }

    /** One text change: {@code length} characters at {@code start} become {@code text}. */
    private record Edit(int start, int length, String text) {}

    /**
     * The holders of {@code holders} outside their plugin's folder, and every file that names them, as one plan;
     * empty when every holder is in its folder already.
     */
    public static Optional<Refactor.Outcome> plan(BotIndex index, ProjectConfig config, List<PluginFiles.Holder> holders) {
        List<Moved> moved = moves(index, config, holders);
        if (moved.isEmpty()) return Optional.empty();
        for (Moved each : moved) {
            if (index.sources().containsKey(each.to()) || Files.exists(each.to())) {
                return Optional.of(new Refactor.Refused(relative(config, each.to()) + " exists already, so "
                        + relative(config, each.from()) + " was not moved."));
            }
        }
        Map<Path, String> rewrites = rewrites(index, moved);
        Map<Path, Path> moves = new LinkedHashMap<>();
        moved.forEach(each -> moves.put(each.from(), each.to()));
        Set<String> folders = new LinkedHashSet<>();
        moved.forEach(each -> folders.add(relative(config, each.to().getParent()) + "/"));
        Refactor.Planned plan = new Refactor.Planned("Moved " + moved.size() + (moved.size() == 1 ? " file" : " files")
                + " to " + String.join(", ", folders), rewrites, moves);
        return Optional.of(Refactor.checked(index, plan, "Moving the plugin files"));
    }

    /**
     * Whether any holder of {@code holders} is outside its folder, read from the sources without bindings — what
     * is asked before the whole bot is parsed and compiled to plan the move.
     *
     * @param sources each bot file and its text
     */
    public static boolean anyOutside(Map<Path, String> sources, List<PluginFiles.Holder> holders) {
        for (Map.Entry<Path, String> each : sources.entrySet()) {
            Optional<PluginFiles.Holder> owner = PluginFiles.owner(each.getKey(), each.getValue(), holders);
            if (owner.isPresent() && !owner.get().segment().equals(ManagedHolders.segmentOf(each.getKey()))) return true;
        }
        return false;
    }

    /** The banner's line offering {@code plan}: how many files, from which folders and to which. */
    public static String offer(ProjectConfig config, Refactor.Planned plan) {
        Set<String> from = new LinkedHashSet<>();
        Set<String> to = new LinkedHashSet<>();
        plan.moves().forEach((was, now) -> {
            from.add(relative(config, was.getParent()) + "/");
            to.add(relative(config, now.getParent()) + "/");
        });
        int count = plan.moves().size();
        return (count == 1 ? "A plugin file is" : count + " plugin files are") + " in " + String.join(", ", from)
                + ", not where Studio writes them now (" + String.join(", ", to) + "). They work where they are.";
    }

    /** The holders to move, each outside its plugin's folder. */
    static List<Moved> moves(BotIndex index, ProjectConfig config, List<PluginFiles.Holder> holders) {
        List<Moved> out = new ArrayList<>();
        index.read(units -> {
            units.forEach((path, unit) -> PluginFiles.owner(path, index.sources().get(path), holders).ifPresent(holder -> {
                // A holder in the default package stays: no import can name it from the folder it would go to.
                if (holder.segment().equals(ManagedHolders.segmentOf(path)) || unit.getPackage() == null) return;
                String pkg = unit.getPackage().getName().getFullyQualifiedName();
                String newPkg = config.mainPackage() + ".plugins." + holder.segment();
                Path to = config.mainPackageDir().resolve("plugins").resolve(holder.segment().replace('.', '/'))
                        .resolve(holder.className() + ".java").toAbsolutePath().normalize();
                out.add(new Moved(path, to, qualified(pkg, holder.className()), newPkg + "." + holder.className()));
            }));
            return null;
        });
        out.sort(Comparator.comparing(Moved::oldName));
        return List.copyOf(out);
    }

    /** Every file's text once the holders are moved, by its current path; unchanged files left out. */
    private static Map<Path, String> rewrites(BotIndex index, List<Moved> moved) {
        Map<String, Moved> byOldName = new LinkedHashMap<>();
        moved.forEach(each -> byOldName.put(each.oldName(), each));
        Map<Path, Moved> byFile = new LinkedHashMap<>();
        moved.forEach(each -> byFile.put(each.from(), each));
        Map<Path, String> out = new LinkedHashMap<>();
        index.read(units -> {
            Set<String> stayingPackages = new LinkedHashSet<>();
            units.forEach((path, unit) -> {
                if (!byFile.containsKey(path) && unit.getPackage() != null) {
                    stayingPackages.add(unit.getPackage().getName().getFullyQualifiedName());
                }
            });
            units.forEach((path, unit) -> {
                String text = index.sources().get(path);
                if (text == null) return;
                String rewritten = rewritten(text, unit, byFile.get(path), byOldName, stayingPackages);
                if (!rewritten.equals(text)) out.put(path, rewritten);
            });
            return null;
        });
        return out;
    }

    /**
     * {@code text} with its package moved when it is a holder ({@code self}), and every name of a moved class
     * following the class.
     */
    static String rewritten(String text, CompilationUnit unit, Moved self, Map<String, Moved> byOldName,
                            Set<String> stayingPackages) {
        List<Edit> edits = new ArrayList<>();
        PackageDeclaration declaration = unit.getPackage();
        String oldPkg = declaration == null ? "" : declaration.getName().getFullyQualifiedName();
        String newPkg = self == null ? oldPkg : self.newPackage();
        if (self != null && declaration != null) {
            Name name = declaration.getName();
            edits.add(new Edit(name.getStartPosition(), name.getLength(), newPkg));
        }

        Set<String> imported = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        for (Object each : unit.imports()) {
            ImportDeclaration imp = (ImportDeclaration) each;
            String name = imp.getName().getFullyQualifiedName();
            imported.add(name);
            if (imp.isOnDemand() && !imp.isStatic()) {
                Set<String> to = new LinkedHashSet<>();
                byOldName.values().stream().filter(m -> m.oldPackage().equals(name)).forEach(m -> to.add(m.newPackage()));
                if (to.isEmpty()) continue;
                // The old package's own import goes once nothing is left in it; each new package gets one.
                List<String> news = new ArrayList<>(to);
                if (!stayingPackages.contains(name)) {
                    edits.add(new Edit(imp.getName().getStartPosition(), imp.getName().getLength(), news.removeFirst()));
                }
                news.forEach(pkg -> added.add(pkg + ".*"));
                continue;
            }
            renamed(imp.getName(), byOldName).ifPresent(edits::add);
        }

        // Qualified names in the code, and a moved class used by its simple name from what was its package.
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(PackageDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(QualifiedName node) {
                Optional<Edit> edit = renamed(node, byOldName);
                edit.ifPresent(edits::add);
                return edit.isEmpty();
            }

            @Override
            public boolean visit(SimpleName node) {
                if (node.getParent() instanceof QualifiedName q && q.getName() == node) return false;
                IBinding binding = node.resolveBinding();
                if (!(binding instanceof ITypeBinding type) || !type.isTopLevel() || node.isDeclaration()) return false;
                String name = type.getErasure().getQualifiedName();
                String typePkg = packageOf(name);
                if (!typePkg.equals(oldPkg)) return false;
                Moved other = byOldName.get(name);
                String nowIn = other == null ? typePkg : other.newPackage();
                String nowName = other == null ? name : other.newName();
                if (!nowIn.equals(newPkg) && !imported.contains(nowName)) added.add(nowName);
                return false;
            }
        });

        if (!added.isEmpty()) {
            StringBuilder lines = new StringBuilder();
            added.forEach(name -> lines.append("\nimport ").append(name).append(";"));
            ASTNode after = unit.imports().isEmpty() ? declaration : (ASTNode) unit.imports().getLast();
            int at = after == null ? 0 : after.getStartPosition() + after.getLength();
            edits.add(new Edit(at, 0, after == null ? lines.substring(1) + "\n" : lines.toString()));
        }

        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder out = new StringBuilder(text);
        for (Edit edit : edits) out.replace(edit.start(), edit.start() + edit.length(), edit.text());
        return out.toString();
    }

    /** The edit giving {@code name} its moved class's new name, when it is, or starts with, a moved class's old one. */
    private static Optional<Edit> renamed(Name name, Map<String, Moved> byOldName) {
        for (Name part = name; part != null; part = part instanceof QualifiedName q ? q.getQualifier() : null) {
            Moved moved = byOldName.get(part.getFullyQualifiedName());
            if (moved != null) return Optional.of(new Edit(part.getStartPosition(), part.getLength(), moved.newName()));
        }
        return Optional.empty();
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String qualified(String pkg, String name) {
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    private static String relative(ProjectConfig config, Path path) {
        Path root = config.mainPackageDir().toAbsolutePath().normalize();
        Path at = path.toAbsolutePath().normalize();
        return (at.startsWith(root) ? root.relativize(at) : at).toString().replace('\\', '/');
    }
}
