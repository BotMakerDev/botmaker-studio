# Dated notes — read these before the other files here

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
