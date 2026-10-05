# Relationship to the SDK and shared

Studio's BotMaker Maven dependencies are **`botmaker-studio-api`** (the plugin contract it hosts),
**`botmaker-plugin-host`** (the loader), **`botmaker-shared`** (editor-time native window capture) and
**`botmaker-session`** (private displays). **That is the whole list, and the two that are missing are the
point of it.**

- **`botmaker-sdk` left this pom on 2026-09-02, and with it every `com.botmaker.sdk` name in
  `src/main/java`.** Studio is a plugin host that bundles no plugin: the SDK reaches it only as *a plugin*,
  loaded off the open project's own resolved classpath by `PluginHost.bind` — which is where a bot's pinned
  SDK always came from, so an open project is unaffected. What is lost is `PluginHost.BUNDLED`: with **no
  project open** there is no name resolution and no plugin toolbar item. The lineage of the thing that went
  is worth one line, because each step deleted the one before it: `palette/SdkApi` (a hand-kept
  `List<String>` nothing verified) → `palette/SdkType` (an enum of real `Class<?>` literals, deleted in
  plugin-platform phase 7) → the SDK's own reflected `PaletteCatalog` → **no compile-time knowledge of any
  plugin at all**.

- **`botmaker-plugin-toolkit` left it the same day.** Studio carried it at `runtime` scope from 2026-08-28
  so its bundled `SdkPlugin` — a subclass of the toolkit's `AbstractStudioPlugin`, reached through the SDK's
  non-transitive `optional` dependency — could be constructed at all. With no bundled plugin there is
  nothing to construct. It survived one more day on *"the fallback copy for a plugin that brings none"*,
  which does not survive reading the gate: `botmaker-cli`'s `pom-scopes` check **refuses** a `provided`
  toolkit and **passes** a plugin with no toolkit at all, so a plugin either brings its own at `compile`
  scope (what the archetype generates) or needs none. The only plugin a fallback would rescue is the one the
  registry rejects — and it would bind that plugin to *Studio's* toolkit version rather than the one it
  compiled against. `StudioSourcesTest` still forbids naming a `com.botmaker.plugin.toolkit` type here.

- **`plugin/PluginHost` is where a name is resolved, and it serves two different catalogs on purpose.**
  `bundled()` is "which names does a plugin own?" — the newest surface this build knows — and backs
  `ownerOf` / `qualifiedName` / `isFacadeClass` / `menuFacades` / `facadeNames`, every one of which is a
  question asked with **no project in hand**. `catalogFor()` is "what should we offer *this* bot?" and is
  the only one curation may read. Using the first for curation would offer a bot on an older SDK members its
  jar has never had. **It lost its pin argument on 2026-09-22**, with `StudioPlugin.catalog(String)`'s:
  every plugin ignored the pin, because the catalog a project's own plugins serve is already the one its
  jar was built with — which is what the pin used to select.

  **What loads the plugins is no longer here.** `plugin/PluginLoader` moved to the `botmaker-plugin-host`
  submodule on 2026-08-28 (`com.botmaker.plugin.host.PluginLoader`, an ordinary `compile` dependency), so
  the `botmaker` CLI and the plugin registry's CI load a plugin exactly as Studio does — the
  parent-first/child-first split is the last code in this project that should exist in two copies.
  `PluginHost` itself stayed: it is the bundled fallback, the per-project bind and the two catalogs above,
  all of which are about *Studio's* open project. **`botmaker-plugin-host` is not `botmaker-plugin-toolkit`,
  and Studio must never list the second** — the toolkit is a *plugin's* dependency, resolved onto the
  plugin's own classloader. (The reason is not that a host copy would deny a plugin its own version:
  `PluginLoader` is child-first for the toolkit, so it would not. It is that there is no plugin the host
  copy helps and one it would silently mis-serve — see the bullet above.)

  **The catalog mirrors the SDK's `api`/`internal` boundary; it does not draw a second one.** The SDK's rule
  is *"can a bot write the name down?"* — a type it can only ever *receive* lives in `internal`. When 1.1.0
  applied that rule, the `CaptureSource` implementations (`Desktop`, `Monitor`, `NamedWindow`,
  `SessionSource`) and the observation stack (`Bots`, `BotObserver`, `Surface`, `ClickEvent`, `MatchEvent`,
  `SwipeEvent`) left `api`, and `Screen` was deleted. A type only a factory returns is not catalogued.
  The same release sub-packaged the whole surface — `api.geometry.Point`, `api.util.Debug`,
  `api.bot.Session`, `api.meta.ReplacedBy` — which is why **`ImportManager.repairSdkImports` existed**: an
  existing bot's `import com.botmaker.sdk.api.Point;` no longer resolved, so it was repointed on open by
  asking `PluginHost.qualifiedName` for the simple name's current FQN, guarded by a
  `com.botmaker.sdk.api.` prefix constant. **Deleted 2026-09-02**, with `SDK_API_PREFIX` and
  `SHARED_OCR_PREFIX`, because that guard is one plugin's package name written down in the host: the
  repair could only ever fire for the SDK, and a second plugin re-packaging its own types got nothing.
  A declared rename is `@ReplacedBy` and the migrator's business; an undeclared package move is now a
  readable compile error, which is the honest outcome for a host that cannot know what the package was
  called.
- **Method knowledge does _not_ come from that jar, on purpose.** A generated bot compiles against the SDK
  version *it* pins, which may be older than Studio's. So methods still come from `ProjectAnalyzer` scanning
  the bot's **resolved** SDK jar with ClassGraph, and Javadoc from `SdkDocsService` parsing the bot's
  `botmaker-sdk:<version>:sources` jar. Adding a *method* to an existing facade needs no Studio change.
