# Dated notes — read these before the other files here

> **A dev Studio can be driven over MCP since 2026-10-25.** `config/AppPaths` owns the per-user folders: with
> `BOTMAKER_HOME` set, the projects root (`Constants.PROJECTS_ROOT`), `UserDirs`' data and config, Java's preferences
> and `ProgramEntries`' folders all move under it (`AppPaths.apply`, in `BotMakerStudio`'s static block, before any
> of them is read); the cache does not. `assist/UiTools` is the `ui` tool over `Window.getWindows()`, served only with
> `BOTMAKER_MCP_UI=1` and never under `jpackage.app-path`. `assist/HomeEndpoint` serves it (with `quit_studio`) on the
> MCP port whenever no project's endpoint holds it; `AssistantPane` yields it before binding and hands it back after
> closing. The bridge's `start_studio home ui` sets both variables.

> **Studio opens on the bot list since 2026-10-25** (umbrella run-mode-bmk plan). `ui/app/home/RunHome` (My bots
> over Get more bots, one page; each card ▶ Run and *Edit in Studio*; ＋ New bot) is the startup screen unless
> `--project`; Browse Bots and `GalleryDialog` are deleted (`ui/app/home/GetBots`). The Run window is the old
> `RunnerWindow` with a status line (`StudioPlugin.readiness()`) and an update strip that keeps the runner's values
> (`RunnerValues`, key `_local/<folder>` for own bots). A bot's icon is `icon.png` at the repository root
> (`project/BotIcon`), set in Project Settings (`ui/app/botinfo/`). `sharing/BmkOpen`, `BmkEdit`, `BmkExport`,
> `UnpackedBots` open and export a `.bmk` (format: the cli's `com.botmaker.cli.bmk`); an unpacked bot's classpath is
> its `bot.json`, not the pom. The installers register `.bmk` (`packaging/bmk.properties`; on Linux the
> `packaging/linux/BotMaker Studio.desktop` override adds `%f`), and `sharing/ProgramEntries` keeps one
> programs-menu entry per runnable bot, only in an installed Studio (`jpackage.app-path`).

> **The catalog is read as the cli's `Catalog` record, schema 4, since 2026-10-24** (umbrella easy-publish plan,
> Phase 4b). `sharing/CatalogIndex` reads each row as `Catalog.Plugin` / `Catalog.Bot` (a row that does not read is
> skipped whole; one with no `releases` too — the schema-3 single `tag` is not read). `PluginRegistry.Plugin` and
> `GalleryEntry` carry `releases`, newest first; `tag` is the newest. Plugins & Libraries offers a listed plugin's
> approved tags only (`InstalledPlugin.approved`; JitPack is no longer asked for them). `plugin/ReleaseChecksums`
> (JitPack's `.sha1`, one tag) is replaced by `plugin/ReleasePins`: the jar and the pom beside it in `~/.m2` against
> the release's `jarSha256`/`pomSha256`, offline; a row vouches for every approved release. `PluginGate.admit`
> refuses, without asking, a release whose files differ ("changed after the catalog approved it") and one this
> computer saw approved that the catalog removed (preference `catalog.approvedReleases`; "removed from the catalog",
> naming the newest to upgrade to). A bot installs (`ProjectVcs.cloneAt` with the commit) and *Get vX.Y* merges only
> while the tag is at the pinned commit, and *Get vX.Y* offers the newest approved tag of a listed original.

> **Publish runs the cli's publish library since 2026-10-24** (umbrella easy-publish plan, Phase 4a). Studio depends
> on `botmaker-cli`'s main jar; `BotPublisher.Run` makes sure of the repository (REPO, Studio's own: made, public,
> `mine`), then runs `com.botmaker.cli.publish.Publish` — the steps and rules of `botmaker bot publish` — through
> `sharing/StudioPublishHost`, a `PublishHost` of JGit and Studio's sign-in that pushes to `mine` (a fork's line
> `studio/<branch>`), makes the release, checks the download and opens the pull request with
> `CatalogListing.open`, now only the transport of the cli's `CatalogPr.Change`. The local check is
> `sharing/StudioChecks` (rules 6 and 8 without Maven; the new CHECK row). The entry appends the release, pinned to
> its commit, and keeps every approved one. `CatalogListing.BotEntry`, `Change`, `refusal` (rules 3 and 5),
> `put`, `remove` and `pending` are gone; Unpublish is the cli's `CatalogPr.unpublish`. A retry runs the library
> again whole; it skips what is done. The note below is history for the steps it names.

