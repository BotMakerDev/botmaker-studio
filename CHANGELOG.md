# Changelog

What each released version of BotMaker Studio gives you, in a few bullets. `ROADMAP.md` stays the detailed
engineering log — why a thing was built, what was rejected, what it cost; this is the short answer to
*"should I update, and what changes for me?"*, and it is prepended to the GitHub Release notes for the tag.

**`release.sh` refuses to cut a version with no section here** (`check_changelog`, decide pass, before
anything is tagged). If the top section still says `## [Unreleased]`, rename it to the version being cut and
date it.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

### Added

- **Right-click a value to choose which plugin's editor draws it.** Two plugins may honestly both have an
  editor for a rectangle, and until now the winner was whichever one `ServiceLoader` reached first —
  silently, and in an order nothing guaranteed. The host collects every claimant and offers *Edit with ▸* on
  the slot; the verdict is saved per project in `settings.json` as `preferredEditors`, keyed on the fully
  qualified Java type so a choice made on a block also applies to that type's row in the Parameters window
  and to the pictures beside its declared choices. A verdict naming an uninstalled plugin is inert. Nothing
  crosses the contract — there is no priority a plugin can declare for itself, because the first one to write
  a big number would win forever. **The menu is on the canvas only**: persisting a verdict needs the
  project's settings service, and the Parameters window's editors are handed a `ProjectConfig` alone, so that
  window honours the verdict without being able to set one.
- `CodeEditorService.rerenderActiveFile()` — draws the open file's blocks again from the text already in
  memory, for the changes that alter *how* a block is drawn rather than what it says. Two callers: a re-bound
  plugin set, and an editor verdict.
- **The overlay editor draws a plugin row.** Every `ToolbarGroup.OVERLAY` item, built by the same
  `ToolbarItems` the main bar uses — so a plugin's button on the HUD is styled, tooltipped, icon-boxed and
  guarded identically. At 340px a second renderer would not merely look different, it would size differently
  and push the rest of the row off the panel.
- `PluginHost.itemsIn(ToolbarGroup)` — one filter with two readers, the main bar (every group but `OVERLAY`)
  and the HUD (only that one). A group nobody reads is an item silently absent, which is what
  `ToolbarMergeTest` exists to catch.
- `HostOverlayContext` — `HostActionContext` plus the three live facts an overlay item is handed: the title of
  the window the HUD is drawn over, where that window sits right now, and the editor's insertion cursor. All
  three are suppliers, because the window moves and the cursor changes while the HUD is up.
- **Right-click either toolbar to hide a group.** Six checkboxes, one per `ToolbarGroup`, saved per project in
  `settings.json` as `hiddenToolbarGroups`. It hides Studio's own items in that group as well as any
  plugin's — a group whose plugin buttons vanish while the host's remain is a group that lies about what it
  holds — and it is not an overflow policy: `OverflowBar` already answers *no room right now*, this answers
  *never*, and survives a resize. The set names what is **hidden**, so a group a later release adds shows up
  rather than being absent from every project written before it existed, and an enum name this Studio does
  not know is ignored rather than costing the user the rest of their list. The overlay HUD's row carries the
  same menu, because the HUD has no menu bar to get a group back from.
- **Recorded actions can land at the overlay's cursor again.** `ActionContext.insertAtCursor` is served here
  through `CodeEditor.pasteCode`, so an inserted statement brings its imports and goes through the same
  rewrite, undo entry and diagnostics refresh as any other edit. The loss recorded when the macro recorder
  became a plugin was accepted because a capability shaped to one caller is a back door; what answers that is
  a row several plugins can contribute to, not a better argument.

### Changed

- `PluginHost` remembers which plugin each slot editor came from (`ownedSlotEditors()`). `slotEditors()` is
  unchanged and still answers the flat list — derived from the owned one, so the two cannot disagree.

- **The overlay editor asks which window to draw over.** When no private session is live it offers every open
  window first, in the window manager's own order, then any remembered title not already there. **Nothing is
  persisted:** which window the *editor* draws on lasts as long as the HUD, while what the *bot* watches stays
  the owning plugin's `capture.json` — two facts with one shape, held by the two owners that actually have an
  opinion about them, rather than one fact spelled twice.

