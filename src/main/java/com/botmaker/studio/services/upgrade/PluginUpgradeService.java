package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.parser.refactor.ApiMigrationRunner;
import com.botmaker.studio.parser.refactor.ApiReferences;
import com.botmaker.studio.parser.refactor.CallMigrator;
import com.botmaker.studio.parser.refactor.ReviewMarks;
import com.botmaker.studio.project.FileRole;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.services.JitPackSearch;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.upgrade.ApiModel.ApiClass;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.botmaker.studio.services.upgrade.ApiModel.CTOR;
import static com.botmaker.studio.services.upgrade.ApiModel.declares;
import static com.botmaker.studio.services.upgrade.ApiModel.offers;
import static com.botmaker.studio.services.upgrade.Redirects.fittingAt;
import static com.botmaker.studio.services.upgrade.Redirects.redirectFor;
import static com.botmaker.studio.services.upgrade.Redirects.redirectsFor;
import static com.botmaker.studio.services.upgrade.Redirects.returnTypeFqn;
import static com.botmaker.studio.services.upgrade.Redirects.returnTypeOf;

/**
 * What changing one installed plugin's version would actually do to <em>this bot</em>.
 *
 * <p>Until now, changing the version rewrote one line of the pom and nothing else — no warning, no list
 * of what breaks, no way back. That is a fine operation for a library nobody depends on and a terrible one
 * for a bot whose source is the model: {@code parser/BlockConverter} parses blocks <em>out of</em> Java on
 * every open, so a renamed method does not become a red block, it becomes a file that no longer parses
 * into the shape the editor expects. The user finds out by opening their project.
 *
 * <p>This service answers the question first. It resolves the <b>target</b> version's jar (which need never
 * have been on this machine — the project pom's JitPack repository is used), ClassGraph-scans it beside the
 * one the project currently pins, and intersects the difference with the call sites in the project's own
 * source. The result is a {@link Report}: what is new, what the bot calls that is now deprecated, what the
 * bot calls that is <em>gone</em> (with file and line), and what the plugin itself says cannot be migrated
 * automatically.
 *
 * <h2>One coordinate, handed in</h2>
 *
 * <p><b>It was {@code SdkUpgradeService} until 2026-09-15, and the SDK was not a parameter but a
 * constant.</b> Nothing under this package ever read an SDK-shaped fact: the pointer vocabulary is the
 * contract's {@code com.botmaker.plugin.api.meta}, which every plugin may write, and the jar scan, the
 * pairing walk and the rewrite are about bytecode and Java source. What was SDK-shaped was the
 * <em>driver</em> — four constants and a resolver. So the coordinate is now a constructor argument,
 * {@link #artifact}, and plugin #1 goes through the path every other plugin goes through. The SDK-named
 * entry points on {@code MavenService} survive as delegations for the palette-side readers, which ask a
 * different question and stay SDK-shaped for now.
 *
 * <h2>Where the work lives</h2>
 *
 * <p>This class is the service: the public {@linkplain Report report} it hands back, the entry points that
 * build one, and the scan of the bot's own sources — the only part that needs the project. Everything it
 * asks of the two jars lives beside it, package-private, in five files that no caller outside this package
 * ever names:
 *
 * <ul>
 *   <li>{@link ApiModel} — the two jars reduced to what the questions need, and the pointer grammar;</li>
 *   <li>{@link Pairing} — the edges, and the walk that follows them to something the target jar has;</li>
 *   <li>{@link Redirects} — the one place a redirect is decided, and the checks it has to pass;</li>
 *   <li>{@link UpgradeDiff} — the lists a report carries, and the sentences shown beside them;</li>
 *   <li>{@link WhatsNew} — the one thing not derived from the bytecode: the release's own changelog.</li>
 * </ul>
 *
 * <h2>A redirect where the jars confirm it, a default where they do not</h2>
 *
 * <p>The SDK once shipped a repair per break — a {@code fix} in {@code META-INF/botmaker/migrations.json}
 * — and that lineage is the SDK's because it is the only plugin old enough to have one.
 * naming another member to point the call at — and it was guessing, because nothing checked it: two members
 * need not share a return type, an arity or any semantics. What replaced it is not the absence of a redirect
 * but a <b>checked</b> one. Studio holds both jars, so it can ask the questions that objection was really
 * about, and the position of the call decides which it has to ask:
 *
 * <ul>
 *   <li>a call standing as a <b>statement</b> discards its value, so the target's return type cannot make
 *       the redirect wrong. It is taken. (A pure default repair <em>deletes</em> that statement, throwing
 *       away work the bot did.)</li>
 *   <li>a call whose value is <b>used</b> is redirected only when what comes back still fits where the old
 *       value sat — the same type, a subtype of it in the target jar, or a widening primitive.</li>
 * </ul>
 *
 * <p>Arity is not a reason to refuse either: the arguments the call already passes are kept in order and the
 * difference is filled with literal defaults or dropped, which is {@code SignatureMigration}'s own machinery.
 * <b>Everything the check refuses falls back to the old answer</b> — a <em>default value of the type it used
 * to give back</em> ({@code false}, {@code 0}, {@code ""}, {@code null}), a deleted statement for a
 * {@code void}, and a review mark either way. <b>The repair's job is to make the bot compile; the user's job
 * is to make it correct.</b>
 *
 * <p>What says where a member went is <b>one annotation, at one end</b>: {@code @ReplacedBy}, read out of
 * the bot's <em>own</em> jar, on the deprecated element, naming what to use instead. The bot still spells
 * the element the old way, so that is where the pointer to the new spelling has to be. An <em>empty</em>
 * value is the author saying outright that nothing takes its place.
 *
 * <p><b>There is no back edge, and the premise that makes one unnecessary is enforced.</b> A second
 * annotation on the survivor — {@code @Replaces} — was read here until 2026-09-15, for the one case a
 * forward pointer cannot answer: an element that has been <em>deleted</em> carries no pointer. Nothing
 * writes it (the contract declares only {@code ReplacedBy}), and nothing needs to: japicmp refuses a
 * removal from a plugin's published API, so the target jar still carries the deprecated element and its own
 * pointer.
 *
 * <p>That is also what resolves a <b>chain</b> with no intermediate jar fetched — {@code a}→{@code b}
 * announced in 2.0 and {@code b}→{@code c} in 3.0 lands a bot still spelling it {@code a} on {@code c}.
 * Absence of a pointer is an answer and not a gap: nothing is ever paired by guesswork, because a wrong
 * pairing is a bot that compiles and behaves differently.
 *
 * <p><b>Types and members pair independently.</b> A member pointer may cross types, and a paired type does
 * not vouch for its members: each one is still resolved on its own, so a pointer kept across a redesign
 * degrades to defaults plus review marks rather than a silently wrong rewrite.
 *
 * <p>{@link #apply} then carries it out: snapshot → repair the source
 * ({@code parser/refactor/ApiMigrationRunner}) → bump the pom, one button and one revert away.
 *
 * <h2>Modernising: the same walk, one hop further, no version change</h2>
 *
 * <p>A pointer says where something went whether or not it has gone yet — that is what a deprecation window
 * <em>is</em>, both ends present at once. So {@link #modernisations()} asks the identical question of a
 * single jar, the one the bot already pins: the graph is walked with one extra rule, that a spelling the jar
 * marks {@code @Deprecated} <em>and</em> points somewhere is walked past rather than accepted. Everything
 * downstream — the shape check, the arity repair, the review marks, the all-or-nothing commit — is the code
 * that was already there, which is the reason it is a stopping rule and not a second engine.
 *
 * <p>Two things reach it: <b>Project ▸ Modernise…</b>, which touches no pom at all, and the upgrade dialog's
 * "also move off deprecated members", where the extra hop is taken during the upgrade so a bot does not
 * arrive on the new version already owing the same work. The one rule that differs is that modernising never
 * writes a default value: a deprecated member is still there, so anything the shape check refuses is left
 * alone and stays on the list, where the user can see it.
 *
 * <p>This lineage is worth one line: an OpenRewrite recipe YAML → a declarative fix engine → this.
 * OpenRewrite existed to let a user migrate with no Studio at all; once that stopped being a requirement, an
 * engine we do not control bought nothing {@code parser/refactor/CallMigrator} could not do. One consequence
 * removed a constraint rather than adding one: OpenRewrite type-attributes against the <em>old</em> SDK, so
 * the rewrite had to run before the pom was bumped, and the dialog had to teach that ordering. Our rewriter
 * resolves the SDK not at all, so snapshot → migrate → bump is a single operation.
 *
 * <h2>The one break that still cannot be repaired</h2>
 *
 * <p>A <b>removed type with no pairing</b> refuses the upgrade, naming the type and its uses. A default has
 * nowhere to go in {@code ImageTemplate t = …;} and {@code Object} would be silently wrong. Everything else
 * is repairable, which is why {@link Report#canMigrate()} is now a question about the jars rather than about
 * what the plugin's author remembered to declare.
 *
 * <h2>What it cannot see</h2>
 *
 * <p>Call sites are judged from source alone, without bindings — the same constraint
 * {@code parser/refactor/MethodReferences} works under, for the same reason (half a mid-edit project is on
 * no classpath Studio owns). A call is attributed to the plugin when its receiver is written as the class name
 * ({@code Mouse.click(…)}, {@code new ImageTemplate(…)}), which is how every generated block writes them; a
 * call through a variable is not attributed and so is not reported. A file that does not parse is named in
 * {@link Report#problems()} rather than skipped silently: "nothing breaks" must never be the answer given by
 * a scan that could not read half the project.
 *
 * <h2>Fields and constants count as API</h2>
 *
 * <p>{@code Key.ENTER}, {@code Precision.TIGHT}, {@code Direction.UP} are as much of the surface as any
 * method, and a release that deletes one breaks a bot exactly as hard. Public fields are therefore scanned
 * out of both jars alongside methods and constructors (an enum constant is a static field, so the five
 * public enums arrive for free), and three shapes of use are recognised in the bot's own source: the
 * qualified read {@code Key.ENTER}, a bare name reaching a {@code import static …Key.ENTER}, and a
 * {@code case} label, whose enum type is carried by the switch expression and so cannot be read off the
 * label at all.
 *
 * <p>That last one is why the unqualified shapes follow {@code MethodReferences}' three-way verdict rather
 * than a simple match: a bare {@code UP} that names a constant on exactly one known plugin type is attributed,
 * one that could be a constant on several is a line in {@link Report#problems()}, and one that matches
 * nothing is not a plugin reference. Guessing between two enums would report a break in the wrong class.
 */