- **The served catalog is the superset; `services/SdkSurfaceService` is what the bot actually has, and the
  palette is the intersection.** Those two drifting apart is the normal case, not the exception — a bot pins one SDK,
  Studio ships on its own train — and until 2026-08 nothing noticed: an older bot was offered blocks its jar
  could not compile, and the only feedback was a javac line *after* the block was built. The service parses
  nothing; it reads the `ClassInfo` `TypeSummaryManager` already holds, including `@Deprecated` (bytecode, a
  `RUNTIME` annotation — **not** parsed Javadoc; the Javadoc `@deprecated` *text* naming the replacement is
  `SdkDocs.Overload.deprecated()`, a separate thing that can disagree). **It fails open**: with no SDK
  indexed, every presence query answers yes and every deprecation query answers no — a degraded probe must
  never hide a block the user legitimately has, nor strike one through.
  **No menu needs an explicit *presence* gate and none must grow one**: `StatementMenu` and `MenuBuilders`
  enumerate members through `ProjectAnalyzer` first and drop a facade that resolves none, which is the same jar
  answering the same question. The service's presence queries exist for the surfaces where nothing enumerates
  first — the `OverlayPalette` chips, the class dropdowns on `MethodInvocationBlock`/`LambdaCallBlock`, and
  `ProjectSettingsDialog`'s favourites.
  **That rule is about *presence*. Curation is a second question and does need an explicit filter** (2026-08-23):
  "is this method here?" and "should we lead with it?" are different, and nothing enumerable answers the second.
  **Since 2026-08-26 the answer comes from the SDK's per-version catalog, served through
  `PluginHost.catalogFor(the project's pin)`** (no argument since 2026-09-22: the project's own plugins
  answer) — the third mechanism to hold this job, after an `@Palette`
  annotation probed out of the bot's jar (2026-08-23 → 2026-08-25) and nothing at all for the day between.
  Read `isCurated` as *"a plugin catalogues this type"* and `isPaletteAware` as *"a catalog was served"*.
  **The fail-open is at the version level and only there:** a pin with no catalog — released before catalogs
  existed, *or newer than this editor* — is uncurated, so the menus widen rather than empty. Curation touches
  the **index** not at all; presence and curation are two questions with two sources, which is why
  `SdkSurfacePaletteTest` builds no fixture jar and drives everything from one line in a pom.
  The invariant that keeps it safe: **filter what is *offered*, never what is *resolved*.** `MethodInvocationBlock.findSignatures` stays unfiltered (it backs argument typing and the
  current-overload lookup), and the picker shows *offered ∪ the overload this call is already on*, so a block
  sitting on a hidden overload keeps rendering, keeps compiling and can still see where it is.
  **Every surface that *proposes* a member consults it, and getting that list wrong is the mistake this
  feature has already made once.** The 2026-08-23 rollout filtered `StatementMenu.facadeMethodNames`, the ⚙
  picker, the ★ favourite submenu and `StatementFactory`'s default-overload pick — and stopped there, leaving
  the **whole expression menu** offering everything for a year of curation, because the standing "don't gate
  presence here" comment on `MenuBuilders` read as a general prohibition. 2026-08-24 closed it:
  **`MenuBuilders.buildScopeMenu`** is the single member-listing routine behind the expression menu *and* its
  search view *and* every variable/`this`/library scope, so one filter there covers six call sites, and
  **`MethodInvocationBlock.populateMethodList`** filters the method-name dropdown. The name-level twin of
  `retainOffered` is `SdkSurfaceService.retainOfferedNames`, which deliberately has **no** never-hand-back-
  nothing guard: an empty ⚙ picker on a block that plainly has overloads reads as breakage, an absent submenu
  does not.
  **Curation is about members; `FacadeRole` is about types, and the two are separate on purpose.** Whether a
  type gets a submenu at all (`MENU`), is recognised for chrome but never offered (`HIDDEN` — `Window`,
  `Debug`, `Watchdog`, `PopupGuard`, `Session`) or is an import target only (`VALUE` — `Rect`, `Point`, the
  result types) travels with an icon and a display order, and since phase 7 all three are the **catalog's**
  to declare rather than Studio's — which is what retired `SdkType.Role`. The corollary that reads as a
  contradiction from the wrong end: a `HIDDEN` or `VALUE` type is still worth curating, because its members
  are reached through a variable's member submenu and a placed block's ⚙ picker.
  **Constants are never curated** — the fields half of a member submenu is always offered whole (a catalog
  names methods and constructors; enum constants reach the activity pickers through `VariableWire`'s own
  `enumConstantNames`, which never consults the index or the catalog).
  One collision to know about: the index is keyed by **simple name**, so a *qualified* name reaches
  `SdkSurfaceService` only as an exact match against a catalogued class — a user's own `com.mybot.Mouse` is
  nobody's to curate (`PaletteKeyResolutionTest`), and since the catalog holds the real `Class<?>` that is
  now an identity check rather than a package-prefix guess. A **bare** simple name is still trusted, so a
  user class named exactly `Window`
  reaching `MethodInvocationBlock`'s class scope would be curated by the SDK's answer; that hole predates the
  change, costs a couple of missing dropdown entries and never a wrong edit, and is left open rather than
  guessed at. A pin no catalog names leaves every menu byte-for-byte unchanged; so does a class no catalog
  names inside a catalogued pin — which is what lets a catalog be written one facade at a time. One trap
  worth knowing: the signature key is derived in **three** vocabularies — `MethodSignature.signatureKey()`
  from the analyzer's signatures, `signatureKeyOf(MethodInfo)` from the raw index, and
  `signatureKeyOf(MemberId)` from the descriptor a catalog's method reference carried — and a mismatch has
  **no symptom**, just a catalogued overload that never appears. `SignatureKeyAgreementTest` holds all three
  against the real SDK jar and is the reason to keep the derivation in one place. Varargs is the case that
  bites: a descriptor cannot say varargs, so the third derivation reads the flag back off the declaring
  `Class`, because the key spells a varargs tail as its **element** type.
  **There is no version floor — `MIN_SDK_VERSION`, the amber banner, `SdkSurfaceService.isBelowMinimum`,
  `EditorCanvas.sdkFloorBanner` and `TemplateStore.requireFloor` were all deleted on 2026-08-25**, reversing
  the `1.1.0` floor set the day before. The floor existed to stop Studio emitting `FlowGraph.of(…)` into a
  project whose jar had never heard of it; Studio now emits **no generated Java at all** (see the demolition
  below), so the floor guarded nothing while still costing every pre-1.1.0 project a banner. Any pinned SDK
  opens, reads, builds and runs, and an incompatibility surfaces at compile time. `SDK_FALLBACK_VERSION`
  stays — what a *newly created* pom pins is a separate question. When the SDK's own generator lands
  (inversion phase 2), whether it can serve a given pinned version is the **generator's** answer to give,
  from its per-version catalog, not a constant here.