### Removed

- **Studio holds no capture target.** `services/capture/CaptureTarget`,
  `ScreenCaptureService.defaultTarget()`/`captureDefaultTargetAsync`/`forProjectFiles` and `TargetCapture`'s
  default-target supplier, its `ProjectSettingsService` constructor, its monitor/desktop/emulator branches
  and its ADB frame are all gone. The record was field-for-field the owning plugin's own capture-target
  model, and every one of those readers had answered "no target" since Studio stopped reading `capture.json`
  on 2026-08-31. The overlay editor resolves its own window live, and the *bot's* target is written by the
  plugin from the overlay's own row, so there is nothing left for a second spelling to hold. What Studio's
  capture service still does is what a caller with a window in hand asks for — raise, resize, capture,
  bounds, the title list — plus the screen, which a pick over several monitors now always asks about.

### Fixed

- **Opening the overlay editor with nothing to draw over is no longer a dead end.** It read the project's
  configured capture target, which Studio stopped reading on 2026-08-31 — so
  `ScreenCaptureService.defaultTarget()` had answered `null` for every caller since, and the HUD could only
  open over a live private session. It asks now. `ProgramShapeOverlay.open`'s `chooseTarget` parameter is gone
  with it: the Launch Target dialog it existed to call left for the SDK plugin on 2026-09-01, so its one
  caller had been passing `null` for eleven days. What replaces the affordance is better than what it lost —
  the dialog arranged a *launch*, while the question is which of the windows already open the user means, and
  a window they opened by hand was never reachable through it at all.

- **A plugin that will not load is now said out loud, in Manage Plugins.** When a plugin on a project's
  classpath cannot be loaded — most often because its own dependency is missing — Studio carries on with the
  plugins that did load, which is right: a project must open. But the only trace was one line in a log
  nobody reads, so *this project has no plugins* and *this project has a broken plugin* looked identical: an
  empty palette. The dialog now names each plugin that failed and why, and says plainly that the project
  itself is unaffected.

- **One broken plugin no longer hides the others.** A project with two plugins, the first of which failed to
  load, got **neither**. Each is loaded independently now.

### Changed

- **Installing a plugin declares what *that plugin* says it needs — not what Studio knew about one of
  them.** Some plugins need a library in the editor that they do not carry into your bot: the SDK's Remote
  Pilot needs a web server and a QR encoder, and a bot that never opens the pilot must not download either.
  Studio used to carry that list in its own code, for the SDK alone, so any other plugin needing the same
  thing simply did not work. The list is part of a plugin's registry entry now, Studio reads it there, and
  removing the plugin takes it back out. Nothing changes for the SDK: the same two libraries are declared,
  in the same place, at the same scope.

  Two consequences worth knowing. A plugin you built locally that the registry has never seen is installed
  on its own, because there is no entry to read a list from — publish it, or add the library through Manage
  Libraries. And **File ▸ Recover Project Files** rebuilds a lost `pom.xml` from what is on disk, which
  cannot include that list; a repaired project keeps its plugins and one visit to Manage Plugins puts back
  what they need in the editor.

- **A new project's `pom.xml` no longer declares the plugin toolkit or JavaFX.** Three dependencies left the
  generated list; two of them were downloads nothing ever used, and the third could break the editor. The
  SDK's own pom declares `botmaker-plugin-toolkit` normally, so it arrives with the SDK — and Maven prefers
  the *nearest* declaration, which meant a version written into your pom by whichever Studio created the
  project won over the one the SDK you pinned was actually built with. An SDK newer than that pin then hit
  `NoSuchMethodError` in the editor. JavaFX was never resolved from a project at all: Studio hands every
  plugin its own. What a project still declares for the SDK plugin is Javalin and ZXing, the two the SDK
  genuinely marks optional and nothing else supplies.

  **Existing projects are untouched and keep working.** The three entries stay recognised as built in, so
  Manage Libraries will not offer to delete them and editing your libraries will not drop them. Removing
  them from an older project is safe but not necessary.

### Fixed

