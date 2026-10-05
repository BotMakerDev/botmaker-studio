# Plugins — the registry answers "where is it", and installing is an ordinary dependency

**Project ▸ Manage Plugins…** (`ui/app/ManagePluginsDialog` over `sharing/PluginRegistry`) browses the
generated `index.json` in `botmaker-plugin-registry` and installs through **`LibraryService`**, the same path
Manage Libraries uses. That is the whole design and it is deliberate: `META-INF/services` says how the host
*instantiates* a plugin already on the classpath, so what is missing is only the **coordinate**, and once you
have one a plugin is a normal Maven dependency. A bespoke install path would be a privilege the bundled SDK
plugin has and a third party's plugin does not — the back door the platform exists to close.

- **A plugin that did not load is answerable, not only catchable (2026-09-06).** `PluginHost.failures()` is
  what the last `bind` could not load, rebuilt on every bind and cleared by `unbind`, and
  `ManagePluginsDialog.failureText` is the one line it becomes. Catching a broken plugin is correct — a
  classpath with no plugin on it is an ordinary state and must not stop a project opening — but until now
  being caught was the end of it, so *this project pins no plugin* and *this project pins a plugin that is
  broken* were the same empty palette. The formatting is static and pure so it is asserted with no scene
  (`PluginFailureTextTest`), the same split as `BlockTree`; what no test here can answer is whether the row
  is legible in both themes.
- **What a plugin needs in the editor is the plugin's to declare, not Studio's to know (2026-09-06).** An
  entry's `editorDependencies` — `groupId:artifactId:version` — are declared `provided` beside the plugin on
  install and taken back out on remove. They exist because `optional` means *not transitive*: the SDK's
  plugin half needs a web server and a QR encoder its pom marks exactly that way, so a project that
  installed the SDK got the jar and a pilot button that failed with a missing class. Studio held that list
  in `MavenService` as `if (isSdk(…))` until this date — **the one privilege plugin #1 had**, and the one
  `PluginRegistry`'s own javadoc says a platform must not grant. Two consequences: a **local build the
  registry has never seen** installs alone (there is no entry to read), and `ProjectRepair` rebuilding a
  lost pom writes none of them (they are not on disk), which costs one visit to Manage Plugins.
  `isDefaultDependency` recognises **any `provided` dependency** as built in for the same reason — the set
  of coordinates is open, so no list here could classify them, and the failure to avoid is Manage Libraries
  offering a companion for deletion and `writeUserLibraries` then discarding it.
- **Studio only ever reads the registry.** A plugin is submitted with `botmaker publish`, whose validator is
  the same code the registry's CI runs; those checks need the plugin's build, which a bot's editor has not
  got. There is no publish path here and there should not be one.
- **The version installed is the entry's `verifiedVersion`, not the newest tag** — the version the gate
  actually loaded and checked. Only an entry carrying none falls back to JitPack's newest.
- **Installed is decided by coordinate, never by version**, so a plugin pinned to an older version reads as
  installed; changing that version is Manage Libraries' job, and this dialog does not duplicate it.
- **Everything degrades to a sentence.** An unreachable registry is an empty catalog with a message in the
  list's placeholder, matching `JitPackSearch`; a catalog nobody can fetch must never block the editor.
- `PluginRegistry.Plugin` is pure and its rules (parse, `matches`, `isInstalledIn`, `isInstallable`) are
  tested headlessly — the same split as `BlockTree`. It ignores unknown JSON properties because **this Studio
  is the reader that lags**: a field a newer `botmaker publish` writes must not lose the whole catalog.

**The plugin author's loop is *Reload Plugins* plus `~/.m2`, and neither needs a release (2026-08-28).**
The SDK has always been testable without pushing a tag — `mvn install`, and Maven checks `~/.m2` before
JitPack — and a plugin now is too:

- **`LibraryService.reloadPlugins()` writes no pom.** The coordinate resolves to the same jar *path* before
  and after a rebuild, so there is nothing to write; what changed is the jar's **bytes**, and
  `PluginHost.bind` opening a fresh `URLClassLoader` over the same paths is the whole of what it takes to
  see them. It is deliberately not `updateLibraries(currentLibraries(), currentSdkVersion())`, which would
  rewrite the pom to say what it already says. *Project ▸ Reload Plugins* reports the plugins it found,
  because a reload that found nothing new looks exactly like one that did nothing.
- **`MavenService.localPluginBuilds()` finds them by the service file, not by a convention.** A candidate is
  a `*SNAPSHOT` directory in `~/.m2` whose jar carries
  `META-INF/services/com.botmaker.plugin.api.StudioPlugin` — the entry `ServiceLoader` itself reads — so
  nothing here keeps a list, a naming rule or a registry in step with anything. Gated on
  `AppVersion.isDevBuild()`, exactly like `localSdkVersions()`.
- **In Manage Plugins a local build *replaces* the registry's version for that coordinate**, rather than
  adding a second row: two rows for one artifact would offer two versions of it, and a developer who just
  built one wants the one they built. A local build nobody has published becomes a row of its own, at the
  top.