public final class PluginUpgradeService {

    private final ProjectConfig config;
    private final ProjectState state;
    private final LibraryService libraryService;
    private final JitPackSearch jitpack;

    /**
     * Which plugin this service is about — the coordinate its jars are resolved at and whose pin the pom
     * carries. Its {@link UserLibrary#version()} is ignored: what the project currently pins is read from
     * the pom on every call, because the window may have written it since.
     */
    private final UserLibrary artifact;

    /**
     * Simple type names another installed plugin also declares — every name this report must <b>refuse</b>
     * rather than attribute. See {@link InstalledPlugin#ambiguousTypeNames} for why the set exists and
     * {@link #usesIn} for what refusing looks like.
     */
    private final Set<String> ambiguous;

    /** The SDK coordinate, which is what every caller passed implicitly until 2026-09-15. */
    public static final UserLibrary SDK =
            new UserLibrary(MavenService.SDK_GROUP_ID, MavenService.SDK_ARTIFACT_ID, "");

    public PluginUpgradeService(ProjectConfig config, ProjectState state,
                                LibraryService libraryService, JitPackSearch jitpack,
                                UserLibrary artifact) {
        this(config, state, libraryService, jitpack, artifact, Set.of());
    }

    /**
     * The same, told which simple type names are <b>shared with another installed plugin</b>.
     *
     * <p>Empty for every caller with one plugin in hand, which is what the four-argument form passes and
     * what every test that is not about collisions uses. The project upgrade window fills it, because it is
     * the only thing that holds every installed plugin's jar at once — a clash is a property of what two
     * plugins declare, and one service reading one jar pair cannot see it.
     */
    public PluginUpgradeService(ProjectConfig config, ProjectState state,
                                LibraryService libraryService, JitPackSearch jitpack,
                                UserLibrary artifact, Set<String> ambiguousTypeNames) {
        this.config = config;
        this.state = state;
        this.libraryService = libraryService;
        this.jitpack = jitpack;
        this.artifact = artifact;
        this.ambiguous = Set.copyOf(ambiguousTypeNames);
    }

    // =========================================================================
    // THE REPORT
    // =========================================================================

    /**
     * One place in the bot's own source, as the user would find it: project-relative path and 1-based line.
     *
     * <p>{@code text} is the call as the user wrote it, elided if long. A line number cannot tell
     * {@code scroll(3)} from {@code scroll(-3)}, and those are exactly the two calls a
     * {@linkplain Choice split} asks the user to answer differently.
     *
     * <p>{@code offset} is not for display. It is the <b>key</b> a per-site decision travels under: the
     * report pass and the apply pass parse the sources twice, so the AST node the dialog was built from is
     * not the node the rewriter holds, and node identity would silently pair nothing. Nothing edits the
     * files between the two passes, so a character offset is stable — and a key that misses simply falls
     * back to that site's own default, which is the correct degradation.
     */
    public record CallSite(String file, int line, String text, int offset) {

        /** The call as written, cut to something a dialog row can hold. */
        static String elide(String source) {
            String one = source.replaceAll("\\s+", " ").trim();
            return one.length() <= 40 ? one : one.substring(0, 39) + "…";
        }

        @Override
        public String toString() {
            return file + ":" + line;
        }
    }

    /** Why a call the bot makes would stop compiling on the target version. */
    public enum BreakKind {
        /**
         * The class is gone from the target SDK and no pointer, at either end, names what took its place.
         * <b>The one break that cannot be repaired</b>: a default value
         * has nowhere to go in {@code ImageTemplate t = …;}, so the upgrade is refused rather than half-made.
         */
        TYPE_REMOVED,
        /**
         * The class was renamed and this bot still writes the old name. Repaired file-wide by
         * {@code CallMigrator.renameTypeIn} — including the declarations, casts and type arguments no call
         * scan records. Listed as a break because the bot does not compile until it is made.
         */
        TYPE_RENAMED,
        /** The class is still there; no method or constructor of that name is. */
        MEMBER_REMOVED,
        /**
         * The class is still there; the field or enum constant this bot reads is not. Distinct from
         * {@link #MEMBER_REMOVED} so the report can say "the constant is gone" instead of describing
         * {@code Key.ENTER} as though it were a method.
         */
        FIELD_REMOVED,
        /** The name survives, but no overload takes the number of arguments this bot passes. */
        SIGNATURE_CHANGED
    }