- **A new project pins SDK 1.1.6, which is what 1.0.37 said it did.** That release's notes announced the
  pin and the constant behind it never moved — it still read `1.1.5`, the one SDK whose editor-side half
  cannot start on a machine without JavaFX. Projects created by 1.0.37 are unaffected in Studio itself,
  which supplies JavaFX either way; what they could not do is pass the plugin registry's own check.
  Existing projects are untouched — this is only what a *freshly created* pom declares, and
  **Project ▸ Manage Libraries** changes it whenever you like.

### Changed

- **The GitHub layer is `botmaker-shared`'s.** `GitHubClient`, `GitHubAuth`, `GitHubConfig` and `SemVer` are
  `com.botmaker.shared.github`; Studio changed import lines and nothing else. Sign-in, publishing, the
  gallery, the plugin registry and both updaters behave exactly as before. The move is for the coming
  operator GUI, which reads the same repositories and must not carry a second copy of the OAuth device flow.

## [1.0.37] — 2026-09-05

### Fixed

- **A plugin you install works straight away — no restart.** Its toolbar buttons appear as soon as the
  install finishes, and the blocks already on screen pick up the editors it brings. Removing one takes them
  away again the same way.
- **Installing the SDK from *Project ▸ Manage Plugins* now gives you a working palette.** It added the SDK
  and nothing else, and the SDK's editor-side pieces — the widget toolkit, JavaFX, the Pilot's server —
  are marked so that they do not travel with it. So the plugin could not load: no menu entries, no slot
  editors, no plugin toolbar buttons, and nothing on screen saying why. Installing declares those pieces
  now, and removing the plugin takes them back out.
- **The Install button tells the truth.** A plugin your project already has reads **Remove**, and pressing
  Install twice can no longer leave two copies of it in your `pom.xml`.
- ***Manage Libraries* no longer lists a BotMaker SDK your project does not have.** A blank project showed
  an SDK row with an empty version and offered to change it.

### Added

- **The `botmaker` command comes with Studio on Linux, and Studio keeps it current.** The `.deb` and `.rpm`
  now require it, so installing Studio installs the command-line tool from the same signed repository; and
  **Help ▸ Update Command-Line Tool…** checks its latest release and upgrades it through your package
  manager, behind one authorisation prompt that shows you the command first. Studio's own version does not
  have to move for a newer command to be offered. Note that a package file downloaded and installed **by
  hand**, without that repository configured, will now report an unresolved `botmaker` dependency — register
  the repository (`packaging/linux/install.sh`) or install `botmaker` first. Windows and macOS are unchanged
  and show no such menu entry.

### Changed

- ***New Project ▸ Start from* leads with the published templates.** *Base* was doing the same job as
  Studio's built-in *Blank*, so the two are no longer listed side by side: the templates replace *Blank*
  when the gallery can be reached, and *Blank* is what you get when it cannot — so creating a project still
  works with no network. Template names are shown capitalized.

### Removed

- **🗂 Resources and *Project ▸ Resource Manager…* are gone.** Both opened nothing — the window moved to
  🖼 **Manage Pictures** on the toolbar in 1.0.32, and these two were left behind pointing at nowhere. Use
  🖼 Manage Pictures; it does everything the old window did.

### Changed

- **A newly created project pins SDK 1.1.6**, which is the first release whose plugin half loads when the SDK
  is added to a project as a dependency — the arrangement every project has used since Studio stopped
  bundling a plugin. Nothing in Studio itself changed; see `botmaker-sdk`'s changelog for what the empty
  palette was.

## [1.0.36] — 2026-09-05

### Fixed

- **A new project pins an SDK that exists — third attempt.** `1.0.35` pointed at SDK `1.1.4`, whose own
  JitPack build then failed for a third distinct reason: an upstream module pinned a Maven plugin whose
  declared prerequisite is newer than the Maven JitPack runs, so it published nothing. This release points
  the constant at a tag that resolves. Nothing in Studio itself changed.

## [1.0.35] — 2026-09-04

### Fixed

- **A new project pins an SDK that exists.** `1.0.34` moved those constants to versions whose JitPack
  builds then failed for a second reason (the upstream compiler pin named a plugin version JitPack's own
  Maven is too old to run), so a bot created by it still could not resolve its SDK on a first build. This
  release points them at tags that are published. Nothing in Studio itself changed.

## [1.0.34] — 2026-09-04

### Fixed