> **The consent gate's four bypasses are closed since 2026-10-23** (umbrella catalog plan, Phase 7b, after a
> security review of Phase 6). In `plugin/PluginGate.admit`: a library with classes in an admitted plugin's package
> is left off (`packagesOf`, `shadows`); a row vouches only for the jar JitPack built at its tag
> (`ReleaseChecksums`: the jar's SHA-1 against JitPack's `.jar.sha1`, remembered per `groupId:artifactId@tag` in the
> preference `catalog.releaseChecksums`; never checked and offline = not the release); the user's own plugin is
> exempt only as their catalog row at its tag, no longer by `com.github.<login>`; and `installedHere` also needs
> `maven-metadata-local.xml` and no other `maven-metadata-*.xml` beside the jar.

> **Publishing lists a bot by the catalog's pull request since 2026-10-23.** `BotPublisher`'s `LISTING` step
> writes the description and the tags (as topics; `GitHubConfig.TOPIC` is no longer written, and was removed from shared the same day), checks rules 3 and 5
> on the release (`CatalogListing.refusal`), then `CatalogListing.put` opens or updates the pull request for
> `bots/<owner>__<repo>.json` on the branch `entry/<path>` — of the catalog itself for an account that can push
> there, else of the account's fork — as the cli's `CatalogPr` does through `gh`. `ListingStatus` reads it back
> (check run, `waiting` label, the `<!-- botmaker-catalog -->` comment, a refusal's annotations). Unpublish is
> `CatalogListing.remove`; *Stop following* and *Add remote…* `original` are `BotPublisher.setDetached`, the
> entry's `detached` by pull request. The `botmaker-detached` topic in the note below is gone.

> **Bots and plugins come from one catalog since 2026-10-23** (umbrella spec
> `docs/superpowers/specs/2026-10-23-catalog-design.md`): `sharing/CatalogClient` reads `catalog.json` once a
> session and keeps the last read in `UserDirs.cache()`; `CatalogIndex` turns it into `PluginRegistry.Plugin` and
> `GalleryEntry` rows, each with its listed `tag`, `PublisherRank` and stars. The topic search in the note below is
> gone. A bot's `trust()` is the lower of its rank and its plugins' (`pluginsRank`); `ShowCommunity` (one preference)
> hides community rows everywhere, and lists sort `RANKED`. `PluginHost.guard` installs `plugin/PluginGate` at start:
> before a jar loads, a community or unlisted plugin needs the user's yes (`CommunityConsent`, kept per
> `owner/repo#id`), and so does an official or trusted one at any version but its listed tag (per version, the
> user's call: a pom may serve another version from its own repository); a dev build skips it only when Maven
> installed it here (`_remote.repositories`). After construction, a plugin at a catalog coordinate whose `id()` is
> not its entry's is dropped. The questions are put before `bind` takes its lock. A host that installs no gate
> (tests, headless) binds as before.

> **A fork is a bot of its own since 2026-10-23** (umbrella `docs/refactor/39-versions.md` §2, §7, §8).
> `SyncModel.Ownership.FORK` (someone else's `original`, the user's `mine`) offers *Publish vX.Y…*, whose push tags
> `studio/<branch>` on the fork and marks the tag as the fork's own (`ProjectVcs.markOwnRelease`), so `releaseTag`
> still names the author's release. `GalleryEntry.basedOn` is read from a fork's `parent` unless it has the topic
> `botmaker-detached`, which *Stop following…* sets after removing `original`. An update compiles the merged tree
> (`runtime/TreeCompile`) before it commits. `ProjectMode.isYours` makes a fork the user owns editable.

> **There is no gallery repository since 2026-10-23.** `GitHubGallery.browse` is one GitHub search for the topic
> `botmaker-bot` (forks included); a result is listed when its newest release's pom declares a registry plugin.
> Publishing adds the topic (the plan's `LISTING` step), unpublishing removes it, and `HiddenBots` is this
> computer's *Hide* list. `catalog.json`, `index.json`, entry files, listing pull requests, `ListingStatus`,
> `GalleryMirror` and tiers are gone: read `sharing.md` and `tiers-and-publish.md` as history. Provenance is the
> `original` remote and the nearest tag (`BotSource.read`); nothing writes `botmaker-source.json` any more.

> **The type index holds `index/LibraryType` records since 2026-10-18**, not ClassGraph's `ClassInfo`.
> ClassGraph 6 removed `ScanResult.toJSON`/`fromJSON`, so `TypeSummaryManager` scans a jar, keeps what Studio
> reads of each public class (modifiers, supertypes, type-parameter count, public methods, constructors and
> fields with their generic and erased types, `@Deprecated`, `@Since`) and caches those records as JSON
> (`CACHE_FORMAT` `v3`). `ProjectAnalyzer.ResolvedMethod`/`ResolvedField.FromIndex` and
> `ResolvedType.FromIndex` wrap them. Read `suggestions.md`'s "consume ClassGraph directly" and
> `sdk-and-shared.md`'s `ClassInfo` as history; only `nav/LibrarySource`'s outline still asks ClassGraph itself.

> **Every migration is a `@Migrate` rule since 2026-10-16** (`docs/refactor/25-compatibility.md` §8, §12).
> `@ReplacedBy`, `PluginType.replacedBy()` and every pointer are gone from the contract, and with them
> `ApiMigrationRunner`, `Redirects`, `Pairing`, `UpgradeDiff`, `ApiReferences`, `FixList`/`FixSheet`,
> `ManagedSets.repoint` and `Modernise`: read `sdk-and-shared.md`'s text about pointers, pairing, candidates,
> per-site decisions and the report's breaks as history. The one engine is `migrate/`: `ApiCache` keeps the jar
> each plugin last loaded (so an upgrade and a same-`-SNAPSHOT` reload both have the old API), `Broken` lists the
> old elements the bot uses that the new classpath lacks, `Rules` resolves the bot's `@Migrate` methods by JDT
> bindings over the old classpath (its own signature names the element; `@Migrate.Self`, `kind`, `name` for a
> gone type), `Sites` (on `nav/Uses`) finds every use, and `Rewrite` substitutes each rule's body in place,
> each site checked to compile, a managed value copied beside its original (`@Migrated`). `Seed` writes a rule's
> skeleton marked `@Refactor("write the replacement")`; seeded rules are applied only as the last resort
> (*Upgrade anyway*, a removal). `Migration` is the façade for `ui/app/MigrateWindow` (Project ▸ Migrate…, the
> canvas banner, a Review row, the upgrade's *Migrate first*) and the assistant's `migration` tool (`list`,
> `seed`, `write`, `apply`, `copy_values`, `switch_uses`, `remove_migrated`, `remove_finished`). Studio never
> guesses a successor: with no rule, nothing is rewritten. `project/source/BotIndex`'s cache is keyed by each
> jar's size and mtime, so a `-SNAPSHOT` rebuilt in place is parsed again.

> **A managed value is marked by its plugin's own annotation (2026-10-10).** `@Managed("id")` is gone. A
> plugin declares an annotation meta-annotated `@ManagedMarker` whose `value()` is an enum nested in it
> (`@SdkValue(SdkValue.Id.FLOW)`); the id is that enum's binary name plus the constant.
> `project/managed/ManagedIds` reads and spells it through JDT bindings, or through imports in a unit without
> them, and nothing reads a string id any more. An add to an open set two classes carry is refused with both
> named (`ManagedSets.add`).
> Values are written as chains of named calls (`Flow.activity(…).described("…").goesHome()`): the grammar's
> `ValueWriter.linked` and `ExpressionReader.linked` read and write a type's declared withers and flags. An
> open set declared `ofEnum` is an enum in the bot: `ManagedSets` adds, renames and removes its
> `EnumConstantDeclaration`s by binding, `ManagedHolders` writes an empty marked enum implementing the
> element type, and `ManagedConstants` reads a constant as what the plugin's `byName` makes of its name. Read
> any older `@Managed("…")` text here and in the other files as history.

> **A type chooser goes back (2026-10-06).** `TypeChooser`'s draft pushes the type before each change (pick,
> wrap, unwrap). *↶* and Ctrl+Z with an empty search pop it. The stack is kept on the chooser across opens and
> cleared by `setType`, because a caller setting the type means another value. A dialog that rebuilds its
> chooser after a write (Edit Variable, Parameters) starts each chooser empty. On the canvas, `TypePicker.chip`
> passes each pick through `TypeChip.keepingArguments`: a same-arity generic picked with `Object` placeholders
> keeps the part's arguments. Each commit is one `CodeEditor.insert`, so it is one history step.

> **An upgrade asks every fix first (2026-10-06).** Before a pass writes anything, `InstalledPluginsTab.runPass`
> reads each row's report and turns it into `services/upgrade/FixList` issues: one per call site the move
> breaks, in source order, with the fixes the repair can write (`Decision`: the engine's answer, a split's
> candidates, a default value, delete the call) and one acknowledgement per gone type the bot writes down.
> `ui/app/FixSheet` walks them one at a time; *Upgrade* enables only when each has a fix, Cancel writes
> nothing, and the picks go to `ProjectUpgrade.run`. `ui/app/upgrade/ReportView` and its prose report are
> deleted; read `sdk-and-shared.md`'s text about it as history.

> **Installed has one button per row (2026-10-06).** `InstalledPluginsTab` rows open on the installed version;
> `actionLabel`/`actionTarget` decide *Upgrade to* / *Switch to* / nothing, and each click runs
> `ProjectUpgrade.run` with that one row and no picks (an unanswered site gets a default value and a review
> mark, as before). *Upgrade all* is the same pass over every row's upgrade. The tab no longer uses
> `ui/app/upgrade/ReportView`; Remove asks in `RemovalSheet`. Read older text about Check, seeded versions and
> *Snapshot, repair & switch* as history.

> **One plugin, one copy (2026-10-06).** A pom naming an artifact under both `com.github.LiQiyeDev` and
> `com.github.BotMakerDev` resolves both jars and both trees, and one `URLClassLoader` gives each class the
> first jar's answer — a mix. `plugin/DuplicatePlugins.split` runs after `ReleasedPlugins.split` in
> `PluginHost.bind` and keeps one side: the new groupId's when one of its plugin jars is loadable, else the
> old one's. `PluginHost.loaded()` records each bound plugin's jar. The Installed tab groups rows with
> `InstalledPlugin.sameArtifactGroups` (keeper: the loaded copy), offers *Keep <version>* (a pom edit,
> `LibraryService.removePlugin` with no editor dependencies), and lists plugins the pom does not declare
> read-only. `PluginRegistry.Plugin.isInstalledIn` counts the former groupId. Compiling and running the bot
> still see both jars until the pom keeps one: Maven, not Studio, builds that classpath.

> **Dev mode (2026-10-06).** `StudioProjectSettings.devMode`, a per-project, git-excluded setting, lets
> `PluginHost.bind(classpath, services, devMode)` load `-SNAPSHOT` plugin jars. `ReleasedPlugins.Split.dev`
> names them, and `EditorCanvas` shows them in a banner. It is switched with the *Dev mode* box in
> `PluginsWindow` or the Installed tab's *Use dev mode* (`LibraryService.setDevMode`). A dev-mode project is
> given `HostContract.devVersion()`, which is a source-run Studio's own SNAPSHOT. Outside dev mode,
> `ContractDependency.reconcile` moves a SNAPSHOT contract entry back to the released one. Publish still
> refuses SNAPSHOT pins. Read the 2026-10-03 note below as "outside dev mode". In dev mode only,
`services/LocalBuilds` (the `~/.m2` SNAPSHOT plugin scan, back from 2026-10-01 in a new class) feeds
`PluginCatalog.withLocalBuilds` in Browse and a *(local build)* version on each Installed row. Read the
2026-10-01 note below the same way.

> **A try is a run (2026-10-06).** `CodeExecutionService.runCode` and `tryCode` share one launch: a try compiles
> the project, then its caller (`services/trial/TrialCaller`) into `target/botmaker-trial/`, and runs that class
> instead of the entry class — same events, console, telemetry and Stop, no version taken. Both editors reach
> it through `ui/app/trial/Trials`; nothing writes a try into the bot's sources.

> **One desktop layer (2026-10-06).** The run overlay's click-through window left `RunOverlayWindows` for
> `ui/app/run/DesktopLayer`, which the overlay editor holds as well; a run's layer parts and the editor's
> `Marks` share it. `OverlayEditor.open` takes a `WatchedScreen.LiveSession`, not a session window id: a
> session is probed and picked on in its own pixels. Read the older "run layer" text below through this.

> **The overlay editor is a docked panel (2026-10-06).** `ProgramShapeOverlay`, `OverlayHeader` and
> `OverlayTargetPicker` are gone; `ui/app/overlay/OverlayEditor` coordinates a panel docked beside what the
> bot watches (see `ui-structure.md`). `project/managed/ManagedTargets` is replaced by
> `services/overlay/OverlayTargets` (targets by the plugin's declared type, read off bindings).
> `plugin/record/` (`Gestures`, `InputCapture`, `RecordingWriter`) and ⏺ Record are deleted, and with them the
> settings `preferredRecorders` and `lastRecordedActivity` (now `lastTarget`, a target key); `OverlayState`
> holds the panel's width. Read the older names below through this.

> **One window for the pom (2026-09-29).** *Manage Libraries*, *Manage Plugins*, *Reload Plugins* and
> *Upgrade…* are **Project ▸ Plugins & Libraries…** (`ui/app/PluginsWindow`): tabs **Installed**
> (`InstalledPluginsTab`, was `ProjectUpgradeDialog` — the only place a plugin's version moves or a plugin is
> removed), **Browse** (`BrowsePluginsTab`, was `ManagePluginsDialog` — install only; an installed row sends
> to Installed) and **Libraries** (`LibrariesTab`, was `ManageLibrariesDialog` — plugin rows held back, the
> SDK pin untouched), with Reload and the did-not-load line in the window. **Manage Imports is deleted**
> (every edit imports what it writes). `LibraryService.watchPom()` (`PomWatcher`) rebinds when `pom.xml`
> changes outside Studio; it compares with the pom last bound, so Studio's own writes do not rebind twice.
> Read the older names below through this.

> **No dev versions are loaded either (2026-10-03, the maintainer's call).** `plugin/ReleasedPlugins` leaves
> a plugin jar resolved at a `-SNAPSHOT` version off `PluginHost.bind`'s loader and reports it in
> `failures()`; the Installed tab marks the row and *Pin released versions* moves it through the checked pass.
> A non-plugin SNAPSHOT library still loads. `HostContract` writes `MavenService.CONTRACT_FALLBACK_VERSION` (a
> released tag the release bumps), never `DEV_VERSION`, which is deleted. So a plugin change is tried in
> Studio only after its release; read "pins its SNAPSHOT by hand and presses Reload" below as history.

> **No dev versions are offered (2026-10-01, the maintainer's call).** `MavenService.localSdkVersions()` and
> `localPluginBuilds()` are deleted, with `InstalledPlugin.Source.LOCAL_BUILD`, `PluginCatalog`'s merge (its
> `rows()` is the registry) and every `(local build)` label. A plugin author pins their SNAPSHOT in the pom by
> hand and presses Reload plugins, which still works. Publish refuses a pom with a SNAPSHOT or undefined
> `${property}` pin (`PublishRequest.unreleasedPins`), the contract pin a dev Studio writes
> (`HostContract.DEV_VERSION`) included. Read the *plugin author's loop* and *local build* paragraphs below
> as history.

> **The shell keeps one Scene (2026-09-29).** Every screen (selector, loading, editor, Runner) is shown by
> `StudioWindow.showOnShell`, which swaps the built scene's **root** into the scene already showing — never
> `setScene` after the first. A scene set on a maximized stage came up at the restored size, and the
> maximize-flag toggle that re-filled it could leave KWin un-maximized (reproduced under a nested
> `kwin_x11` with `GeometryTrace`, which `BOTMAKER_TRACE_GEOMETRY=1` turns on). Hang handlers on a screen's
> root, not its scene.

> **Find (2026-09-29).** Navigate ▸ Find… (Ctrl+F, `ui/app/FindBar`, floated over the canvas by
> `EditorCanvas.overlay`) and Find in Project… (Ctrl+Shift+F, `NavigationPopups.findInProject`) search the
> **source text** (`nav/TextSearch`, case-insensitive) and land on the block owning the offset
> (`SourceNavigation.blockAtOffset`); matches are re-read on every step, since the bot may have been edited.

> **SDK 2.0.0 (2026-09-23) changed two things the dated sections below describe.** (1) The SDK's plugin half
> is `com.botmaker.sdk.plugin.*` now: read `sdk.internal.plugin.X` below as `sdk.plugin.X`, with `capture`
> split into `plugin.screen` and `plugin.source`, `templates` into `plugin.pictures`, `internal.authoring`
> into `plugin.types` (`docs/refactor/34-plugin-package-tree.md`). (2) **The recorder is Studio's again**:
> `plugin/record/` (`Gestures`, `RecordingWriter`) records input, picks the plugin method annotated
> `@Records(Gesture, rank)` and fills it by type (plugin-host's `Recordings`), writing the statement through
> the grammar. The SDK's `internal.plugin.record` (`MacroRecorderDialog`, `MacroTranslator`) is deleted, and
> `ActionContext.insertAtCursor` with it.

> **A value's type is a `java.lang.reflect.Type` since 2026-09-24, and `ValueForm` is deleted.** Read
> `ValueForm.Leaf` below as the plugin's own `Class`, `ValueForm.Of` as `ValueTypes.Parameterized` (a real
> `ParameterizedType` over `List`/`Map`/`Map.Entry`), `ValueForm.Declared` as `ValueTypes.BotClass`, and an
> unknown leaf as `ValueTypes.Unknown` (display only). A written type becomes one **once**, in
> `project/source/ValueTypeResolver` — from the JDT binding when the unit was parsed against the project's
> classpath (`project/source/BotParser`), otherwise through the unit's imports (`plugin/grammar/SourceNames`,
> the JLS rules). `ValueGrammar.qualify`, `type(String)` and `containerForJava` are gone; `named(canonical)` is
> the one exact lookup. `@Param`/`@Managed` are identified by class (`project/source/BotAnnotation`). No
> suffix match (`endsWith("." + name)`) is left on the value path; an expression parsed on its own
> (`SourceNode.parse`, marked `JavaExpressions.detached`) still matches simple names, until values stay
> attached to their unit.

> **A value is written as a tree since 2026-09-24.** Read `ValueGrammar.Written` / `spell` / `initializer`
> below as returning a `plugin/grammar/JavaValue` — an `Expression` node the grammar built
> (`plugin/grammar/ValueWriter`), its imports, and a formatted `source()` for display only.
> `initializerOfParts` is `ValueGrammar.compose(Type, List<JavaValue>)` (and `BotRecords.compose`). Sinks copy
> the node (`JavaValue.copyInto(ast)`): `JavaParameterEdits.setValue/retype/add`, `JavaManagedEdits.setValue`,
> `CodeEditor.replaceWithValue`, `setTrailingArguments(call, from, List<JavaValue>)`,
> `CodeEditor.insertStatement` (recordings). **Never write a value with `createStringPlaceholder`** or join
> Java as text; build nodes. An editor cell reads back `Supplier<Optional<JavaValue>>` (`ValueEditors.Editor`,
> `ParamValueWidgets.ValueEditor`), and `HostValueContext` holds the value set plus its tree
> (`current()`), with `onChange` a `Consumer<JavaValue>`. Still text, and not on the value path: the canvas's
> clipboard paste, the `java.time` Date/Time pickers and `ExpressionMenu`'s raw code
> (`replaceWithRawExpression`), and the palette's fresh-value strings (`PluginHost.freshInitializer`).

> **A plugin's editor asks about a class and gets an `Executable` since 2026-09-24** (studio-api 0.3.0,
> `docs/refactor/36-bound-values.md`). `plugin/HostTypes` is the one bridge: a `TypeRef` from the
> `ITypeBinding` (erasure's binary name plus every supertype), from a ClassGraph `ClassInfo`, or from a
> binary name loaded on `PluginHost.classLoader()`; a bare simple name is unresolved. A slot's call is the
> `IMethodBinding` (`PickerContext.call()`, `MethodInvocationBlock.callBinding()`), loaded as an
> `Executable` on the plugin loader by name and erased parameter types. Read `enclosingClassName` /
> `enclosingMethodName`, `className`/`methodName` on `PickerContext` and `TypeRef.simpleName/qualifiedName`
> below as gone. `HostValueContext.typeName()` is the *Edit with* key the canvas also uses.

> **Every name in the bot is found, renamed and refused one way since 2026-09-27** (umbrella
> `docs/refactor/36-bound-values.md`, *Names in the bot*). `project/source/BotIndex` is the whole bot parsed
> once over the buffers; `nav/Usages` is the only "where is this used" (`across` by binding key, `local` for a
> local variable); `nav/Refactor.rename` is the only rename of a field, method, type, enum or constant, and a
> plan that would not compile is refused; `ui/app/RefusalDialog` is the only refusal window. Read
> `project/managed/MethodReferences`' and `parser/refactor/MethodReferences`' "syntax only, no bindings"
> below as history: the second finds calls and `Collect::body` references by binding, and the first is
> `project/managed/ManagedTargets`, which resolves each reference to the file javac says. The Parameters,
> managed-value and record scans read `BotIndex`'s units too (`JavaParameters.over`, `JavaManagedValues.over`,
> `BotRecords.over`); `JavaParameterSource.parse` is `BotParser.syntax`, and `ManagedHolders` writes the holder
> as a JDT unit. Never add a rename that walks names by spelling, a second parse of the whole bot, or Java
> joined as text, and never refuse an edit through a bare status line or `Alert`.

> **A plugin upgrade or removal is never refused (2026-09-29, the maintainer's rule).** Read every "refuses",
> "blocks the upgrade", "all-or-nothing" and "Nothing has been changed" below under `services/upgrade/` and
> `ApiMigrationRunner` as history. `ApiMigrationRunner.Outcome` is `(files, leftAsWritten)`: a file it cannot
> repair is left as written and named, and so is a single site (a removed `void` call in a one-line lambda).
> Bundled library source is reported, never rewritten. `BreakKind.TYPE_REMOVED` is `Break.leavesWork()`: its
> calls are defaulted, and each place writing the type is left, marked `@Refactor` and listed
> (`Repairs.goneTypes`). `Report.leftForYou()` replaced `unrepairable()`, and `canMigrate()` is "there are
> breaks". `problems()` (unparsed files, two plugins declaring a name, whose names are then left out) informs
> and never stops. `ProjectUpgrade` defaults every unanswered waiting site (`withDefaults`), and says what was
> left in `Result.leftForYou()`. `PluginUpgradeService.repairReporting`/`repairRemovalReporting` return
> `Repaired(files, leftAsWritten)`. Apply is grey only when no row moves or a check is running.

> **`services/SdkSurfaceService` is deleted (2026-09-28).** Its presence half intersected the palette catalog
> with the classes the type index found, which filtered nothing once the catalog came from the project's own
> plugins, loaded from the same jars. Read every mention below as: the facades are `PluginHost.menuFacades()`
> / `facadeNames()`; what a menu *offers* on a catalogued type (`@Hidden`, by member name) is
> `plugin/PaletteCuration` (`isOffered`, `retainOffered`, `retainOfferedNames`, read from
> `PluginHost.catalogFor()` at the point of use — no parameter is threaded any more); a member's
> `@Deprecated` is `ProjectAnalyzer.isMemberDeprecated`. `CodeEditorService.sdkMenuFacades`/`sdkFacadeNames`/
> `isSdkMemberDeprecated`/`getSdkSurface` are gone, and `sdkVersion`/`missingFacades` had no caller.

> **Studio names no plugin in its menus or docs (2026-09-28).** `SdkDocs`/`SdkDocsParser`/`SdkDocsService` are
> `palette/ApiDocs`/`index/ApiDocsParser`/`services/ApiDocsService` (`getApiDocs()`): the docs come from **every
> bound plugin's** `sources` jar — found beside the jar the plugin's class was loaded from, else resolved by
> the jar's `pom.properties` coordinate and its repository directory's version — restricted to
> `PluginHost.cataloguedPackages()`, merged (first plugin wins a simple-name clash).
> `MavenService.resolveSdkSourcesJar` is deleted. The menus' `SdkCall`/`sdkCalls`/`isSdkFacadeCall`/
> `appendSdkFacadeExpressionSubmenus`/`collectSdkFacadeLeaves` are `FacadeCall`/`facadeCalls`/`isFacadeCall`/
> `appendFacadeExpressionSubmenus`/`collectFacadeLeaves`. The SDK's coordinate constants
> (`SDK_GROUP_ID`, `SDK_FALLBACK_VERSION`, `readSdkVersion`, …) stay: they create and repair projects that
> name the SDK, and the release tooling edits `SDK_FALLBACK_VERSION` by name.

> **`HostSources` and the contract's `Sources` are deleted (2026-09-28).** Every mention below of a token
> needle, `HostSources` or `Sources.replace` is history. A plugin's open set (`@Managed` on a class —
> `Pictures`) is changed through `HostPluginValues`' `members`/`open(id, member)`/`add`/`uses`/`rename`/
> `repoint`/`remove`, planned by `project/managed/ManagedSets` over `BotIndex` (rename is `Refactor.rename`),
> compiled as the whole bot, written after one Project History snapshot with the open file redrawn.

> **`settings.json` is `<project>/.botmaker/settings.json` since 2026-09-26** (`ProjectConfig.studioRoot()`,
> `SchemaFile.dirOf`). Every mention below of it "in `src/main/resources`" is the old place: an old project's
> file is moved once, first thing on open (`StudioProjectSettings.moveOutOfResources`, from
> `ProjectSchema.check`), and `ProjectVcs` keeps `/.botmaker/` out of git through `.git/info/exclude`.
> The explorer is a folded folder tree over `ui/app/ExplorerModel` since the same day, not the flat list
> described below.

> **No `botmaker-project.properties` since 2026-09-27.** Studio writes, reads, repairs and migrates none of it
> (`SchemaFile` is `SETTINGS` alone): a bot's tuning is the SDK's `@Managed("settings")` value, edited in that
> plugin's ⚙ Bot Settings (Studio's `BotSettings`, `SessionSetting`, `BotSettingsDialog`, the 🐞 Debug toggle and
> 🖱 Input are deleted); what this machine launches is a **run property** — `Runs.property/setProperty`, kept in
> `StudioProjectSettings.runProperties` (git-excluded) and passed to every run and debug JVM as `-D` by
> `BotJvm.options`; what the bot was tested on is the Publish sheet's checkboxes, into the gallery entry only.

> **The centre column is `ui/app/CenterTabs` since 2026-09-27**: the canvas's tab, never closed, plus one tab
> per thing opened only to be read — a file through `ui/app/viewers/ResourceViewers` (picture, JSON tree,
> properties table, text; view only, parsed by `Image`/Jackson/`Properties`, never by hand), or a library class
> through `LibraryClassView` (Go to Definition; `LibrarySourceWindow` is deleted). A library class is drawn by
> `BlockConverter.convertReadOnly` into a block map of its own — never `ProjectState`'s, never through
> `LockResolver`, which speaks for the open project file — and its functions fold with
> `MethodDeclarationBlock.collapseHere`, which builds a body on first open and writes no fold into the project.