    /**
     * One API member this bot calls that the target SDK no longer offers in the shape the bot uses.
     * {@code detail} is display text — the old and new signatures, or the type's new name — and is empty for
     * a plain removal.
     *
     * <p>{@code repair} is the sentence the dialog shows beside it: what Studio will write in its place —
     * renaming the type, pointing the call at where the member went, or standing a default value in. It is
     * display text rather than a code because nothing but the dialog reads it: the edit itself is derived
     * again from the jars at the moment of writing, never from this record.
     */
    public record Break(String type, String member, BreakKind kind, String detail, String repair,
                        List<CallSite> sites) {

        /** {@code Mouse.click} / {@code new ImageTemplate} — how the user reads it in their own code. */
        public String display() {
            if (kind == BreakKind.TYPE_REMOVED || kind == BreakKind.TYPE_RENAMED) return type;
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }

        /** False for exactly one kind — see {@link BreakKind#TYPE_REMOVED}. */
        public boolean isRepairable() {
            return kind != BreakKind.TYPE_REMOVED;
        }
    }

    /**
     * A member this bot calls that is {@code @Deprecated} in the jar being read. Compiles; will not forever.
     *
     * <p>{@code becomes} and {@code repair} are the two halves of the answer <em>the plugin itself</em> gives:
     * the {@code @ReplacedBy} target resolved against that same jar, and the sentence saying what moving
     * there would cost. Both are empty when the member points nowhere — a deprecation the author has not
     * said what to do about is a deprecation nothing can act on, and saying so is the point of the pair
     * being empty rather than absent.
     */
    public record Deprecation(String type, String member, String becomes, String repair,
                              List<CallSite> sites) {
        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }

        /** Whether Studio can move these calls itself — something took its place and the shapes line up. */
        public boolean isMovable() {
            return !repair.isEmpty();
        }
    }

    /**
     * One place a redirect could go, and the sentence the plugin's author wrote to distinguish it from the
     * others. {@code when} is blank where the author declared no {@code whens()} beside that candidate,
     * which is every pointer naming a single target and so nearly all of them.
     */
    public record Candidate(ApiMigrationRunner.Redirect redirect, String when) {

        /** How the user reads it in the menu: {@code Mouse.scrollUp — when notches is positive}. */
        public String display() {
            return when.isBlank() ? redirect.display() : redirect.display() + " — " + when;
        }
    }

    /**
     * A member that became <b>two</b>, and the question that puts to each call of it.
     *
     * <p>Which candidate a call meant is a property of <em>that call</em>, not of the member — the sign of
     * the argument, the thing the surrounding code does with the result — so this is the one thing in the
     * whole upgrade that is a decision rather than a fact, and the only place the user is asked one. It is
     * surfaced beside {@link Report#breaks()} and {@link Report#deprecated()} rather than inside them: a
     * split is not a new <em>verdict</em> about the member (it is still deprecated, or still gone), so
     * folding it in would change what {@link Report#canMigrate()} and {@link Report#canModernise()} mean.
     *
     * <p>Nothing is required of the user: every site arrives answered with the author's preferred candidate,
     * so a dialog closed without a click migrates exactly as it would have before splits existed.
     */
    public record Choice(String type, String member, int argCount, List<Candidate> candidates,
                         String note, List<Site> sites) {

        public String display() {
            return CTOR.equals(member) ? "new " + type : type + "." + member;
        }
    }

    /**
     * One call of a split member, with the candidates that fit <em>there</em>.
     *
     * <p>The list is filtered per site because {@code expressionSafe} is a property of the position, not of
     * the candidate: a call standing as a statement discards its result, so every candidate fits, while one
     * whose value is used admits only those whose return type still sits where the old one did. The
     * <b>first</b> is the preselection. An <b>empty</b> list is not a new outcome — it is today's default
     * value plus {@code @NeedsReview}, and the dialog says so rather than offering an empty menu.
     */
    public record Site(CallSite site, List<Candidate> candidates, boolean statement) {}

    /**
     * What the user asked for at one call site — the widened form of "which candidate", since 2026-09-15.
     *
     * <p>Three actions, and they are three because they are the three things that can be written without a
     * compile error: point the call somewhere ({@code REDIRECT}, naming which candidate), stand a default
     * value in and mark the function ({@code DEFAULT}), or delete the call ({@code DISCARD}). <b>There is no
     * "leave it"</b>: a call whose member is gone does not compile, so skipping is not on offer — discard is
     * what a user who does not want the call means.
     *
     * <p>{@code REDIRECT} with {@code candidate == 0} is the engine's own answer, so it is the value every
     * site arrives on and a window closed without a click migrates exactly as one closed before any of this
     * existed.
     */
    public record Decision(Kind kind, int candidate) {

        public enum Kind { REDIRECT, DEFAULT, DISCARD }

        /** The engine's own answer: the author's preferred candidate. */
        public static final Decision PREFERRED = new Decision(Kind.REDIRECT, 0);
        public static final Decision DEFAULT = new Decision(Kind.DEFAULT, 0);
        /** Only offered where the call stands as a statement — see {@link Site#statement()}. */
        public static final Decision DISCARD = new Decision(Kind.DISCARD, 0);

        /** The candidate at {@code index} of <em>that site's</em> own list. */
        public static Decision redirect(int index) {
            return new Decision(Kind.REDIRECT, index);
        }
    }

    /**
     * One release's own account of itself: a section of the target jar's {@code CHANGELOG.md}.
     *
     * <p>{@code date} is whatever followed the version in the heading and may be blank — a section is
     * identified by its version, never by its date. {@code lines} is the section body with the emphasis
     * markers removed and nothing else touched, so the author's wording reaches the user verbatim.
     *
     * @see WhatsNew
     */
    public record Highlight(String version, String date, List<String> lines) {}

    /**
     * Which question a {@link Report} answers — four operations over one engine.
     *
     * <p>They are not four code paths. Each is the same jar-to-jar diff with the jars chosen differently:
     * an upgrade compares the pinned version with a newer one, a downgrade compares it with an older one by
     * passing them the other way round, modernising compares the pinned jar with itself, and a removal
     * compares it with nothing at all. The distinction is carried so the window can say what it is doing;
     * the diff itself never reads it.
     */
    public enum Operation {

        /** The target is newer than what the pom pins. */
        UPGRADE,

        /**
         * The target is older. {@code @ReplacedBy} points <b>forward</b>, so nothing pairs in this
         * direction: every member the bot uses that the older jar lacks is an unpaired break, and the
         * actions left are a default value or a discard. A silent zero-redirect run would read as success,
         * which is why this is stated in the report's own header rather than inferred from an empty list.
         */
        DOWNGRADE,

        /** No version change at all — the plugin's own deprecations, read out of the jar already pinned. */
        MODERNISE,

        /** The plugin goes. Every type it declares is unpaired, because there is no target jar. */
        REMOVAL
    }

    /**
     * The whole answer to "what happens if I move to this version".
     *
     * <p>{@code problems} is what the scan could <em>not</em> determine — an unresolvable jar, a file that
     * does not parse, an old spelling two survivors both claim. It is separate from the findings on purpose:
     * an empty {@code breaks} list means something quite different depending on whether this one is empty too.
     *
     * <p>{@code highlights} is the only list here the two jars did not produce: it is the target release's
     * own {@code CHANGELOG.md} sections for the span being crossed, newest first, read out of the target jar
     * itself. Every other list states a <em>cost</em>; this one is the only thing that can state a reason,
     * which is why the dialog leads with it. Empty for a jar that carries no changelog — see
     * {@link WhatsNew}.
     *
     * <p>{@code addedBySince} is the new API <b>grouped by the release it arrived in</b>, newest first, read
     * from {@code @Since}. A flat alphabetical list of names is a cost sheet, not a reason to upgrade: what
     * the user is deciding is whether to move, and "these six things arrived in 1.2.0" is the shape of that
     * answer. A jar carrying no {@code @Since} at all lands whole in the one unlabelled bucket, which is
     * exactly today's list — every new reader degrades.
     *
     * <p>{@code scaffolding} is the release's contact with the members <em>Studio's own generated files</em>
     * write. It is stated up front rather than discovered mid-apply, which is where
     * {@code ApiMigrationRunner.scaffoldingInTheWay} finds it: a refusal that arrives after the user has
     * committed to the upgrade is the same information delivered at the worst possible moment.
     *
     * <p>{@code splits} are the members that became two — see {@link Choice}. They sit beside the two verdict
     * lists rather than inside them, deliberately: a split member is already reported there as deprecated or
     * as a break, and this is the question that goes with it, not a third kind of finding.
     *
     * <p>{@code operation} is which of the four questions this report answers. Every one of them runs the
     * same diff over the same two jars, so nothing downstream branches on it — it exists because the
     * <em>sentence</em> above the lists differs, and a downgrade in particular reads as a suspiciously quiet
     * upgrade unless the window says why nothing could be pointed anywhere.
     */
    public record Report(String from, String to,
                         Operation operation,
                         List<Highlight> highlights,
                         Map<String, List<String>> addedBySince,
                         List<Deprecation> deprecated,
                         List<Break> breaks,
                         List<Choice> splits,
                         List<String> scaffolding,
                         List<String> problems) {

        /** Everything new, as one list — the exhaustive answer, for a reader that does not want the eras. */
        public List<String> added() {
            return addedBySince.values().stream().flatMap(List::stream).toList();
        }

        /** The breaks Studio will repair itself — a type rename, or a default value standing in. */
        public List<Break> repairable() {
            return breaks.stream().filter(Break::isRepairable).toList();
        }

        /** The breaks nothing can repair: a removed type with no pairing. See {@link BreakKind#TYPE_REMOVED}. */
        public List<Break> unrepairable() {
            return breaks.stream().filter(b -> !b.isRepairable()).toList();
        }

        /**
         * Whether the upgrade may repair the source: the scan read everything, something needs repairing, and
         * nothing in it is a removed type with no counterpart.
         *
         * <p>One unrepairable break disables the whole span rather than the file it sits in. The alternative
         * — rewrite what we can and leave the rest — is the half-migration the whole design refuses: the user
         * would be left with a project that is neither the old shape nor the new one, and no way to tell
         * which call sites were touched.
         */
        public boolean canMigrate() {
            return problems.isEmpty() && unrepairable().isEmpty() && !breaks.isEmpty();
        }

        /** The deprecated members Studio can move off by itself — what "Modernise" would actually rewrite. */
        public List<Deprecation> movable() {
            return deprecated.stream().filter(Deprecation::isMovable).toList();
        }

        /**
         * Whether modernising has anything to do. Deliberately not {@link #canMigrate()}: that one asks
         * whether a <em>break</em> may be repaired, and nothing here is broken — every one of these calls
         * compiles today and would go on compiling if the user closed the dialog.
         */
        public boolean canModernise() {
            return problems.isEmpty() && !movable().isEmpty();
        }

        /** True when the scan ran cleanly and found nothing that would stop this bot compiling. */
        public boolean nothingBreaks() {
            return breaks.isEmpty() && problems.isEmpty();
        }

        /** True when the scan could not answer the question, whatever the other lists say. */
        public boolean isIncomplete() {
            return !problems.isEmpty();
        }

        static Report unavailable(String from, String to, String problem) {
            return unavailable(from, to, operationFor(from, to), problem);
        }

        static Report unavailable(String from, String to, Operation operation, String problem) {
            return new Report(from, to, operation, List.of(), Map.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(problem));
        }

        /**
         * Which way this pair of versions goes — {@link Operation#REMOVAL} is never derived, because it is
         * the one case that is not about a pair of versions at all and its caller says so outright.
         */
        static Operation operationFor(String from, String to) {
            if (from.equals(to)) return Operation.MODERNISE;
            if (from.isBlank() || to.isBlank()) return Operation.UPGRADE;
            return ApiModel.compareVersions(ApiModel.strip(to), ApiModel.strip(from)) < 0
                    ? Operation.DOWNGRADE
                    : Operation.UPGRADE;
        }
    }

    // =========================================================================
    // ENTRY POINTS
    // =========================================================================

    /**
     * The version of {@link #artifact} the project pom pins right now, {@code ""} when it pins none.
     *
     * <p>Read from the pom on every call rather than cached: this is what the window's rows are compared
     * against, and a stale answer would offer an upgrade the project has already had.
     */
    public String currentVersion() {
        return MavenService.readDependencyVersion(
                config.projectPath(), artifact.groupId(), artifact.artifactId()).orElse("");
    }

    /** {@code groupId:artifactId} — what this service is pointed at, and the key its pom write is made by. */
    public String coordinate() {
        return artifact.groupId() + ":" + artifact.artifactId();
    }

    /** What to call this plugin in a sentence — "SDK" for plugin #1, the artifact id for everybody else. */
    public String displayName() {
        return name();
    }

    /** Every version JitPack can build of it, newest first. Best-effort: an empty list on any failure. */
    public CompletableFuture<List<String>> availableVersions() {
        return jitpack.fetchVersions(artifact.groupId(), artifact.artifactId());
    }

    /**
     * Builds the report for moving this project to {@code targetVersion}.
     *
     * <p><b>Blocking</b> — resolves (and possibly downloads) two jars, scans both and parses every project
     * source file. Call it off the FX thread.
     */
    public Report compare(String targetVersion) {
        return compare(targetVersion, false);
    }

    /**
     * The same report, optionally reading <em>through</em> the target's own deprecations.
     *
     * <p>{@code alsoModernise} is the dialog's checkbox, and it changes one thing: a member that survives the
     * upgrade but arrives {@code @Deprecated} with a {@code @ReplacedBy} is followed one hop further, so the
     * report names where it went and the repair moves the call there. With it off, such a member is listed as
     * a deprecation with nothing beside it — which is the honest answer, since it still compiles.
     */
    public Report compare(String targetVersion, boolean alsoModernise) {
        String from = currentVersion();
        String to = targetVersion == null ? "" : targetVersion.trim();
        if (to.isEmpty()) {
            return Report.unavailable(from, to, "No target version was chosen.");
        }

        Optional<Path> oldJar = resolve(from);
        Optional<Path> newJar = resolve(to);
        if (newJar.isEmpty()) {
            return Report.unavailable(from, to, name() + " " + to + " could not be resolved. It may not be "
                    + "published yet, or you are offline.");
        }
        if (oldJar.isEmpty()) {
            return Report.unavailable(from, to,
                    "The " + name() + " this project currently pins (" + from + ") could not be resolved, so "
                            + "there is nothing to compare the target against.");
        }

        return compare(oldJar.get(), newJar.get(), from, to, alsoModernise);
    }

    /**
     * That jar, at one version — {@link #artifact}'s coordinate with the version filled in.
     *
     * <p>The project's own pom is consulted for its {@code <repositories>}, which is what lets a version
     * that has never been on this machine download on demand.
     */
    private Optional<Path> resolve(String version) {
        return MavenService.resolveArtifact(
                config.projectPath(), artifact.groupId(), artifact.artifactId(), "", version);
    }

    /**
     * What this plugin is called in a sentence shown to the user — its artifact id, except for the SDK,
     * which everything in this project has always called "SDK" rather than "botmaker-sdk".
     */
    private String name() {
        return MavenService.SDK_ARTIFACT_ID.equals(artifact.artifactId()) ? "SDK" : artifact.artifactId();
    }

    /**
     * What moving off this plugin's own deprecated members would do — the same question with <b>one</b> jar.
     *
     * <p>There is no version change and so no diff: the jar is compared with itself, and the only thing that
     * moves is what the plugin's authors have already said should move. Every finding therefore lands in
     * {@link Report#deprecated()} and {@link Report#breaks()} comes back empty, because nothing here is
     * broken — that is the whole difference between this and an upgrade, and why it has a question of its
     * own ({@link Report#canModernise()}) rather than borrowing {@link Report#canMigrate()}.
     *
     * <p><b>Blocking</b>, for the same reasons {@link #compare(String)} is.
     */
    public Report modernisations() {
        String version = currentVersion();
        Optional<Path> jar = resolve(version);
        if (jar.isEmpty()) {
            return Report.unavailable(version, version,
                    "The " + name() + " this project pins (" + version + ") could not be resolved, so there "
                            + "is nothing to read its deprecations out of.");
        }
        return compare(jar.get(), jar.get(), version, version, true);
    }

    /**
     * What taking this plugin <b>out</b> of the project would do — the same question with <em>no</em> target
     * jar.
     *
     * <p>Until now removing a plugin rewrote one line of the pom and said nothing, which is the operation
     * this whole service exists to replace: a bot calling a plugin that is no longer resolved does not open
     * with a warning, it opens as a file that will not compile. So a removal is a report first, exactly like
     * a version change, and it is built by the same diff — this plugin's own jar as <em>old</em>, an empty
     * model as <em>new</em>.
     *
     * <p>Two verdicts come out of it and the difference is the whole design. A type the bot only
     * <b>calls</b> is repairable: the call becomes a literal default or a deleted statement, the type name
     * goes with it, and the import line is dropped, so what is left compiles with no trace of the plugin. A
     * type the bot <b>holds</b> — a field, a parameter, a cast, a type argument — is
     * {@link BreakKind#TYPE_REMOVED} and <b>refuses the removal</b>, naming the type and every place it is
     * written. There is no value to stand in for a declaration, and inventing {@code Object} there would be
     * the one outcome worse than a compile error.
     *
     * <p><b>Blocking</b>, for the same reasons {@link #compare(String)} is.
     */
    public Report removal() {
        String version = currentVersion();
        Optional<Path> jar = resolve(version);
        if (jar.isEmpty()) {
            return Report.unavailable(version, "", Operation.REMOVAL,
                    "The " + name() + " this project pins (" + version + ") could not be resolved, so there "
                            + "is nothing to read what removing it would break out of.");
        }
        return removal(jar.get(), version);
    }

    /**
     * The removal comparison itself, given the jar — split out for the same reason
     * {@link #compare(Path, Path, String, String)} is.
     */
    Report removal(Path jar, String version) {
        Map<String, ApiClass> before = ApiModel.snapshot(jar);
        if (before.isEmpty()) {
            return Report.unavailable(version, "", Operation.REMOVAL,
                    "The " + name() + " jar scanned to no public API at all, so what this bot calls in it "
                            + "cannot be told — which would make an empty answer a guess rather than a fact.");
        }

        List<String> problems = new ArrayList<>();
        Map<String, ApiClass> after = Map.of();
        Uses uses = usesIn(before.keySet(), ApiModel.fieldOwners(before, after), problems);
        Pairing pairing = Pairing.of(before, after, false);

        // No highlights, no additions and no deprecations: each of those is a statement about a release the
        // user is moving to, and there is none. The breaks are the whole answer.
        return new Report(version, "", Operation.REMOVAL,
                List.of(), Map.of(), List.of(),
                UpgradeDiff.breaks(before, after, uses, pairing, true),
                List.of(),
                List.of(),
                List.copyOf(problems));
    }

    /**
     * The comparison itself, given the two jars — everything except resolving them.
     *
     * <p>Split out so the diff can be tested against jars built on the spot rather than against whatever
     * happens to be published: the interesting cases (a method removed, an overload's arity changed, a class
     * that went away entirely) are exactly the ones no released pair of versions exhibits yet.
     */
    Report compare(Path oldJar, Path newJar, String from, String to) {
        return compare(oldJar, newJar, from, to, false);
    }

    Report compare(Path oldJar, Path newJar, String from, String to, boolean throughDeprecations) {
        Map<String, ApiClass> before = ApiModel.snapshot(oldJar);
        Map<String, ApiClass> after = ApiModel.snapshot(newJar);
        if (before.isEmpty() || after.isEmpty()) {
            return Report.unavailable(from, to,
                    "One of the two " + name() + " jars scanned to no public API at all, which means the "
                            + "comparison would be meaningless rather than empty.");
        }

        List<String> problems = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Uses uses = usesIn(known, ApiModel.fieldOwners(before, after), problems);
        Pairing pairing = Pairing.of(before, after, throughDeprecations);

        List<Deprecation> deprecated = UpgradeDiff.deprecations(before, after, uses.calls(), pairing);
        List<Break> breaks = UpgradeDiff.breaks(before, after, uses, pairing);
        return new Report(from, to, Report.operationFor(from, to),
                // Read from the target jar, not diffed out of the two: a release's reason for existing is
                // not a property of its API surface. A span of (from, from] — which is what modernising
                // passes — is empty by construction, and correctly so: nothing is being moved to.
                WhatsNew.between(newJar, from, to),
                UpgradeDiff.additions(before, after),
                deprecated,
                breaks,
                UpgradeDiff.splits(before, after, uses, pairing),
                UpgradeDiff.scaffolding(before, deprecated, breaks),
                List.copyOf(problems));
    }

    /**
     * The whole upgrade, in one button: snapshot → repair the source → bump the pom.
     *
     * <p>The snapshot comes first so all of it is one revert away in the VCS panel — which is the point, since
     * what a changed library does to a bot is only fully visible once the project is reopened.
     *
     * <p>The three steps used to be two, and the missing one was the whole reason a plugin ships pointers at
     * all. The ordering carries no constraint of its own any more: {@code mvn rewrite:run} had to run
     * <em>before</em> the bump because OpenRewrite type-attributed against the old jar, and
     * {@link ApiMigrationRunner} resolves nothing at all.
     *
     * <p>Any refusal from the migration aborts before the pom is touched, with nothing written anywhere — so a
     * failed upgrade leaves a project that still compiles against the version it already had.
     *
     * <p>{@code repairSources} is {@link Report#canMigrate()}, and it gates the middle step only. A span
     * carrying a removed type nothing pairs with still has to be <em>switchable</em>: the user reads which
     * type it is and where they use it, makes those edits themselves, and moves. Refusing the whole button in
     * that case would be a trap with no way out, since the target jar goes on lacking that type forever. What
     * it must never do is repair half of the span, which is why the flag is all-or-nothing rather than per
     * break.
     */
    public CompletableFuture<Void> apply(String targetVersion, boolean repairSources, boolean alsoModernise) {
        return apply(targetVersion, repairSources, alsoModernise, Map.of());
    }

    /**
     * The same, carrying the per-site answers the window collected: the report's own {@link CallSite} mapped
     * to a {@link Decision}. An empty map — every headless caller, and a window the user simply accepted —
     * takes the engine's own answer everywhere.
     *
     * <p>The decisions are all that crosses the dialog boundary. What each one <em>means</em> is worked out
     * again from the two jars at the moment of writing, for the same reason the rest of the repair is: a
     * value that crossed an FX thread is not evidence about the files on disk right now.
     */
    public CompletableFuture<Void> apply(String targetVersion, boolean repairSources, boolean alsoModernise,
                                         Map<CallSite, Decision> picks) {
        return CompletableFuture
                .runAsync(() -> {
                    snapshot("Before " + name() + " upgrade to " + targetVersion);
                    if (repairSources || alsoModernise) {
                        repair(targetVersion, alsoModernise, true, picks);
                    }
                })
                // Since 2026-09-15 the write is keyed by THIS service's coordinate rather than by the SDK's,
                // which is what lets a second plugin reach it at all. It re-versions a dependency the pom
                // already declares and adds none — see MavenService.setDependencyVersions — so it is the same
                // write updateLibraries did for the SDK, with the name of the artifact supplied rather than
                // assumed.
                .thenCompose(v -> libraryService.updateVersions(Map.of(coordinate(), targetVersion)));
        // There is no re-render step after the pom moves, and there is nothing left for one to do.
        // `regenerateScaffolding` produced Activities, Parameters, ActivityRegistry, FlowDriver and
        // Templates again against the new jar, because the migrator deliberately never rewrote a generated
        // file and did not have to: they were derived from the model. Nothing is derived and nothing is
        // generated, so every file in the project is the user's and every one of them goes through the same
        // migration as the rest of their code.
    }

    /**
     * Moves this bot off the deprecated members of the version it already pins — snapshot, then rewrite. No
     * pom is touched, because there is no version change: this is the same repair machinery answering the
     * question the plugin's own {@code @ReplacedBy} pointers pose, at any moment the user chooses.
     *
     * <p>It is the one entry point that is not an upgrade, and the one place a <em>default value</em> is
     * never written: a deprecated member is still there, so there is nothing to stand in for. Anything the
     * shape check refuses is simply left alone and stays on the deprecation list.
     */
    public CompletableFuture<Void> modernise() {
        return CompletableFuture.runAsync(() -> {
            snapshot("Before modernising");
            repair(currentVersion(), true, false, Map.of());
        });
    }

    /**
     * Takes this plugin out of the project: snapshot → repair the source → drop it from the pom.
     *
     * <p>The same three steps an upgrade takes, in the same order and for the same reason — the source is
     * repaired first because a pom that no longer declares the plugin is a project that no longer resolves
     * the jar the repair has to read. What differs is the last step, which removes a dependency instead of
     * re-versioning one, and which takes the plugin's {@code editorDependencies} with it: those were
     * declared {@code provided} beside it by {@code MavenService.installPlugin} and belong to nobody else.
     *
     * <p>{@code repairSources} is {@link Report#canMigrate()}, read the same way {@link #apply} reads it: a
     * removal whose report found nothing to repair still has a pom edit to make.
     */
    public CompletableFuture<Void> remove(List<UserLibrary> editorDependencies, boolean repairSources,
                                          Map<CallSite, Decision> picks) {
        return CompletableFuture
                .runAsync(() -> {
                    snapshot("Before removing " + name());
                    if (repairSources) repairRemoval(picks);
                })
                .thenCompose(v -> libraryService.removePlugin(
                        artifact.groupId(), artifact.artifactId(), editorDependencies));
    }

    /**
     * Repairs the project's own files for this plugin's <b>departure</b>, or throws saying why it will not —
     * the removal's half of {@link #repair}, and public for the same reason: it writes the files and not the
     * pom.
     */
    public void repairRemoval(Map<CallSite, Decision> picks) {
        String version = currentVersion();
        Optional<Path> jar = resolve(version);
        if (jar.isEmpty()) {
            throw new IllegalStateException("The " + name() + " jar could not be resolved again, so the "
                    + "removal stopped before changing anything. Check the report and try once more.");
        }

        ApiMigrationRunner.Outcome outcome = migrateRemoval(jar.get(), picks);
        if (outcome == null) return;                        // nothing named it
        if (outcome.isRefusal()) throw new IllegalStateException(outcome.refusal());
        try {
            ReviewMarks.ensureFile(config.mainPackageDir(), config.mainPackage());
            CallMigrator.commit(outcome.files());
        } catch (IOException e) {
            throw new RuntimeException("Some files could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * The one revert away everything here promises.
     *
     * <p>Public since 2026-09-15 because the project upgrade window takes <b>one</b> snapshot across every
     * row it is about to move: a commit per plugin would describe a state the user never chose to be in, and
     * reverting it would undo one plugin's repair while leaving another's.
     */
    public void snapshot(String message) {
        try {
            new ProjectVcs(config.projectPath()).commit(message);
        } catch (IOException e) {
            throw new RuntimeException("Could not snapshot the project first: " + e.getMessage(), e);
        }
    }

    /**
     * Repairs the project's own files against the target jar, or throws saying why it will not. An upgrade
     * that breaks nothing is not an error — most of them don't.
     *
     * <p>Everything it needs it works out again from the two jars: the report the user read is a value, and a
     * value that crossed a dialog and an FX thread is not evidence about the files on disk right now.
     *
     * <p><b>Public, and it writes the files but not the pom</b> — which is what a window moving several
     * plugins needs: one snapshot, then this once per row (each pass re-reading {@code ProjectFile}'s current
     * content, so the second plugin sees the first one's output), then one pom write for all of them. It
     * blocks, for the same reasons {@link #compare(String)} does.
     */
    public void repair(String targetVersion, boolean throughDeprecations, boolean allowDefaults,
                       Map<CallSite, Decision> picks) {
        String from = currentVersion();
        Optional<Path> oldJar = resolve(from);
        Optional<Path> newJar = resolve(targetVersion);
        if (oldJar.isEmpty() || newJar.isEmpty()) {
            throw new IllegalStateException("The " + name() + " jars could not be resolved again, so the "
                    + "upgrade stopped before changing anything. Check the report and try once more.");
        }

        ApiMigrationRunner.Outcome outcome = migrate(oldJar.get(), newJar.get(), from, targetVersion,
                throughDeprecations, allowDefaults, picks);
        if (outcome == null) return;                        // nothing needed repairing
        if (outcome.isRefusal()) throw new IllegalStateException(outcome.refusal());
        try {
            // The annotation the rewritten files now reference. Written before them, so the project never
            // exists in a state where a mark names a type that isn't there — and only when the migration has
            // already agreed to write something, so a refused upgrade adds no file at all.
            ReviewMarks.ensureFile(config.mainPackageDir(), config.mainPackage());
            CallMigrator.commit(outcome.files());
        } catch (IOException e) {
            throw new RuntimeException("Some files could not be written: " + e.getMessage(), e);
        }
    }

    /**
     * The rewrite worked out, given the two jars — everything except resolving them and writing the result.
     * Null means nothing needed repairing, which is not an error: most upgrades break nothing.
     *
     * <p>Split out for the same reason {@link #compare(Path, Path, String, String)} is: the cases worth
     * testing are the ones no published pair of versions exhibits, and they have to be compiled on the spot.
     * It is also the only seam through which a per-site {@linkplain Choice choice} can be exercised without
     * a pom, a VCS repository and a network round trip standing between the test and the answer.
     */
    ApiMigrationRunner.Outcome migrate(Path oldJar, Path newJar, String from, String targetVersion,
                                       boolean throughDeprecations, boolean allowDefaults,
                                       Map<CallSite, Decision> picks) {
        List<String> problems = new ArrayList<>();
        Map<String, ApiClass> before = ApiModel.snapshot(oldJar);
        Map<String, ApiClass> after = ApiModel.snapshot(newJar);
        Set<String> known = new LinkedHashSet<>(before.keySet());
        known.addAll(after.keySet());
        Map<String, List<String>> fieldOwners = ApiModel.fieldOwners(before, after);
        Pairing pairing = Pairing.of(before, after, throughDeprecations);

        Uses uses = usesIn(known, fieldOwners, problems);
        // The same all-or-nothing rule the report states: anything the scan could not answer — a file that
        // does not parse, a bare constant name two types both declare — stops the rewrite before it writes.
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        List<Break> breaks = UpgradeDiff.breaks(before, after, uses, pairing);
        Break refused = breaks.stream().filter(b -> !b.isRepairable()).findFirst().orElse(null);
        if (refused != null) {
            throw new IllegalStateException("\"" + refused.type() + "\" is gone from " + name() + " "
                    + targetVersion
                    + " and nothing in that release takes its place, so there is no value to stand in for it "
                    + "where this bot writes the type itself. Change these by hand, then upgrade: "
                    + String.join(", ", refused.sites().stream().map(CallSite::toString).toList())
                    + ". Nothing has been changed.");
        }

        return rewrite(before, after, uses, pairing, known, fieldOwners, allowDefaults, false, picks);
    }

    /**
     * The rewrite that takes this plugin <b>out</b> — the same pass with no target jar, so every call it
     * finds becomes a default value or a deleted statement and every import of one of its classes is dropped.
     *
     * <p>It refuses on a held type before writing anything, exactly as {@link #removal()} predicted it
     * would: the check runs twice on purpose, because the report the user read is a value and the files on
     * disk may have moved under it.
     */
    ApiMigrationRunner.Outcome migrateRemoval(Path jar, Map<CallSite, Decision> picks) {
        List<String> problems = new ArrayList<>();
        Map<String, ApiClass> before = ApiModel.snapshot(jar);
        Map<String, ApiClass> after = Map.of();
        Map<String, List<String>> fieldOwners = ApiModel.fieldOwners(before, after);
        Pairing pairing = Pairing.of(before, after, false);

        Uses uses = usesIn(before.keySet(), fieldOwners, problems);
        if (!problems.isEmpty()) throw new IllegalStateException(problems.getFirst());

        List<Break> breaks = UpgradeDiff.breaks(before, after, uses, pairing, true);
        Break refused = breaks.stream().filter(b -> !b.isRepairable()).findFirst().orElse(null);
        if (refused != null) throw new IllegalStateException(removalRefusal(refused));

        return rewrite(before, after, uses, pairing, before.keySet(), fieldOwners, true, true, picks);
    }

    /** Why a removal will not be made — the type the bot writes down, and every place it writes it. */
    private String removalRefusal(Break refused) {
        return "\"" + refused.type() + "\" comes from " + name() + ", and this bot writes the type itself "
                + "rather than only calling it — so removing the plugin leaves nothing to put in its place. "
                + "Change these by hand, then remove it: "
                + String.join(", ", refused.sites().stream().map(CallSite::toString).toList())
                + ". Nothing has been changed.";
    }

    /** The last third of both passes: what to write, over which files, through the shared runner. */
    private ApiMigrationRunner.Outcome rewrite(Map<String, ApiClass> before, Map<String, ApiClass> after,
                                               Uses uses, Pairing pairing, Set<String> known,
                                               Map<String, List<String>> fieldOwners, boolean allowDefaults,
                                               boolean removing, Map<CallSite, Decision> picks) {
        ApiMigrationRunner.Repairs repairs = repairsFor(before, after, uses, pairing, allowDefaults, removing);
        if (repairs.isEmpty()) return null;

        List<ProjectFile> editable = new ArrayList<>();
        List<ProjectFile> generated = new ArrayList<>();
        for (ProjectFile file : state.getAllFiles()) {
            // FileRole is the single source of truth for "may the user change this?", and the migration
            // answers to the same rule the editor does. Since 2026-08-29 the only file it refuses to rewrite
            // is bundled library source: nothing in a project is generated, so the second list is normally
            // empty and the runner's two-list shape is what carries that fact rather than a special case.
            (FileRole.of(file.getPath()) == FileRole.EDITABLE ? editable : generated).add(file);
        }

        return ApiMigrationRunner.run(repairs, choicesFor(before, after, uses, pairing, picks),
                editable, generated, known, fieldOwners, config.mainPackage(), null, state);
    }

    /**
     * What the rewriter has to do, derived from the two jars and this bot's own call sites.
     *
     * <p>Only types this bot actually mentions are renamed: a file-wide rename is cheap but not free, and a
     * project that never heard of {@code ImageClicker} should not have its files rewritten to themselves.
     *
     * <p>{@code allowDefaults} is off for exactly one caller — {@link #modernise()}. A default value stands
     * in for something that is <em>gone</em>, and nothing is gone when the two jars are the same one: a
     * deprecated member is still there and still compiles, so a modernisation that cannot be made cleanly is
     * left alone rather than replaced by {@code false}.
     *
     * <p>{@code removing} is on for exactly one caller — {@link #migrateRemoval}. Nothing is renamed and
     * nothing is redirected, because there is no jar to rename or redirect <em>to</em>: every call becomes a
     * removal, and every one of the plugin's own class names is handed over as an import to drop.
     */
    private static ApiMigrationRunner.Repairs repairsFor(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after,
                                                         Uses uses, Pairing pairing,
                                                         boolean allowDefaults, boolean removing) {
        Map<String, ApiMigrationRunner.TypeRename> types = new LinkedHashMap<>();
        Map<String, ApiMigrationRunner.Redirect> redirects = new LinkedHashMap<>();
        Map<String, ApiMigrationRunner.Removal> removals = new LinkedHashMap<>();

        // A type the bot only *writes* — `ImageTemplate t;`, a parameter, a type argument — is renamed on
        // the same evidence as one it calls. The rename itself was always file-wide and so always covered
        // these places; what was missing was any reason to run it on a file that makes no call at all.
        for (TypeUse use : uses.types()) {
            ApiClass then = before.get(use.type());
            if (then == null) continue;
            ApiClass now = pairing.pairedTo(then, after);
            if (now != null && !now.simpleName().equals(then.simpleName())) {
                types.putIfAbsent(then.simpleName(),
                        new ApiMigrationRunner.TypeRename(then.name(), now.name()));
            }
        }

        for (Call call : uses.calls()) {
            ApiClass then = before.get(call.type());
            if (then == null || !declares(then, call.isField(), call.member())) continue;

            if (removing) {
                String gone = returnTypeOf(then, call);
                // No returnTypeFqn: that is the cast a default needs to be spelled in the *target* jar, and
                // there is none. The cast is only ever wanted for a type the target still has.
                removals.putIfAbsent(then.simpleName() + "#" + call.member() + "#" + call.argCount(),
                        new ApiMigrationRunner.Removal(then.simpleName(), call.member(), call.argCount(),
                                gone));
                continue;
            }

            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) continue;                      // refused above; nothing to write
            if (!now.simpleName().equals(then.simpleName())) {
                types.putIfAbsent(then.simpleName(), new ApiMigrationRunner.TypeRename(
                        then.name(), now.name()));
            }

            String key = then.simpleName() + "#" + call.member() + "#" + call.argCount();
            ApiMigrationRunner.Redirect redirect = redirectFor(then, now, call, after, pairing);
            if (redirect != null) {
                redirects.putIfAbsent(key, redirect);
                continue;
            }
            // Nothing to redirect to. Either the call already resolves on the paired type — the type sweep
            // will carry it across on its own — or there is nowhere for it to go and a default stands in.
            if (!allowDefaults || offers(now, call.member(), call.argCount())) continue;
            String removed = returnTypeOf(then, call);
            removals.putIfAbsent(key,
                    new ApiMigrationRunner.Removal(then.simpleName(), call.member(), call.argCount(),
                            removed, returnTypeFqn(removed, after)));
        }
        // Every class the plugin declares, so the runner can drop the import lines that name them. It is the
        // whole set rather than the ones the bot calls: an import of a class the bot no longer uses still
        // stops resolving once the jar leaves the classpath, and the runner asks each file for itself.
        List<String> dropped = removing
                ? before.values().stream().map(ApiClass::name).sorted().toList()
                : List.of();
        return new ApiMigrationRunner.Repairs(List.copyOf(types.values()), List.copyOf(redirects.values()),
                List.copyOf(removals.values()), dropped);
    }

    /**
     * Turns the window's per-site decisions into the actions the runner will apply — see {@link Decision}.
     *
     * <p>A redirect's index is looked up in the same list {@link UpgradeDiff#splits} built for that site,
     * worked out here from the jars rather than carried over from the report. Anything that does not line up
     * — a site nobody was asked about, a member that no longer splits, an index past the end — is simply left
     * out, and the site takes the preferred candidate that {@link #repairsFor} already recorded. A key that
     * misses is the correct degradation, not a failure: it produces the upgrade the user would have got by
     * not choosing.
     *
     * <p><b>A discard asked for where the call is not a statement becomes a default</b>, decided here rather
     * than left to the runner's own guard, because this is the layer that knows the call's position. Both
     * layers refuse it, for the reason the whole feature has one rule: nothing may write a file that does
     * not compile, and a deleted expression leaves a hole.
     */
    private static ApiMigrationRunner.Choices choicesFor(Map<String, ApiClass> before,
                                                         Map<String, ApiClass> after, Uses uses,
                                                         Pairing pairing, Map<CallSite, Decision> decisions) {
        if (decisions.isEmpty()) return ApiMigrationRunner.Choices.NONE;

        Map<ApiMigrationRunner.SiteKey, ApiMigrationRunner.Action> out = new LinkedHashMap<>();
        for (Call call : uses.calls()) {
            Decision decision = decisions.get(call.site());
            if (decision == null || Decision.PREFERRED.equals(decision)) continue;

            if (decision.kind() == Decision.Kind.DISCARD) {
                out.put(call.key(), call.statement()
                        ? ApiMigrationRunner.Action.DISCARD
                        : ApiMigrationRunner.Action.DEFAULT);
                continue;
            }
            if (decision.kind() == Decision.Kind.DEFAULT) {
                out.put(call.key(), ApiMigrationRunner.Action.DEFAULT);
                continue;
            }

            ApiClass then = before.get(call.type());
            if (then == null || !declares(then, call.isField(), call.member())) continue;
            ApiClass now = pairing.pairedTo(then, after);
            if (now == null) continue;

            List<Candidate> fitting = fittingAt(redirectsFor(then, now, call, after, pairing), call);
            int pick = decision.candidate();
            if (pick < 0 || pick >= fitting.size()) continue;
            out.put(call.key(), new ApiMigrationRunner.Action.Redirected(fitting.get(pick).redirect()));
        }
        return new ApiMigrationRunner.Choices(out);
    }

    // =========================================================================
    // THE BOT'S OWN CALL SITES
    // =========================================================================

    /**
     * One reference in the bot's source to something that looks like a member of the plugin being read,
     * reduced to what the report asks of it: which member, how many arguments, and where the user finds it.
     *
     * <p>The finding itself is {@link ApiReferences}' — the same scan {@code ApiMigrationRunner} rewrites from.
     * That sharing is the point: two scans would eventually disagree, and the shape of the disagreement would
     * be a dialog listing three call sites next to a button that repairs two.
     */
    record Call(String type, String member, int argCount, CallSite site, Path file,
                boolean statement) {
        boolean isField() {
            return argCount == ApiReferences.FIELD_READ;
        }

        /** The key a per-site decision is looked up under — see {@link CallSite#offset()}. */
        ApiMigrationRunner.SiteKey key() {
            return new ApiMigrationRunner.SiteKey(file.toString(), site.offset());
        }
    }

    /**
     * One place the bot writes such a type's name without calling it — {@code ImageTemplate t;}, a parameter,
     * a {@code List<ImageTemplate>}, a cast.
     *
     * <p>It carries no member because there is none: this is the bot depending on a type <em>existing</em>.
     * That is why it is here at all — a removed type is the one break with no repair, and a report built only
     * from calls said nothing about a bot that merely holds one.
     */
    record TypeUse(String type, CallSite site) {}

    /** Everything one pass over the bot's sources found, which is what every reader downstream needs. */
    record Uses(List<Call> calls, List<TypeUse> types) {}

    /**
     * One pass over the bot's sources — the only part of the report that needs the project.
     *
     * <p><b>A type name another installed plugin also declares is refused here, before anything is
     * scanned.</b> Attribution is by the simple name the source writes, so {@code Point.of(…)} in a project
     * holding two plugins that both declare a {@code Point} is genuinely unanswerable — and the answer is a
     * line in {@code problems()}, which stops the whole report and the whole rewrite, rather than a guess.
     * That is {@code MethodReferences}' own three-way verdict: one owner is a match, several is a problem,
     * none is not a reference. Guessing would report a break in a class the bot never touched and then
     * rewrite it there, which is the one outcome worse than a compile error. The refusal is the same
     * all-or-nothing rule as a file that does not parse, one level up.
     */
    private Uses usesIn(Set<String> apiTypes, Map<String, List<String>> fieldOwners, List<String> problems) {
        List<Call> calls = new ArrayList<>();
        List<TypeUse> types = new ArrayList<>();

        List<String> clashes = apiTypes.stream().filter(ambiguous::contains).sorted().toList();
        if (!clashes.isEmpty()) {
            problems.add("Another installed plugin also declares " + String.join(", ", clashes)
                    + ", so a call written on that name cannot be attributed to either without bindings. "
                    + "Change one project's spelling, or upgrade the plugins one at a time by removing the "
                    + "other first.");
            return new Uses(List.of(), List.of());
        }

        for (ProjectFile file : state.getAllFiles()) {
            String path = relativePath(file.getPath());
            CompilationUnit cu = SourceParser.parse(file.getContent());
            if (cu == null || SourceParser.hasSyntaxErrors(cu)) {
                problems.add(path + " does not parse, so its calls were not checked.");
                continue;
            }
            ApiReferences.Scan scan = ApiReferences.in(file, cu, path, apiTypes, fieldOwners);
            problems.addAll(scan.problems());
            for (ApiReferences.Reference reference : scan.references()) {
                int offset = reference.site().node().getStartPosition();
                calls.add(new Call(reference.type(), reference.member(), reference.argCount(),
                        new CallSite(path, cu.getLineNumber(offset),
                                CallSite.elide(reference.site().node().toString()), offset),
                        file.getPath(), reference.site().isStatement()));
            }
            for (ApiReferences.TypeUse use : ApiReferences.typeUses(file, cu, apiTypes)) {
                int offset = use.site().node().getStartPosition();
                types.add(new TypeUse(use.type(), new CallSite(path, cu.getLineNumber(offset),
                        CallSite.elide(use.site().node().toString()), offset)));
            }
        }
        return new Uses(List.copyOf(calls), List.copyOf(types));
    }

    private String relativePath(Path path) {
        Path root = config.projectPath();
        return path.startsWith(root) ? root.relativize(path).toString() : path.getFileName().toString();
    }
}