**Studio carried `botmaker-plugin-toolkit` at `runtime` scope from 2026-08-28 to 2026-09-02, and both the
defect that put it there and the reasoning that took it out are worth remembering.**

The defect: Studio's plugin #1 was the SDK, whose `SdkPlugin` extends the toolkit's `AbstractStudioPlugin`;
the SDK declares the toolkit `optional`, so it is **not transitive**, so Studio's classpath had no toolkit at
all. `ServiceLoader` threw `NoClassDefFoundError` while constructing it, `PluginHost.discover` caught it —
correctly; a classpath with no plugin on it is an ordinary state — and Studio ran with an **empty palette, no
name recognition and no SDK slot editors**, having printed one line to stderr. Nothing failed to compile at
any point. `PluginHostLoadTest` was the guard, every assertion in it "not empty", because empty is exactly
what that break looks like.

The removal: Studio bundles no plugin now, so there is nothing to construct and `PluginHostLoadTest` has no
subject — it is **deleted**, along with the dependency. The second justification the line briefly stood on,
*"the fallback copy for a plugin that brings none"*, does not survive reading the gate: `botmaker-cli`'s
`pom-scopes` check **refuses** a `provided` toolkit and **passes** a plugin declaring none, so a plugin either
brings its own at `compile` scope or needs none, and there is no third case for a fallback to serve. Its
refusal message already said so: *"the host does not have one to provide, because botmaker-studio must never
depend on it."*

- **`StudioSourcesTest` survives and matters more than before.** No Studio source may name a
  `com.botmaker.plugin.toolkit` type. It scans the *source*, not the classpath — which is what keeps it a
  real test now that the way to break the rule is to add the dependency back rather than widen a scope.
**The Remote Pilot left Studio (2026-08-30).** Fifth step of *Studio knows only the contract*, and the one the
toolbar surface was added for: 17 files and ~3,400 lines, plus the built web client under
`src/main/resources/pilot/`, moved to `botmaker-sdk`'s `internal/plugin/pilot/`, and Javalin and ZXing left
this pom with them.

- **What it needed from the host turned out to be four things, all already on the contract.** Which project
  is open (`resourcesDir`), a line in the status bar (`status`), the look and the owning window (`theme`,
  `dialogs().owner()`), and the bot as a process (`runs`). It held four editor classes for those — the event
  bus, `ProjectSettingsService`, `ProjectConfig`, `CodeExecutionService` — and **nothing was added to
  `StudioServices` to replace them**, which was the standing condition on this whole move.
- **The default capture target it asks for is the SDK's own `capture.json`**, read through `Authoring` by a
  small `PilotProject`. That is why the capture store moved first: while the list was in the editor's
  `settings.json`, the pilot could not have read it without a service on the contract for it, and a capture
  target is `CaptureSource`'s vocabulary, which is exactly what the 2026-08-27 `Assets` reversal refuses to
  put there.
- **Studio keeps one thing it used to get from the pilot**: the live session's host window, for the overlay
  to draw over. `StudioActions.liveSessionWindow` asks the project's own `BackgroundLauncher` — which is
  where it always was; `RemotePilotUi` was merely the only thing holding one.
- **`ProjectPreferences` lost the pilot token and port.** A plugin's state is the plugin's: they are in its
  own `java.util.prefs` node. An older preferences file still carrying the two keys reads fine.
- **The `pilot` Maven profile went with it**, so the web client is rebuilt by the module that serves it.
- The accepted consequence, stated plainly: **a project with no SDK plugin has no Pilot button.** That is
  the platform's own rule working, and it is worth meeting here rather than in phase 9.

**The capture targets stopped being stored twice (2026-08-30).** Fourth step of *Studio knows only the
contract*, and the first that changes where a project's data lives: the targets are the SDK's
`capture.json` now, read and written through `Authoring.readCapture`/`writeCapture`.

- **The disagreement it ends.** `settings.json` held the list a picker offered; `botmaker-project.properties`
  held the one `capture.source` spec a *running bot* resolves. Nothing synced them, and both files parse — so
  the editor and the bot could look at two different windows and say nothing about it.
- **`StudioProjectSettings` keeps both components and `@JsonIgnore`s them.** Every picker in the editor asks
  for `captureTargets`/`defaultTargetIndex` there and still does; only the file underneath changed. `read`
  takes them from `capture.json`, or — while that file does not exist — from the ones this settings file
  itself used to hold, so an older project opens configured and the next write moves them across. Once
  `capture.json` exists it is the answer, empty or not: a migration that resurrects a list the user emptied
  is worse than no migration.
- **`project/capture/CaptureTargets` is the one conversion**, both directions total. A spec in no recognised
  form reads back as the whole desktop, which is the SDK's own fallback. `WindowTarget.windowId` does not
  survive the trip, correctly — its javadoc already says a persisted live handle is meaningless.