- **A new project pins an SDK and a toolkit that can actually be downloaded.** Nothing in Studio itself
  changed. What changed is upstream: every BotMaker library published since 2026-09-02 was unbuildable on
  JitPack (their poms did not pin `maven-compiler-plugin`, so JitPack's default 3.1 built them with
  `source 5`), so a bot created by Studio 1.0.33 pinned an SDK that failed to resolve on the first build.
  This release moves the two version constants a generated pom carries to tags that exist.

- **Installing the SDK through *Project ▸ Manage Plugins* gives you a working palette.** Also an upstream
  fix — the SDK declared its widget toolkit `optional`, so a project that added the SDK got the plugin
  without the classes it extends and Studio loaded no plugin at all. See the SDK's own changelog.

## [1.0.33] — 2026-09-02

- **Studio runs on Java 25 (LTS) and JavaFX 25.0.4.** If you installed Studio from a release, nothing is
  asked of you: the installers bundle their own runtime, and jpackage now bundles a 25 one. If you build from
  source you need a JDK 25 or newer. The bots you create are unaffected by this — what they compile against
  is whichever SDK version their own pom pins.

## [1.0.32] — 2026-09-02

- **New projects declare what the editor needs to show you the SDK's own screens.** A generated `pom.xml`
  now lists the widget toolkit, JavaFX, the pilot's server and its QR encoder at `provided` scope. They are
  there so the editor can draw the SDK's pickers and open the Remote Pilot; `provided` means your *bot*
  still does not link any of them, so a headless bot is unchanged and downloads nothing extra at run time.
  Without them the 🎮 Pilot button failed with a missing class, and the SDK's slot editors were silently
  absent.
- **Studio ships no plugins of its own, and asks your project which ones it has.** The editor no longer
  carries a copy of the SDK. Every menu, value type and toolbar button you see comes from the plugins your
  open project actually resolves — so what you are offered is what your bot can really call, and a project
  pinning an older SDK is offered that SDK. With **no** project open there is nothing to ask, so type names
  are not recognised and plugin buttons are absent until you open one.
- **The Resource Manager is the SDK's 🖼 Manage Pictures now.** The window that renames, retags, replaces,
  deletes, imports and exports your bot's pictures moved out of the editor and onto the toolbar, beside ✂
  Capture Templates. It does everything it did, including rewriting the blocks that name a picture you rename
  or delete — that half never left. What left is the half that knew what a picture is called.
- **A plugin can rename something it owns and carry your code with it.** Whatever a plugin names in your bot's
  source, it can now find and repoint through the editor, in your open buffers as well as on disk, with the
  project snapshotted to Project History first and the changed functions marked for review when the meaning
  moved. It reaches files that do not currently compile, which is exactly the file a half-finished rename
  leaves behind.

## [1.0.31] — 2026-08-24

- **The files BotMaker generates for you come from the SDK your project pins, not from Studio.** Studio fills
  in what is true about your project — your activities, your flow, your stored parameters — and the SDK owns
  everything else. Two things follow for you: a generated file is written in the idiom of *your* SDK rather
  than of the Studio that happened to create the project, and a Studio older than your SDK still produces
  files that compile, because anything it does not recognise stays at the SDK's own default.
- **`FlowDriver` and `Activities` hold your project's data and nothing else.** The walk loop, the step budget
  and ~150 lines of parameter-parsing code are the SDK's now. Your two knobs, `MAX_STEPS` and
  `STEP_DELAY_MS`, are still in `FlowDriver` where you left them.
- **A duration reads the same in the editor and in the bot.** `1h30m` was parsed by two separate
  implementations — one in Studio, one written into the generated code — that nothing could compare. There is
  one now, and it is the SDK's.
- **Studio requires SDK 1.1.0 or newer to write a generated file.** An older bot still opens, and every file
  in it stays editable, buildable and runnable — but saving the Activity Flow, and *Recover Project Files*,
  ask you to run *Project ▸ Upgrade SDK…* first, by name and with nothing changed on disk. The upgrade
  re-renders `FlowDriver` and `Activities` in the new shape and does not touch your stubs, `GoHome` or
  `Popups`. New projects pin 1.1.0.