- **Changing an installed plugin's version is a report, not a cell edit — `services/upgrade/PluginUpgradeService`**
  (*Project ▸ Upgrade…*, the one door since 2026-09-19). It resolves the **target** version's jar
  (`MavenService.resolveArtifact`, any coordinate and any version — the project pom's JitPack repo means it need
  never have been on this machine), ClassGraph-scans it beside the pinned one, and intersects the difference with the bot's own
  call sites: what's new, what the bot calls that is now deprecated, what the bot calls that is **gone**
  (file + line), and where each break went — read from the **one pointer the old jar carries**
  (`@ReplacedBy`, `docs/refactor/21-api-compat.md` §4). Ten things about it are load-bearing:
  - **The coordinate is a constructor argument, not a constant (2026-09-15), and that is the whole of what
    "generalising the upgrade" required.** The six engine classes moved from `services/` to
    `services/upgrade/` and lost their `Sdk` prefix — `ApiModel`, `Pairing`, `Redirects`, `UpgradeDiff`,
    `WhatsNew`, `PluginUpgradeService` — as did `parser/refactor/{ApiReferences, ApiMigrationRunner}`.
    **Nothing but the names and one driver changed**, because nothing under here ever read an SDK-shaped
    fact: the pointer vocabulary is the contract's `com.botmaker.plugin.api.meta`, which any plugin may
    write, and `ApiMigrationRunner.run` already took its type set and field owners as parameters. What *was*
    SDK-shaped was `MavenService.SDK_GROUP_ID`/`SDK_ARTIFACT_ID` plus `resolveSdkJar`/`readSdkVersion`, so
    `MavenService` grew `resolveArtifact(projectDir, groupId, artifactId, classifier, version)` and
    `readDependencyVersion(projectDir, groupId, artifactId)` with the SDK-named entry points left as
    two-line delegations — `SdkDocsService` and `SdkSurfaceService` ask a *palette* question, not an upgrade
    one, and have no coordinate to hand. `PluginUpgradeService.SDK` is the coordinate `StudioActions` passes,
    and it is the only place left in the UI that says "the SDK". **The pom write is coordinate-keyed too
    since the window landed** — `MavenService.setDependencyVersions(projectDir, Map<coordinate, version>)`
    through `LibraryService.updateVersions`, one read and one write for every row at once. It only ever
    re-versions a dependency the pom already declares and adds none: the rows are built *from* the pom, so a
    coordinate it does not name means the pom moved underneath the window, and quietly acquiring a dependency
    is the wrong answer to that. `apply` refused any non-SDK coordinate for one phase, while the write was
    still `updateLibraries`' SDK pin.
  - **A row of the project upgrade window is `services/upgrade/InstalledPlugin`, and *installed* means the
    pom declares it.** Same rule as `PluginRegistry.isInstalledIn`, and the only defensible one: a plugin
    that arrives transitively is something another plugin brought, so writing a pin for it would overrule
    Maven's own mediation. The list is `LibraryService.declaredLibraries()` ∩ three sources, tried in order
    of how much each knows — `REGISTRY` (name, description, `editorDependencies`, and `available` =
    **`verifiedVersion`**, not JitPack's newest, since the newest tag may be one nothing has ever loaded),
    `LOCAL_BUILD` (a `*SNAPSHOT` in `~/.m2`; it wins the version and keeps the registry entry's editor
    dependencies, exactly as `ManagePluginsDialog.merge` already does), `UNLISTED` (the pom declares a
    plugin nothing else accounts for). **What makes a dependency a plugin is `MavenService.declaresPlugin`**
    — the `META-INF/services/com.botmaker.plugin.api.StudioPlugin` entry `ServiceLoader` itself reads, made
    public for this and previously the local-build scan's alone. A second rule would answer differently for
    the first hand-published plugin, which is the case `UNLISTED` exists for. **`InstalledPlugin.of` reaches
    no network**: it is handed the registry's answer, `~/.m2`'s and a predicate, so an unreachable registry
    leaves `available` blank on a row rather than emptying the table, and `withAvailable` is how an async
    lookup lands on a built row.
  - **Two plugins declaring one simple name is refused, never guessed** (`InstalledPlugin.ambiguousTypeNames`
    → `PluginUpgradeService`'s `ambiguous` set → a line in `problems()` from `usesIn`, before anything is
    scanned). Attribution is by the simple name the source writes, because there are no bindings, so
    `Point.of(…)` in a project holding two plugins that both declare a `Point` is genuinely unanswerable —
    the source does not contain the answer. It is `MethodReferences`' three-way verdict applied one level
    up, and the reason it must be a refusal is that a wrong attribution does not merely *report* a break in
    a class the bot never touched, it **rewrites it there**. The refusal is scoped to the clashing names, so
    one bad pair does not freeze every row; and a problem stops the report *and* the rewrite, which is the
    same all-or-nothing rule a file that does not parse already gets.
  - **The window is `ui/app/ProjectUpgradeDialog` (*Project ▸ Upgrade…*), and applying is one pass, not one
    pass per row** (`services/upgrade/ProjectUpgrade`). The order is **check every row → one snapshot →
    repair each row in sequence, each committed to disk before the next starts → one pom write**, and each
    step is where it is for a reason that is not layout. Checking first means a row that refuses does so
    while the project is untouched and there is nothing to undo — one plugin's refusal stops the plugins
    beside it, because the versions are written together and a project sitting on plugin A's new version with
    plugin B's old source compiles against neither. One snapshot because a commit per plugin describes a
    state the user never chose, and reverting it would undo one repair while leaving another. Repairing in
    sequence *with each write landing* because every pass re-reads the project's current content, so plugin B
    is migrated against the files plugin A already rewrote. **What it deliberately does not promise** is a
    rollback of a failure *after* the snapshot: that is what the snapshot is for, and a second implementation
    of the restore the Versions tab already offers would be the worse one. The version control is a `ComboBox` of
    every version JitPack can build, seeded with what is already known — so a **downgrade is the same
    operation and the same control**, and a row stays usable when JitPack never answers.
  - **Four operations, one report, and the fourth is the one worth knowing (2026-09-16).** Upgrade and
    downgrade are the same control and the same engine with the jars in a different order;
    `Report.operation()` says which, and a **downgrade says so in its own sentence** rather than leaving an
    all-defaults report to read as a failure — `@ReplacedBy` points forward, so nothing pairs going
    backwards and that is the correct answer, not a gap. **Remove is the same report with no target jar at
    all** (`PluginUpgradeService.removal()` / `remove()`): the empty model makes every type unpaired, and the
    one place removal and an upgrade must disagree is what that means for a **call**. An upgrade reads a call
    on a vanished type as `TYPE_REMOVED` and refuses, because a class disappearing out of a release the user
    did not write is evidence something larger went wrong. A removal repairs it — the call becomes a literal
    default or a deleted line and the type name goes with it — because the user has said outright that the
    plugin should go. What still refuses is a type the bot writes **down** (`ImageTemplate t;`, a parameter,
    a cast): there is no value to stand in for a declaration, so the removal is refused naming the type and
    every site, which is the constraint working rather than a gap. **Removal is also the only operation that
    drops import lines** (`Repairs.droppedImports` → `CallMigrator.dropTypeIn`): every other operation leaves
    the jar on the classpath, so an import that survives a rewrite still resolves, and a removed plugin's
    does not — including one naming a class the bot never called, which no call scan or type sweep would ever
    reach. **Install has no report and needs none** — nothing is migrated by adding a dependency — so it
    stays `ManagePluginsDialog`'s and is reached from the upgrade window's own button, rather than becoming a
    second install path to keep in step.
  - **The report layout is `ui/app/upgrade/ReportView`.** It was `SdkUpgradeDialog`'s own `render` until the
    project window existed, and it moved for the reason that dialog's javadoc already gave for its own two
    modes: every sentence in it describes what the repair will do, and two copies of that description drift
    the first time the repair changes. That dialog is gone since 2026-09-19 and this is what outlived it —
    an upgrade and a removal are the same layout in two modes. It also *collects* one
    thing — the per-call-site answer a split asks for — and owns no button: whether Apply is enabled is the
    window's question, because the project window asks it across several reports at once.
  - **It scans a jar with `TypeSummaryManager.overEverything()`, and the default manager would be wrong**
    (2026-09-15). The default filters to `PluginHost.cataloguedPackages()` — the packages the plugins bound
    *right now* catalogue — which is the right question for a menu and the wrong one here: the jar being read
    may belong to a plugin that is not bound, is being installed for the first time, or failed to load, and
    each of those answers the empty set. **A scan filtered to nothing reads as "this jar has no public API at
    all"**, which is the sentence the report actually printed in every headless caller from 2026-09-02, when
    the literal `Set.of("com.botmaker.sdk.api")` became the catalog lookup. Production never saw it (a bound
    SDK answers its own package) and 57 tests did, silently, for thirteen days. What a jar contains is a
    property of the jar. The extra classes cost nothing downstream — attribution is by the type name the
    bot's own source writes, so a class no bot can name is never a call site — and the one list that shows
    unintersected names, `UpgradeDiff.additions`, filters for itself.
  - **A redirect where the jars confirm it, a default where they do not.** The SDK once shipped a repair per
    break (a `fix` in `migrations.json`) and it was guessing — nothing checked that two members shared a
    return type, an arity or any semantics. What replaced it is not the absence of a redirect but a
    **checked** one, and the call's position decides what has to be checked: a call standing as a
    **statement** discards its value, so the target's return type cannot make the redirect wrong and it is
    always taken; a call whose value is **used** is redirected only when what comes back still fits where the
    old value sat (same type, a subtype in the target jar, or a widening primitive). Arity never refuses —
    the arguments already passed are kept in order and the difference filled or dropped, which is
    `SignatureMigration`'s machinery. Everything the check refuses falls back to a **literal default of the
    type the old jar said it returned** (`CallMigrator.literalDefaultFor`: `false`, `0`, `""`, `null` — never
    `new Point()`, since the type is often the one just removed — and **cast where the site gives the value
    no type of its own**: `((ImageTemplate) null).width()`, since `null.width()` is not Java and a bare
    `null` argument can make an overload ambiguous; an assignment or a `return` keeps the plain literal), a
    **deleted statement** for a `void`
    (`CallChange.CallDeleted`, because `0;` is not a statement), and `@NeedsReview` on the enclosing function
    **in the same rewrite** — see the review-marks bullet below. *The repair makes the bot compile; the user
    makes it correct.*
  - **`services/upgrade/Pairing` follows edges, and pairs members independently of types.** One edge map, built
    from one annotation: the **old** jar's `@ReplacedBy`, the author of the element the bot actually calls
    saying where it went. The walk follows edges until it reaches a spelling the target jar actually has; a
    visited set bounds it, and a cycle reaching nothing live is simply unpaired. Two deliberate refusals: a
    spelling the target **still has** is answered by the live element (so an accumulated pointer can never go
    stale into a wrong answer), and a pairing is never invented. `memberName` and `targetOf` are the two
    readers — the first answers "what is this called on the type this one paired with", the second hands back
    an endpoint that crossed types, and only `redirectsFor` (which is about to move the receiver too) is
    entitled to that.
  - **There is no back edge, and the removal on 2026-09-15 is worth knowing before anyone proposes one.**
    `@Replaces` on the survivor — with an era filter, since an entry only describes a bot pinned at or below
    the version it records — was read here until then, for the one case a forward pointer cannot answer: a
    **deleted** element carries no pointer. Two facts retired it. Nothing writes it: the contract declares
    `ReplacedBy` and nothing else, and `com.botmaker.plugin.api.meta.Replaces` has never existed, so for two
    weeks `ApiModel` (then `SdkApiModel`) read an annotation no plugin could produce. And nothing needs to: japicmp refuses a
    removal from a plugin's published API, so the target jar still carries the deprecated element and its own
    forward pointer. What that changes about a **chain** is worth stating, because it is not nothing — the
    intermediate is now *present* in the target jar rather than absent from both, so an upgrade stops there
    (`Legacy → Middle`) and it takes Modernise to walk past it (`Middle → Modern`). The bot compiles either
    way, which is the property that matters.
  - **The graph is multi-valued, because a member can become two.** `forwardEdges` is
    `Map<String, List<String>>`, `follow` returns a *list* expanded in declared order and depth-first through
    chains, and `Redirects.redirectsFor` returns `List<Candidate>` in preference order — `redirectFor`
    survives as the one-line "first candidate, or null", which is the whole answer for every reader that does
    not ask the user. Today's `null` is an empty list, so **every one-target pointer is
    the degenerate case** and the almost-always path is byte-for-byte what it was. A split composes with a
    chain for free (`a`→`{b,c}`, `b`→`d` lands on `{d, c}`). Two halves of compile-safety that the split
    forces apart: *shape reconcilable* belongs to the **candidate** and is decided once; *fits where the value
    is used* belongs to the **site**, since a call standing as a statement discards its result and any
    candidate fits there. Zero survivors at a site is not a new outcome — it is the default value plus
    `@NeedsReview`.
  - **Which candidate a call meant is a property of the call, so the user is asked per call site.**
    `scroll(3)` and `scroll(-3)` in one bot want different answers; a project-wide pick would be wrong in half
    of them by construction. `Report.Choice` sits **beside** `breaks`/`deprecated` (so `canMigrate()` and
    `canModernise()` keep their meanings), `CallSite` carries the call's **source text** because `(file, line)`
    cannot tell those two calls apart, and every combo arrives **already answered** — nothing is required of
    the user. The decision reaches the rewriter as a `Choices` map, so Modernise and every headless path work
    unchanged by passing nothing. **The site key is positional** — *(project-relative path, character start
    offset)* — never node identity: the report pass and the apply pass parse the sources twice, so the AST
    node in the report is not the node the rewriter holds. Nothing edits the files between the passes, and a
    key that misses falls back to that site's default.
  - **The question at a site widened from *which candidate* to *what should happen here* (2026-09-15), and it
    is three actions because three are what can be written without a compile error.** `PluginUpgradeService
    .Decision` is `REDIRECT` (naming the candidate), `DEFAULT` (a literal of what the old member gave back,
    plus `@NeedsReview`) or `DISCARD` (delete the call); it reaches the rewriter as
    `ApiMigrationRunner.Action`. **Nothing new is written** — the last two are the engine's own two outcomes,
    and what changed is who decides. `Decision.PREFERRED` is what every site arrives on, so a window closed
    without a click migrates byte-for-byte as before. **There is deliberately no *leave it alone***: a call
    whose member is gone does not compile, so skipping is not an action anybody can be offered — discard is
    what a user who does not want the call means. **Discard is legal only where the call stands as a
    statement** (`Site.statement()`, which is why the record carries it), because deleting an expression
    leaves a hole where its value sat; asked for anywhere else it is written as a default, refused twice —
    once in `choicesFor`, which knows the position, and once in the runner, which must be safe for any
    caller. Defaulting a **void** call is deleting it, since there is no value to stand in for, so the two
    actions meet there.
  - **The dialog opens with what the release *gives* you.** `Report.added` is a diff-derived list of API
    names, which is not a reason to upgrade. The SDK ships its whole `CHANGELOG.md` inside its jar as
    `META-INF/botmaker/whats-new.md`; `Report.highlights()` holds the sections in `(from, to]`, newest first,
    rendered above every cost section, with the exhaustive API diff still below it. Absent file → exactly the
    old dialog, which is the standing rule that every new reader degrades.
  - **The author's own sentence reaches the user verbatim.** `@ReplacedBy(note=…)` is preferred over Studio's
    generated sentence and never rewritten — the author speaking at the moment of the change, on the element
    the bot actually calls, which is the only end there is. `behaviourChanged` forces the review mark **even
    where the shape did not move** — `shapeChanged || behaviourChanged` — which is the one gap the model
    cannot detect by construction. `@Since` groups the additions by the version that introduced them.
  - **A removed type with no pairing is the one break that refuses the upgrade**
    (`BreakKind.TYPE_REMOVED`, `Break.isRepairable()` false): a default has nowhere to go in
    `ImageTemplate t = …;`. It disables the whole span (`Report.canMigrate()`), because rewriting some call
    sites and leaving the rest is the half-migration `CallMigrator.rewriteOthers` returns `null` to prevent.
  - **Modernise is the same machinery one hop further**, and **has no menu entry since 2026-09-19** —
    `PluginUpgradeService.modernise()` survives as a service verb with no caller in the UI. It touches no pom:
    it walks the
    pointers the project's own jar's **deprecated** elements carry (`throughDeprecations`, which also folds in
    the target jar's forward edges — same shape of edge, only the stopping rule differs) and rewrites the bot
    off them in place. It has its own verdict, `Report.canModernise()`, rather than borrowing `canMigrate()`:
    moving off a deprecation is still possible on a project where an unpaired removed type blocks the upgrade.
    It is still an argument of the report (`compare(target, alsoModernise)`) and of `ProjectUpgrade.Row`,
    false at every call site the UI has left.
  - **Studio is the version that lags**, so it degrades rather than guessing — and the pointer model makes
    that free: an annotation a newer SDK invents is simply invisible to a ClassGraph scan asking for the two
    Studio knows, so an older Studio falls back to exactly its behaviour against a jar with no pointers at
    all. There is no schema to refuse any more.
  - This replaced `mvn rewrite:run` against OpenRewrite recipes in 2026-08. OpenRewrite existed for one
    requirement — migrating with no Studio at all — and once that was withdrawn it bought nothing
    `CallMigrator` could not do. Note which way that cut: OpenRewrite type-attributes against the **old**
    SDK, so the rewrite had to run *before* the pom was bumped and the dialog had to teach that ordering.
    Our rewriter never resolves the SDK, so **snapshot → migrate → bump is one operation**, not two steps a
    user can get wrong.
  - **Breaks are judged by arity, not by argument types, and only for members the old jar had.** There are no
    bindings (same constraint as `parser/refactor/MethodReferences`), so a call through a variable is not
    attributed to the SDK at all and is not reported. A file that does not parse goes in `Report.problems()`
    rather than being skipped: `nothingBreaks()` is false whenever anything could not be read, because
    "nothing breaks" from a scan that read half the project is the one answer worse than no answer.
  - **Fields and constants are API too, and the scan reads all three shapes of use.** `Key.ENTER`,
    `Precision.TIGHT`, `Direction.UP` break a bot exactly as hard as a method, so public fields are scanned
    out of both jars into the same `byName` map (an enum constant *is* a static field — the five public enums
    come for free) and marked `ApiMember.field`, which is what stops a constant answering for a no-argument
    call. On the source side that means a qualified `QualifiedName` read, a bare name reaching an
    `import static`, and a **`case` label** — whose enum type lives on the switch expression and so is not
    written at the label at all. The unqualified two therefore take `MethodReferences`' three-way verdict:
    exactly one SDK type declaring that constant is a match, several is a `problems()` line, none is not SDK.
    Until 2026-08 none of this was read at all, so a release deleting `Key.ENTER` reported *"nothing breaks"*
    while the SDK's own `ApiRulesCheck` (whose member tags always included `field`) demanded a migration for
    it — CI and Studio disagreeing about what an API is.
  - **The edit primitives live in `parser/refactor`, and each can refuse.** `CallChange.ValueDefaulted` and
    `CallChange.CallDeleted` are the two an SDK upgrade produces and a signature edit never does;
    `CallMigrator.renameTypeIn` is deliberately **file-level, not per-site** — a type is also written in
    `Precision p;`, a cast and a type argument, none of which any call scan records, so renaming only the
    found sites leaves a file naming a class that is gone. `MethodReferences.CallSite` widened to hold a
    field reference (`arguments()` is simply empty), which is what lets one scan feed both the report and the
    rewrite. **Every primitive that cannot express its edit returns false and takes the whole migration down
    with it** — a removed `void` member in a one-line lambda body, or a constant used as a `case` label whose
    enum cannot be told. That is the same all-or-nothing `rewriteOthers` already enforced for a rewrite that
    will not parse.
  - **Applying is `parser/refactor/ApiMigrationRunner`: one pass over resolved endpoints, two sweeps per
    file.** There is no replay — `Pairing` has already walked the edges to an endpoint the target jar has, so
    `foo`→`bar` (2.0) and `bar`→`baz` (3.0) reach the runner as the single fact `foo`→`baz`, which is what a
    bot that has run neither pass actually needs, and `a`→`b` + `b`→`a` reaches nothing live and is dropped
    where a fixpoint loop would run forever. Each file is then swept **members first, types second, with a
    re-parse between**: a removed member of a renamed type is otherwise two `ASTRewrite` edits on one node,
    which it cannot express.
  - **Since 2026-09-27 the mark is the contract's `@Refactor(value, done)`, and the bullets below that say
    `@NeedsReview` are its history.** `ReviewMarks` reads it through `BotAnnotation.REFACTOR` (binding, else
    the file's import) and writes it with the contract import; nothing is generated into the bot
    (`ensureFile`, `annotationSource`, `ReviewMarker.prepare` are gone). `ReviewMarker.available(state)` —
    the resolved classpath carries `Refactor.class` (`ContractDependency.onClasspath(cp, type)`) — decides
    whether a guessing edit marks; without it the edit is made unmarked. Every **guess** marks, whoever made
    it: an upgrade's default, a signature change's new argument or rescued parameter, a `Sources.replace`
    with a note, a deleted variable's uses defaulted. Reviewing sets `done = true` and keeps the entries
    (`ReviewService.Item` is one function, its open entries); a new guess reopens a reviewed mark. Deleting a
    used variable refuses through `RefusalDialog` (uses as links; fixes: *Use `other` instead*, *Replace with
    `default`*). The upgrade window pre-fills no guess (`Report.guesses()`, and a split's site with nothing
    that fits): `ReportView.unpicked()` holds Apply, `ProjectUpgrade.waitingSites` refuses a pass with one
    unanswered. The badge became `blocks/misc/AnnotationRow`: every annotation as a pill above a method,
    field or class header, `@Param`/`@Managed` read-only, the rest removable (`CodeEditor.removeAnnotation`).
  - **The record of an incomplete repair is `@NeedsReview` in the bot's own source
    (`parser/refactor/ReviewMarks`).** Not a sidecar under `.botmaker/`: the diff cannot answer this later —
    once the pom is bumped the old jar is gone and re-diffing the project finds nothing — and an annotation
    cannot drift from the code it describes, reverts with it through Project History, and needs no schema or
    cleanup pass. It is **generated into the bot's package on demand** (`ReviewMarks.ensureFile`,
    `ProjectConfig.needsReviewSourceFile`) rather than shipped by the SDK, because every bot an upgrade is
    about to touch is pinned to the *older* SDK — an annotation arriving with the version the user is trying
    to leave would help nobody. `@Retention(SOURCE)`, so a marked bot ships the same bytes as an unmarked
    one. It used to be the one file `FileRole` classed `GENERATED` outside the game-bot template, which kept
    it out of the explorer; since the lock sweep it is an ordinary listed file, written when missing and
    never rewritten — nothing in it is about this bot, but hiding a file nobody rewrites bought nothing.
    Writing **merges** into the mark already there
    (Java allows one per method) and dedupes; `strip` removes one entry, and the last one takes the
    annotation — and, once the file holds no marks at all, the import. **A rename is not marked**: the bot
    does afterwards exactly what it did before, and burying the sites that changed meaning under the ones
    that did not is how a review list stops being read.
  - **The marker is not an SDK-upgrade feature — every refactor that rewrites a file uses it
    (`parser/refactor/ReviewMarker`).** `ReviewMarks` edits one tree through one `EditContext` and knows
    nothing of a project; `ReviewMarker` is the project-level half: `prepare` (write the annotation, answer
    the package to import it from), `snapshot` (a Project History commit), `marksSurvive` (never mark a file
    Studio regenerates), and `markLines` (mark the functions a set of changed *line numbers* falls inside —
    the way in for a rewrite done in text rather than in an AST). Four refactors now go through it:
    - **A signature edit** — `CallMigrator.applyIn` / `rewriteOthers` mark at each call site, and
      `MethodHandler.applyFunctionSignature` marks the edited function itself for a rescued parameter or a
      replaced return value. `CallMigrator.reviewEntries` is where the *complete vs. lossy* line is drawn: a
      rename, a reorder or a dropped **literal** leaves the call doing exactly what it did, so it is not
      recorded; a new input filled in with a default, a used result that no longer fits, and a dropped
      argument that **called or constructed something** all are (`droppedWork`).
    - **Deleting a variable** — marked when the uses become defaults, not when they are pointed at another
      variable, which is a complete repair.
    - **Repointing a template** — marked when blocks end up looking for a *different* picture (a delete, a
      missing-file repair), not on a rename, where every block still watches for the same thing under a new
      name. Both the caller and the rule are the SDK plugin's now (`internal/plugin/templates/TemplateUses`
      and `ResourceManagerDialog`); what Studio kept is the `reviewNote` parameter of the `Sources`
      capability, which is how a plugin asks for the mark without knowing what a picture is.
    - `ApiMigrationRunner`, as above.
  - **`prepare` may answer null, and that is not a failure.** A mark is a reference to a generated annotation,
    so if the annotation cannot be written the choice is between refusing the refactor and doing it unmarked
    — and unmarked is plainly better: the user asked for the rename, not the bookkeeping. It has to be called
    **before** anything is written, so the answer is known while refusing is still cheap. `snapshot` is
    best-effort for the same reason: a project whose history was never initialised must not lose the ability
    to rename a function.
  - **The snapshot rule is "does this touch a file the user is not looking at".** `CodeEditor.touchesOtherFiles`
    asks exactly that of the plan. The editor's own ↶ already puts the active file back and dies with the
    session; a file the user never opened has no other way home, and a template repoint happens outside the
    editor entirely. Active-file-only edits take no snapshot — a commit per keystroke is not a history.
  - **The Review tab is a scan, never a list something kept (`services/ReviewService`, `ui/app/ReviewPanel`).**
    Marks live in the source precisely so that nothing has to hold a copy of them: an edit that moves a
    function moves its mark, and a revert through Project History takes the marks out with the change. The
    price is that the list is *derived* — `ReviewService.scan` re-reads the bot's sources every time the tab
    is opened, `markReviewed` strips one entry and the panel re-scans rather than removing the row. That is
    cheap (a bot is tens of files, and a file with no `NeedsReview` in its text is never parsed) and it
    cannot go stale, which a cached list demonstrably would: four refactors write marks and two of them never
    touch the editor. The **entry text**, not the function name, identifies a row — two overloads can both be
    marked. `services/BotSources` is the one walk over the bot's `.java` files, shared with
    `plugin/HostSources` — the implementation behind the contract's `Sources` capability, which is what
    `TemplateReferences` became when its *walk* stayed and its *spellings* went to the SDK plugin. Buffer
    before disk, and a rewrite written to both.
  - **The badge is the only annotation the block editor renders** (`MethodDeclarationBlock.reviewBadge`). It
    reads `@NeedsReview` off the very `MethodDeclaration` the block was built from, so the count cannot drift
    and a mark stripped in the tab is gone from the header on the next render with nothing kept in step. The
    entries are its tooltip: the header has room for a count, not for three sentences.
  - **Generated files are rewritten but never marked.** A call in the activity registry has to be renamed with
    everything else or the bot stops compiling, but the file is regenerated on the next save, which would
    silently erase the mark. A review row that disappears on its own is worse than no row: the user is never
    told the thing they were meant to look at has stopped being listed.
  - **One scanner, two readers: `parser/refactor/ApiReferences`.** The report asks it what the bot calls; the
    runner asks it the same question to know what to rewrite. Two scans would eventually disagree, and the
    disagreement's shape is the worst available — a dialog listing three call sites beside a button that
    repairs two. A `Reference` therefore carries a `MethodReferences.CallSite` (file, parse, **node**); the
    report keeps the line and drops the node, the runner keeps the node.
  - **A member is not the only thing a bot can lose: `ApiReferences.typeUses` is the other half.** It yields
    every place the source *writes* one of the plugin's types without calling it — a field, a parameter, a return type, a
    local, a cast, a type argument, an `instanceof`, a catch clause — and `breaks()` reads it, so a removed
    unpaired type is a `TYPE_REMOVED` break **even in a bot with zero calls**, and a `TYPE_RENAMED` one lists
    those places too. Until 2026-08-23 such a bot got *no finding at all* and was upgraded into something
    that did not compile; the file-wide rename was always right, only the report and the gate that decides a
    file is worth renaming were blind to it. `mentions` is now the same walk asking a narrower question —
    the three positions `renameTypeIn` actually rewrites, plus a static import's qualifier — rather than
    "any name anywhere", which matched a local variable that happened to share a class's name.
  - **Every file in the project is migrated, since 2026-08-30.** The split survives in the runner's two
    lists — only `FileRole.EDITABLE` files are rewritten — and since 2026-09-20 the second list is no longer
    always empty: a plugin's generated model lands in it, which is what makes `ApiMigrationRunner`'s
    `scaffoldingInTheWay` refusal reachable with no code change. A model naming an SDK type a migration is
    about to move blocks the upgrade with the file named, rather than compiling against something gone.
    What follows described the arrangement it replaced, in
    which a generated file was re-rendered rather than rewritten: the upgrade service's `regenerateScaffolding` called
    `Regeneration.write` **after the pom has moved** — the render has to see the SDK the project actually
    pins now — falling back to `Regeneration.writeTemplatesClass` for an empty project, which has no
    model-derived file but does have a `Templates` class. For one day (phase 0b) it re-rendered only that
    last file, because Studio had no generator at all and **an upgrade left the generated Java pinned to the
    old SDK's spelling**; that cost is paid off. An `IOException` here is a printed sentence and not a failed
    upgrade: the sources are repaired and the pom is moved, so undoing it is the destructive answer and the
    pre-upgrade snapshot is the way back. `ApiMigrationRunner.scaffoldingInTheWay` deliberately stays
    conservative even so — relaxing it wants the per-version catalog, not just a working re-render.
  - **`apply(target, repairSources)` is snapshot → migrate → bump, and `repairSources` gates only the middle.**
    A span carrying a removed type nothing pairs with must still be *switchable* — the user reads which type
    it is and where they use it, makes those edits, moves — because the target jar goes on lacking that type
    forever, so refusing the button outright is a trap with no way out. What it must never do is repair half a
    span, which is why the flag is per-upgrade, not per break. `migrateSources` re-derives everything from the
    two jars rather than trusting the `Report` the dialog holds: a value that crossed a dialog and an FX
    thread is not evidence about the files on disk right now.
- **Studio writes a project's Java once, at creation, and never touches it again (2026-08-29).** Nothing
  generates, reconciles, restores or re-renders source. **A project's structure belongs to the user**, and a
  plugin — the SDK included — contributes methods a user calls rather than files a user inherits.
  - **`project/StarterSources` is the whole of it**: one file, composed by Studio, handed to
    `Authoring.createProject` as a caller file beside `MavenService.pomXml`. It is Studio's for the reason
    the pom already was — only the thing that knows the whole plugin set can compose the file that calls into
    them.
  - **And since 2026-08-30 it composes only the *blank* project.** There was a second shape, a game bot, and
    it was not Studio's to write: **a game bot is a project that calls the SDK's static API**, which is
    exactly what the gallery already publishes, browses and installs. So a richer starting point is now a
    **published bot carrying the `template` tag** — see *Templates* below — and the one Studio composes is
    the one that must work with no network. It prints with `System.out.println` and imports nothing:
    teaching `BotMaker.print` for what the JDK already does spends a user's first line on a BotMaker spelling
    of a Java call.
  - **What is deleted, and it is a long list.** `project/Regeneration` (with `ensureStubs`, `write`,
    `writeTemplatesClass`, `restore`, `renderEverything`), `project/seed/` (`SeedWriter`, `SeedReconciler`,
    `SeedLedger`, `SeedSync`), `project/ScaffoldMigration`, `ProjectSpecs.generatedFileNames`/
    `generatedSource`, `PluginHost.seedPlan`/`seedFiles`, `ImageTemplateLibrary.regenerateTemplatesClass`
    and its six call sites, the upgrade service's `regenerateScaffolding`, and `ProjectRepair`'s whole
    damaged-locked-method half. `project/scaffold/` and `TemplateStore` had already gone on 2026-08-26.
  - **The lineage, because each step was defended and each was superseded within days.** Studio owned the
    generators; then the SDK did and Studio spliced fences into its templates; then the templates went and
    the SDK emitted from `ProjectModel` while `Regeneration` was Studio's one door to it; then the SDK
    shipped *seeds* — real compiling classes written into a project once and maintained at marked regions,
    with a key ledger, a reconciler and a rename engine here to keep owning them. Every step improved on the
    last. What was wrong was one level up: making *files in a user's project* a plugin surface at all.
  - **Two capabilities went with the generator and neither is coming back.** *Restoring a missing `.java`*
    needed something that knew what a project must contain. *Repairing a damaged locked method* went further
    — it rendered the project's whole scaffold from its own SDK and diffed each locked method against it, so
    that a `GoHome.run` renamed to `goHome` did not leave the bot silently uncompilable. Both needed a
    canonical text, and the premise underneath them — that a file can be partly BotMaker's — is exactly what
    was given up.
  - **`ProjectRepair` keeps everything that is not source**: `pom.xml` and `settings.json` (since 2026-09-27;
    `botmaker-project.properties`, `activities.json` and the placeholder image went one by one). `looksLikeGameBot` is now the entry point's
    own text alone (`Bot.start` / `Bot.supervise`), the file-presence fallback having had no list to check.
  - **`ActivityService` was `activities.json` and nothing else, and is deleted (2026-09-11).** Adding an
    activity creates no file, renaming one moves nothing, deleting one leaves whatever the user wrote where
    it is — an activity's behaviour is a method the flow references (`Collect::body`) in a file BotMaker
    has never known the location of. That file is the SDK plugin's now, written by its own flow editor.
  - **So opening an activity is a search, not a path.** Since SDK 2.0 (2026-09-23) it follows a method
    reference: `project/managed/MethodReferences` reads every `Owner::method` inside a `@Managed` method's
    body (lambdas skipped) and resolves `Owner` by simple name to the first bot file declaring that type,
    nested types included, over `BotSources.scan`, buffer before disk. No plugin is named, so any plugin
    whose value holds references gets the same navigation. `OverlayTargetPicker` lists the labels
    (`Collect::body`), opens the file and scopes the method picker to `body`. **Finding nothing is an
    ordinary answer**: a reference to a class the bot does not declare is a status line naming the class,
    never an offer to write a file. It was `project/ActivityBodies` (2026-08-30), a text search for
    `define("<name>"`, until SDK 2.0 deleted `Activities.define` and the search found nothing.
  - **A project the old generator wrote keeps every file it wrote, and is told so once (2026-08-30).**
    `Activities.java`, `Parameters.java`, `Templates.java`, `ActivityRegistry.java`, `FlowDriver.java` and
    the whole `activities/` package are ordinary user files: nothing regenerates, reconciles or deletes them,
    `FileRole` classes them `EDITABLE`, and they keep compiling because every SDK type they name is kept by
    never-delete. The announcement is `SchemaMigrations` **activities step 2 → 3**, which writes nothing at
    all — it reads the source directory and returns a sentence. It is a numbered step for the one property a
    number buys: **the sentence is said once**, where a check on every open would become noise and stop
    being read. *Deleting them was considered and refused* — they work, and `Templates.java` is the only
    place a bot's picture names are written down.
  - **The lock machinery went on 2026-08-30, and what replaced it is one rule.** `MethodLock`,
    `GeneratedMembers`, `LockedRegions` and `core/component/MemberVisibility` are deleted and stay deleted;
    `FileRole` is a question about a path alone, and `LockResolver` refuses a bot open for **reading**
    (`ProjectMode`), a **read-only file role**, and **`main`'s signature**.
    - **`FileRole.GENERATED` came back on 2026-09-20, and only the file-level half.** The deletion's stated
      reason — *nothing generates a project's Java any more* — expired when a plugin's model became compiled
      code (`33-plugin-java.md`), and the user's own statement of the rule is the test: *not editable, it's
      modified here in the flow editor, not in the file*. It is recognised **by location only**, at
      `…/plugins/<segment>/<Name>.java`, exactly one package below `plugins`, mirroring `PluginData`'s
      resource scheme so the two cannot drift — narrow on purpose, so a user's own `plugins.helpers.deep.Util`
      stays theirs. A generated file is rewritten **whole**, so there is no partial grant to model and the
      four member-level mechanisms above have nothing to come back for. Each read-only role carries its own
      `reason()` beside its `badge()`, and `LockResolver` asks the role rather than naming one, so a role
      added later cannot be locked and left explaining itself as "library code". The explorer gets a third
      group, *Generated by BotMaker*, shown only when it has files.
    - **`main` is the one member-level lock left, and only its signature.** It is matched on the method's
      *shape* — `public static void main(String[])`, wherever the user has put it — rather than on
      `config.mainSourceFile()`, because the entry point is theirs to rename, move or split and a path-keyed
      rule would stop holding the moment they did. The signature is not a BotMaker convention but the one
      the JVM looks for, so renaming it, deleting it or retyping its parameter is the single edit whose
      consequence the user cannot read off the screen. **Its body is the user's**, and that is the point of
      the file: it is where the bot is put together, one static call at a time. Nothing is *installed* there
      and no plugin is registered — `PopupGuard.install`, `Bot.start` and `FlowGraph.run` are ordinary static
      API methods a user calls or does not. Deleting the file it lives in is not offered anywhere (the
      explorer has had no context menu since well before this).
    - What went with the machinery, deliberately: the **method lock badge** ("Generated - Read Only", "Your
      code goes here") and its `.method-block--yours` accent — every sentence it could say was about a method
      BotMaker wrote; the **pinned trailing `return`** in an activity's `run()`, which nothing may be
      inserted after; the explorer's **Generated by BotMaker** group and its hidden *derived* files, so every
      file the walk finds is now listed under **My code** or **Library**; and `CodeExecutionService`'s
      **locked-parts diff** before writing to disk, since a project file has no locked parts.
    - `Audience` and `ComponentResolver` stay: audience still decides which *components* of a block are worth
      showing. What it no longer does is drop whole members from the tree.
- **Studio creates projects that name no plugin at all (2026-09-04).** `services/MavenService` writes
  `BLANK_DEPENDENCIES` — `junit-jupiter`, and nothing else — plus the three repositories. Until this date
  every project it created pinned `com.github.LiQiyeDev:botmaker-sdk` and eight further entries serving that
  plugin's half of the jar, so *Blank* meant a bot project with a `System.out.println` in it and a user who
  wanted a plain Java project could not have one. The platform rule reaches project creation here: the SDK is
  one plugin among any number, and choosing it is the user's, one step away in **Project ▸ Manage Plugins**.
  - **The consequence is large and is the point**: a blank project has no palette, no plugin toolbar buttons,
    no pictures, no capture, no recorder and no pilot. That is what a project with no plugins installed looks
    like, and it was already reachable — it is what an existing project whose pom does not name the SDK has
    always done. What changed is that it is now the *starting* state.
  - **`BOT_DEPENDENCIES` is kept and unused by creation.** It is what a pom naming the SDK carries for the
    **bot's own source** — the SDK, JNA, Jackson, JUnit. `ProjectRepair` writes it, and so does a test
    building a bot-shaped fixture. It held five more entries until 2026-09-06 and every one of them was
    there on a *plugin's* behalf: the toolkit and both JavaFX artifacts went in the morning (transitive
    through the SDK, and parent-first in the loader, respectively), and javalin/zxing went with the
    `isSdk` branch — they are the SDK entry's `editorDependencies` now.
  - **`DEFAULT_GROUP_ARTIFACTS` is the union of both lists, and narrowing it would be a data-loss bug.** It
    classifies the pom of *any* project, not only one created today: a bot made before this date, and every
    project unpacked from a gallery template, carries the bot set. Left out of it, the SDK, the toolkit and
    JavaFX read as **user libraries** — offered for deletion in Manage Libraries, then genuinely dropped by
    `writeUserLibraries`, which keeps what `isDefaultDependency` recognises and discards the rest.
    `MavenServiceSdkTest` holds both directions. Two additions follow from that: `RETIRED_GROUP_ARTIFACTS`
    (coordinates the list *used to* write — recognised, never generated) and the rule that **any `provided`
    dependency is built in**, which is the only shape that can cover a set of coordinates the registry
    defines and Studio cannot enumerate.
  - **`readSdkVersion` answers `Optional`, and empty is ordinary.** It returned `SDK_FALLBACK_VERSION` for a
    pom naming no SDK, which was harmless while every pom named one and became a lie the moment a blank
    project could exist — its six readers resolve jars with that answer, offer upgrades against it and print
    it in an about box. Each degrades now: no docs, no surface index, no version reported, a null pin passed
    to `parameters(pin)` (which every plugin answers totally), and a blank passed back to
    `writeUserLibraries`, which only ever re-versions a dependency the pom already declares — so editing a
    blank project's libraries cannot make it acquire an SDK.
  - **A repair asks the source, not the recorded template.** `ProjectRepair.usesSdk` walks the project's own
    `.java` for `com.botmaker.sdk` and picks the pom shape from the answer. The recorded template cannot
    separate the two cases — every blank project ever made records `EMPTY`, and the ones made before this
    date pin the SDK — and the pom, which would have known, is the file that is missing. It is the one place
    in Studio that spells a plugin's package prefix, which `ImportManager.repairSdkImports` was deleted for;
    what makes it acceptable is that nothing is rewritten and no name is resolved, so being wrong costs one
    visit to Manage Plugins rather than a mis-repaired file. The guess is biased that way deliberately.
  - **New Project asks for no SDK version.** The combo, its JitPack fetch, the `(local build)` cell factory
    and the show/hide listener that toggled it against the template row are gone: blank has nothing to pin
    and a template brings its author's own pom. `MavenService.localSdkVersions()` survives with one caller,
    `ManageLibrariesDialog`, which is where a project's SDK version is chosen. `SDK_FALLBACK_VERSION` also
    survives — `release.sh` seds it on every `--sdk` release, and `ProjectRepair` still writes it.

This Studio repo is a submodule of the **`botmaker` umbrella repo**, whose aggregator `pom.xml` builds nine
modules in dependency order — `studio-api → plugin-toolkit → plugin-host → plugin-archetype → cli → shared →
session → sdk → studio` (see `../CLAUDE.md`). **All upstream changes go through their own umbrella
submodules** — edit there, commit inside that submodule, bump its pointer in the umbrella. Don't vendor any
of them inside this repo.

To try a local SDK change in a generated bot without pushing a tag, run `mvn -pl botmaker-sdk -am install`
from the umbrella root: `-am` builds shared and session first, so all three land at `0.0.0-SNAPSHOT`, the
version every consumer's pom defaults to. (`dev-install.sh` and the `local-SNAPSHOT` pin are obsolete and
deleted; the SDK's pom `groupId` is `com.github.LiQiyeDev` now, so a plain install already lands where a bot
resolves.) Studio lists locally installed SDK snapshots at the top of its version dropdown, labelled
`(local build)` and preselected, gated on `AppVersion.isDevBuild()`.

Releases are cut with the umbrella's `../release.sh`; **the maintainer owns the JitPack publish.**

A bot asks its user a question with the SDK's `Ask` (since 2026-10-01; `BotMaker.readX()` and its `BM-INPUT` stdout marker are deleted). The question is a `TelemetryEvent.Ask` frame on the run's telemetry socket: `runtime/RunTelemetry.publish` turns it into `InputRequestedEvent` (never relayed to plugins), `UIManager.promptForInput` shows a list, Yes/No or a text field by kind, and `InputAnsweredEvent` then `RunTelemetry.answer` writes the `Answer` frame back through `TelemetryServer.reply` (`CodeExecutionService` for a run, `DebuggingService` for a debug session). The bot validates and re-asks; a closed dialog answers `null`, a cancel. Stdout and stderr are plain text to `ConsoleBatcher`.