- **The capture resolution followed them on 2026-08-31**, as `CaptureModel.reference`. The maintainer's
  framing settles it: *the reference resolution is a property of the SDK, not of Studio* — it describes the
  pictures, every one of which carries it in its sidecar, so the plugin that captures and matches them has to
  be able to read it. `StudioProjectSettings.Resolution` is **deleted** and its ~25 sites retype onto
  `CaptureModel.Resolution`, exactly as the four target shapes did the day before. **It needs its own
  migration read** (`legacyReference`), and that is the trap worth knowing: the targets' rule is *once
  `capture.json` exists it is the answer*, and applying it here would throw the size away for every project
  written in the one-day window when that file had the targets and `settings.json` still had the size.
- **`write` projects the default target onto `capture.source` in the same pass.** A bot cannot read
  `capture.json` (`Authoring` names the contract's value vocabulary, which is off a bot's classpath), so the
  properties key stays the bot's side — one writer, one direction, written with the list it comes from. **A
  project with no default is left alone rather than cleared**: `LaunchTargetDialog` and `EmulatorArgPicker`
  write that key directly for a project that has no target list at all.

**The capability layers the pilot stands on left Studio (2026-08-30).** Third step of *Studio knows only the
contract*, and pure preparation: nothing changed behaviour, ~870 lines left the repository.

- **`studio/emulator/*` → `com.botmaker.shared.emulator`** (`EmulatorProbe`, `EmulatorAppCache`,
  `EmulatorInstanceScanner`, and the three `*EmulatorSurface`). Their only Studio dependency was a cache
  directory, which went with them as `shared.config.CacheDirs`; `config/BotMakerDirs` delegates to it, so
  there is one cache root and not two.
- **`services/launch/BackgroundLauncher` → `com.botmaker.session.launch`**, where it always belonged: it
  named session and shared and nothing else. **Its `Platform.runLater` did not survive** — session has no
  JavaFX — so its callback arrives on the launcher's own thread and `QuickLaunch` and `NestedSessionLauncher`
  hop themselves. Same rule the plugin contract states for its listeners.
- **Why this looks like the wrong direction and is not.** Moving Studio code into shared raises Studio's
  shared *import* count; what matters is that the code is out of Studio, so the pilot can reach it from the
  SDK. An earlier attempt moved the emulator layer on its own, which really was the wrong direction, and was
  reverted — it belongs here, behind the feature that needs it.

**A plugin can reach the open project's bot (2026-08-30).** `plugin/HostRuns` implements the contract's
`Runs` over what Studio already had — the Run/Stop events, `CodeExecutionService.runningBotPid()`, and the
`EventBus` subscriptions for started/stopped/telemetry — and `HostServices.runs()`/`status()` expose it.
Second step of *Studio knows only the contract*, and the thing without which the Remote Pilot cannot leave.

- **Static and installed per project, like `PluginHost`**, because `HostServices` is built ad hoc from a
  `ProjectConfig` at three call sites with no event bus in scope, and Studio holds one open project.
  `BotProject` installs it after `CodeExecutionService` (which owns the process) and clears it in `close()`
  **after `PluginHost.unbind()`** — a plugin releasing something on the way out may well want to stop the
  bot, and a channel already cleared would silently do nothing.
- **`HostServices.runs()` reads the live channel rather than holding one.** An instance can outlive the
  project it was made for, and a held channel would let a stale editor start the bot of a project the user
  has left.
- **A plugin's listener goes in Studio's own list, not on the `EventBus`** — which has no unsubscribe, so a
  per-plugin subscription would leave one dead handler per project opened. The handle returned by
  `onStateChanged`/`onTelemetry` removes from that list.
- **Telemetry is re-encoded with `TelemetryFrame` and handed over as bytes.** Studio decoded it on the way
  in and holds a shared type the contract may not name; the frame is one definition of the format rather
  than a text rendering owned by neither end. A frame that will not encode is dropped, not reported: the bot
  is running, and one stale overlay beats taking the session down.

**A plugin is told when a project closes (2026-08-30).** `PluginHost.swap` calls
`StudioPlugin.projectClosing()` on the outgoing set — after the merge that could still refuse the new
binding, so a project that failed to bind has displaced nothing, and **before** the outgoing `PluginLoader`
is closed, since a plugin's release code is its own class and cannot run on a dead classloader. It is the
first step of *Studio knows only the contract*: the Remote Pilot becomes an SDK feature, and it holds a
bound port and a nested `:N` display that `UIManager.dispose()` releases today.

- **`serving` decides it, not `loader != null`.** A project whose own plugins failed to load is served by
  `BUNDLED` with no loader at all, and that set holds whatever it opened for that project exactly as a
  project's own would. Keying on the loader would leave the fail-open case — the one where a leak is least
  likely to be noticed — never told. So `bind` on an unopenable classpath swaps to `BUNDLED` and sets
  `serving`, rather than calling `unbind`.
- **`closeOutgoing` is total**, like every other pass over plugin code here: a plugin that throws on the way
  out loses what it held and costs nobody else theirs, and cannot stop the project that is opening.

- **It does not lock a plugin to Studio's version.** `PluginLoader` is parent-first only for
  `com.botmaker.plugin.api.**` and the platform namespaces, so the toolkit is child-first: a plugin carrying
  its own copy resolves its own, and Studio's is the fallback for one that brings none.