- ***Project ▸ Upgrade SDK…* opens with what the release gives you, not with what it costs.** The SDK ships
  its own `CHANGELOG.md` inside its jar, so the dialog shows every release you are moving through — newest
  first, in the author's words — above the cost sections. The exhaustive API diff is still there, below it.
- ***Project ▸ Upgrade SDK…* leads with what the release costs *your* bot, with file and line** — and now
  also with what the SDK's author said about each move, word for word, out of the `@ReplacedBy` / `@Replaces`
  pointers the SDK carries in its jar. A redirect that keeps its shape but changes what it does is marked for
  review instead of shipping silently, and additions are grouped by the version that introduced them.
- **A member that became two now asks you which one you meant, per call site.** `scroll(3)` and `scroll(-3)`
  in one bot want different answers, so the dialog lists every call with its own combo — already answered, so
  you can accept the whole thing untouched.
- **The palette is curated.** Studio offers the members the SDK's `@Palette` names, in the statement menu, the
  expression menu, both search views, the ⚙ overload picker and the method-name dropdown. A bot already using
  a member that is no longer proposed still renders, still resolves and still compiles — filtering applies to
  what is *offered*, never to what is *resolved*. A bot pinned to an SDK that predates `@Palette` sees exactly
  the menus it saw before.
- **Existing projects survive the SDK's package reorganisation**: `api.*` imports are repointed on open rather
  than opening as a wall of red.
- **Upgrading no longer stops half-way because of a file Studio wrote.** The generated files — `Activities`,
  `ActivityRegistry`, `FlowDriver`, `Templates` — are re-rendered against the new SDK after the version moves,
  using the same pointers that repair your own code, so a release that renames something they use goes
  through instead of refusing. Where the move genuinely cannot be expressed, the report says so **at the top,
  before you start**, rather than failing part-way through.
- **A new project is never created against an SDK it cannot compile against.** Studio checks the version you
  picked before it writes a single file, and says which member is missing so you can choose another version —
  instead of leaving a broken project behind.
- **Saving the Activity Flow is all-or-nothing.** Every generated file is produced and verified in memory
  before any of them is written, so a flow edit can no longer leave three files updated and a fourth stale.
  If it cannot be done at all, nothing on disk changes and the editor tells you which SDK member is the
  reason. (Reachable only on an SDK newer than your Studio; the fix is to update Studio.)
- A type the bot only *writes* (a field, a parameter, a cast, a type argument) is now seen by the upgrade
  report, and a defaulted value says what type it is.

## [1.0.30] — 2026-08-22

- Re-tagged against a new SDK. No source change.

## [1.0.29] — 2026-08-22

- Documentation only.

## [1.0.28] — 2026-08-22

- **The Linux packages install cleanly and are less than half the size**: the bundled runtime is jlinked
  rather than copied whole (rpm 241 → 126 MB, deb similarly), other platforms' natives are gone, and rpm/deb
  declare the GUI stack they actually need.

## [1.0.27] — 2026-08-21

- **One-command install** on Linux (`packaging/linux/install.sh`) instead of registering the repository by
  hand.

## [1.0.26] — 2026-08-21

- Re-tagged against a new SDK. No source change.

## [1.0.25] — 2026-08-21

- **Studio installs and updates from a signed dnf/apt repository**, so updates arrive through
  `dnf upgrade` / `apt upgrade` rather than a visit to the Releases page. An RPM upgrade no longer deletes the
  application menu entry.
- **Refactoring across files**: a signature you cannot change behind the project's back, a result type the
  body agrees with, every picker on one screen, and undo that spans files.
- The release is built from source at pinned upstream refs rather than from JitPack.

## [1.0.24] — 2026-08-04

- **A selectable UI theme** — Default, Dark, Black, High Contrast — from the View menu.

## [1.0.23] — 2026-08-02

- **The Activity Flow arranges itself**, and a new activity gets a dialog instead of a blank file.
- **The overlay draws over a live private session** and names the activity it records into; object capture
  zooms.
- **An eyedropper**, and one `Precision` editor that hides the knobs a given call cannot use.
- The bot's tuning became project settings rather than a generated `BotSettings.java`.

## Earlier

v1.0.22 and below predate this file. `ROADMAP.md` has the dated log.
