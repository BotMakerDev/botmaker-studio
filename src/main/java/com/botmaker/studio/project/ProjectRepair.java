package com.botmaker.studio.project;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.services.MavenService;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restores the project files that are <b>not</b> the user's source and have gone missing — typically deleted
 * outside the Studio (an {@code rm}, a bad merge, a sync conflict), which nothing else notices:
 * {@code ProjectManager.isValidProject} only checks that {@code src/main/java} and {@code pom.xml} exist.
 *
 * <p><b>Only ever creates what is absent, never overwrites what is there.</b> A file that exists is the
 * user's, whatever is in it.
 *
 * <h2>No {@code .java} is repaired, and none is reported</h2>
 *
 * <p>Since 2026-08-29 nothing writes a project's Java, so nothing knows what it should contain and nothing
 * may put it back. What can still go missing and be restored is everything <em>around</em> the source:
 * {@code pom.xml}, {@code botmaker-project.properties}, {@code settings.json} and the placeholder image
 * template — every one of them a file Studio itself writes. A plugin's own file is not restorable here and
 * {@code activities.json} stopped being listed on 2026-09-11; see {@link #findMissing}.
 *
 * <h2>A plugin's folder is never repaired, and never reported</h2>
 *
 * <p>Every plugin keeps its own files under {@code src/main/resources/plugins/<author>/<plugin>/}, and this
 * class knows nothing about any of them — not that they should exist, not what they should contain, not
 * whether one is missing. That is not a gap: recovery restores what <em>Studio</em> writes, and a file whose
 * format only its owner knows can only be restored by its owner. A blank file written here would read as the
 * user having no parameters rather than as a file that is gone, which is the one outcome worse than absence.
 * The project-open pass names an absent owner instead of repairing anything; see
 * {@link ProjectOpenMigrations} and {@code plugin/PluginOwners}.
 *
 * <p>Two capabilities went with the generator, and both are worth knowing about rather than reinventing.
 * <b>Missing source</b> was restored by asking the project's own SDK to emit the file again — which needed a
 * generator that knew what a project must contain. <b>Damaged locked methods</b> ({@code findDamaged} /
 * {@code repairDamaged}) went further: the file existed, so the never-overwrite rule declared it fine, and a
 * {@code GoHome.run} renamed to {@code goHome} stayed renamed with nothing offering to fix it. That needed a
 * canonical text to diff a method against. Neither has one now, and the idea underneath both — that a file
 * can be partly the user's — is what the change actually removed.
 */
public final class ProjectRepair {

    private ProjectRepair() {}

    /**
     * A file that should exist but doesn't, plus what would restore it.
     *
     * <p>The restorer is nullable and, since 2026-08-26, is never actually null: every file this class
     * reports can be produced again. It stays nullable because {@link #recover} skipping a null is the
     * behaviour that made phase 0b's "report it, cannot restore it" state expressible at all, and the next
     * unrestorable file — a user's captured PNG, say — would want the same shape.
     *
     * <p>A {@link Restorer} rather than the source text it used to be, because not everything recoverable is a
     * string: {@code pom.xml} is built through the Maven Model API and the placeholder template is a generated
     * PNG. {@link #ofSource} keeps the common case a one-liner.
     */
    public record Missing(Path path, Restorer restorer, String reason) {
        public String fileName() { return path.getFileName().toString(); }

        /** The common case: a file whose whole content is known text. */
        public static Missing ofSource(Path path, String source, String reason) {
            return new Missing(path, target -> Files.writeString(target, source), reason);
        }
    }

    /** Writes one missing file. Called only after a re-check that it is still absent. */
    @FunctionalInterface
    public interface Restorer {
        void restore(Path target) throws IOException;
    }

    /**
     * Guesses whether {@code config}'s project is a game-bot project, from its sources: the entry point calls
     * {@code Bot.start}, or the scaffold's two co-generated files are both still present.
     *
     * <p><b>Prefer the persisted template</b> ({@link StudioProjectSettings#template()}, resolved once into
     * {@link ProjectState#getTemplate()} at open). This heuristic is the fallback for projects created before
     * the template was recorded, and it has a real cliff: a game-bot project with <em>every</em> scaffold file
     * deleted and a rewritten main is indistinguishable from an empty one — which is exactly the wrecked
     * project recovery most needs to fix.
     *
     * <p>A guess costs more than it used to: the answer feeds {@link FileRole}, so guessing GAME_BOT makes the
     * named files read-only. One stray file must therefore not be enough — a user's own {@code GameLoop.java}
     * in an empty project used to be sufficient here, and the reward was that their only file went read-only.
     * So the evidence is <em>every</em> file the generator claims, all present at once.
     *
     * <p><b>There is no file list to check any more, and the fallback that used one is gone.</b> It asked
     * the project's own SDK which {@code .java} a game bot must have and required all of them present —
     * itself a repair of an older version that named {@code FlowDriver.java} and {@code ActivityRegistry.java}
     * as literals. Nothing writes a project's Java now, so no list exists and no set of files is evidence of
     * anything. What is left is the entry point's own text, which is the evidence that was always the
     * strongest, plus {@code settings.json} where the user's choice is actually recorded.
     */
    public static boolean looksLikeGameBot(ProjectConfig config) {
        Path entry = config.entrySourceFile();
        if (!Files.exists(entry)) return false;
        try {
            // "Bot.start" is the current entry-point call; "Bot.supervise" recognises pre-rename projects.
            String main = Files.readString(entry);
            return main.contains("Bot.start") || main.contains("Bot.supervise");
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Whether any of the project's own Java names {@code com.botmaker.sdk} — the only witness left, once a
     * pom is missing, to whether this project ever had the SDK.
     *
     * <p>It is the one place in Studio that spells a plugin's package prefix, and that is worth being
     * uncomfortable about: {@code ImportManager.repairSdkImports} did the same and was deleted on 2026-09-02
     * for exactly it, because a repair keyed to one plugin's name gives a second plugin nothing. The
     * difference that makes this acceptable is that the answer is not a *behaviour* — nothing is rewritten
     * and no name is resolved. It picks between two pom shapes, and being wrong costs a visit to Manage
     * Plugins. If a second plugin ever ships, the honest generalisation is to ask each bound plugin whether
     * the source names it; there is nothing to ask today, since with no pom there is no classpath and so no
     * plugin bound at all.
     *
     * <p>Best-effort in every direction: an unreadable file, an unwalkable tree or no sources at all answer
     * <em>no</em>, which is the direction that adds nothing to somebody's build.
     */
    static boolean usesSdk(ProjectConfig config) {
        Path sources = config.projectPath().resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(sources)) return false;
        try (var walk = Files.walk(sources)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".java")).anyMatch(p -> {
                try {
                    return Files.readString(p).contains("com.botmaker.sdk");
                } catch (IOException | RuntimeException unreadable) {
                    return false;
                }
            });
        } catch (IOException | RuntimeException unwalkable) {
            return false;
        }
    }

    /**
     * Everything that is missing and recoverable, in a stable order. Empty when the project is intact.
     *
     * <p>{@code template} says which scaffold the project is supposed to have; a null template falls back to
     * {@link #looksLikeGameBot}.
     *
     * <p><b>A plugin's file is not listed here, and since 2026-09-11 that includes {@code activities.json}
     * (which used to be the third parameter).</b> Recovery can only restore what it can write honestly, and
     * the only copy of a plugin's data the editor ever held was a parse it no longer keeps — so a recovered
     * {@code activities.json} would be an empty file with the user's values silently replaced by defaults,
     * which is worse than the missing file it replaced. What is listed is what Studio itself writes:
     * {@code pom.xml}, {@code botmaker-project.properties}, {@code settings.json} and the placeholder image.
     */
    public static List<Missing> findMissing(ProjectConfig config, ProjectTemplate template) {
        List<Missing> missing = new ArrayList<>();
        Path mainDir = config.mainSourceFile().getParent();
        if (mainDir == null) return missing;

        ProjectTemplate resolved = template != null
                ? template
                : (looksLikeGameBot(config) ? ProjectTemplate.GAME_BOT : ProjectTemplate.EMPTY);

        // No .java is reported and none is restored. BotMaker writes a project's source once, when the
        // project is created, and never reads or rewrites it — so there is no list of files a project "must"
        // have, and a file that is gone is a file its owner deleted. Restoring it would be inventing a
        // starting point for code that has since been written and thrown away.
        //
        // What used to be here, in the order it appeared: two file names as string literals, then the
        // generator's own claimed list with Regeneration.restore behind each entry, then the seed plan. Each
        // was a better answer to a question that has stopped being asked.

        missing.addAll(missingResources(config, template, resolved));

        // activities.json was restored here, from the parse the editor held in ProjectState. Both the parse
        // and the restore are gone — see the note on this method. Activities.java, Parameters.java,
        // ActivityRegistry.java, FlowDriver.java and one stub per activity were asked about here before that,
        // and none of them exists to be missing: a project generated before 2026-08-29 keeps those files as
        // ordinary source of its own, so their absence is a deletion rather than damage.
        return missing;
    }

    /**
     * The non-source files a project cannot do without, when they are gone.
     *
     * <p>These were unchecked until 2026-08-25, and each fails in its own quiet way: no {@code pom.xml} and
     * nothing builds; no {@code botmaker-project.properties} and the bot silently reverts to SDK defaults —
     * a game bot stops driving real input and nothing says so; no {@code settings.json} and the editor forgets
     * which template the project is, which is what {@link #looksLikeGameBot} then has to guess; no
     * {@code default_template.png} and every {@code new ImageTemplate(Templates.DEFAULT_TEMPLATE)} points at a
     * file that isn't there.
     *
     * <p><b>Only what can be restored honestly is listed.</b> A user's own captured template PNG is not here:
     * the pixels are gone and nothing can invent them — that case is the Resource Manager's, which offers to
     * forget the reference instead. And {@code settings.json} is restored <em>only when the template is
     * known</em> ({@code recorded} non-null): rebuilding it from a {@link #looksLikeGameBot} guess would write
     * that guess down as a recorded fact, which is worse than leaving the file absent and guessing again.
     */
    private static List<Missing> missingResources(ProjectConfig config, ProjectTemplate recorded,
                                                  ProjectTemplate resolved) {
        List<Missing> missing = new ArrayList<>();
        Path pom = config.projectPath().resolve("pom.xml");
        if (!Files.exists(pom)) {
            // Which shape to rebuild is asked of the SOURCE, not of the recorded template. Since 2026-09-04
            // a blank project names no plugin, so writing the bot pom unconditionally would hand an SDK to a
            // project that never had one — but `recorded` cannot tell the two apart either: every blank
            // project ever made records EMPTY, and the ones made before that date do pin the SDK. The pom
            // itself is the thing that is missing, so the only honest witness left is whether the user's own
            // code names the SDK. Guessing wrong in the safe direction costs one visit to Manage Plugins;
            // guessing wrong in the other direction adds nine dependencies nobody asked for.
            boolean bot = usesSdk(config);
            missing.add(new Missing(pom,
                    target -> {
                        if (bot) {
                            MavenService.writePom(config.projectPath(), config,
                                    MavenService.SDK_FALLBACK_VERSION);
                        } else {
                            MavenService.writeBlankPom(config.projectPath(), config);
                        }
                    },
                    bot ? "build file (SDK pin reset to " + MavenService.SDK_FALLBACK_VERSION + ")"
                        : "build file (no BotMaker SDK — this project's code names none)"));
        }

        Path properties = config.resourcesRoot().resolve(ProjectProperties.FILE_NAME);
        if (!Files.exists(properties)) {
            BotSettings defaults = resolved == ProjectTemplate.GAME_BOT
                    ? BotSettings.GAME_DEFAULTS : BotSettings.DEFAULTS;
            missing.add(new Missing(properties, target -> BotSettings.write(config.resourcesRoot(), defaults),
                    "project properties"));
        }

        Path settings = config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME);
        if (recorded != null && !Files.exists(settings)) {
            missing.add(new Missing(settings,
                    target -> StudioProjectSettings.empty().withTemplate(recorded).write(config.resourcesRoot()),
                    "editor settings"));
        }

        // The placeholder picture was a fourth row here until 2026-09-01, restored through the SDK's
        // TemplateLibrary. It goes because it was repairing another module's file on its behalf, and because
        // it had already stopped being needed: the plugin's own picture surfaces call ensurePlaceholder the
        // first time they look at the folder, so a missing placeholder repairs itself the next time anything
        // opens a gallery. Recovering it from here only meant Studio knowing what a picture is called.
        return missing;
    }

    /**
     * Creates every file reported by {@link #findMissing}, and returns what was actually written.
     *
     * <p>An entry with no restorer is skipped silently, and so is one whose file has reappeared since the
     * scan: this pass never clobbers.
     */
    public static List<Path> recover(ProjectConfig config, List<Missing> missing) throws IOException {
        List<Path> written = new ArrayList<>();
        for (Missing m : missing) {
            if (m.restorer() == null) continue;
            if (Files.exists(m.path())) continue;      // re-check: never clobber
            Files.createDirectories(m.path().getParent());
            m.restorer().restore(m.path());
            written.add(m.path());
        }
        return written;
    }

    // `needsActivityRegeneration` was here, and is deliberately gone (2026-08-26). It answered "are any of
    // these files something only ActivityService can write?", and after phase 4 the answer is no for every
    // shape of entry: recovery restores each file directly from the generator. A predicate that is now
    // always false is not a cheap thing to keep — its callers each printed a line telling the user to run a
    // recovery that had, by then, already restored everything.

    /** Groups {@code missing} by reason, for a readable confirmation dialog. */
    public static Map<String, List<String>> summarise(List<Missing> missing) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (Missing m : missing) {
            byReason.computeIfAbsent(m.reason(), k -> new ArrayList<>()).add(m.fileName());
        }
        return byReason;
    }

    // `Damage`, `findDamaged`, `damageIn`, `repairSource`, `repairDamaged` and their AST helpers were
    // here, and went on 2026-08-29 with the generator they compared against. They asked whether a method
    // BotMaker owns inside a file the user owns had been renamed, re-signed or rewritten — which needed a
    // canonical text to diff against, and there is none: nothing generates a project's Java. The whole idea
    // that a file can be partly the user's is what went; see `FileRole` and `MethodLock`, which are the next
    // thing to follow it.
}
