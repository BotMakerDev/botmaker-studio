# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The architecture, its history and the reasons behind it are in `docs/architecture/`, one file per section
(moved there unchanged on 2026-10-05). **Read `docs/architecture/dated-notes.md` first**: its dated notes say
which older text in the other files is history now.

## Read before touching

| Touching | Read (`docs/architecture/`) |
|---|---|
| anything below, older than its newest dated note | `dated-notes.md` |
| the pom's dependencies, `PluginHost`, the catalogs, plugin upgrade, project creation | `sdk-and-shared.md` |
| opening a project, `ProjectConfig`, `ProjectState` | `project-lifecycle.md` |
| `CodeBlock`, statement/expression blocks | `block-system.md` |
| `BlockFactory`, source ↔ blocks round trip | `ast-block-sync.md` |
| `CodeEditor`, `parser/handlers`, `AstRewriteHelper` | `rewriting-pipeline.md` |
| `ProjectAnalyzer`, type index, suggestions | `suggestions.md` |
| `EventBus`, `CoreApplicationEvents` | `event-bus.md` |
| `ui/` packages, `UIManager`, windows and dialogs | `ui-structure.md` |
| `LibraryService`, user libraries | `library-management.md` |
| plugin registry, install, what left Studio for a plugin | `plugins.md` |
| capture (`services/capture/`), value editors and pictures that moved to the SDK plugin | `capture-and-moved-editors.md` |
| gallery, GitHub, publishing a bot | `sharing.md`, `tiers-and-publish.md` |
| New Project's templates | `templates.md` |
| diagnostics, `ErrorTranslator` | `validation.md` |

`docs/architecture/` holds history as well as rules; the umbrella `CLAUDE.md` and its `docs/refactor/` win
where they disagree.

## Planning

At the end of the planning stage, write the plan to a dedicated plan file before starting implementation,
so work can be resumed if a session is interrupted.

## Roadmap

`ROADMAP.md` (repo root) is the living backlog + changelog for the **Studio** (this repo only — the SDK and
shared modules each own their own `ROADMAP.md`). **After completing a meaningful change, update it:** add a
dated entry to the top of the **Completed** section (date — what changed — where), and check off / remove the
corresponding backlog item if it's now done. Keep entries to 1–3 lines. New backlog ideas that surface during
work go under the relevant backlog section.

## Commands

This is a **Maven** project (`pom.xml`) — there is no Gradle build.

```bash
# Build
mvn compile

# Run the application
mvn javafx:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=TypeAwareSuggestionTest

# Run a single test method
mvn test -Dtest=TypeAwareSuggestionTest#methodName

# Build a distributable (native app-image + installer)
mvn -Pdist package
```

From the **umbrella** root you can also run the Studio via the reactor: `mvn -pl botmaker-studio javafx:run`.
Tests run with JUnit Jupiter (Surefire).

**An IntelliJ Application run configuration builds its own command line and so carries none of the
`javafx:run` options.** That is where the startup warnings come from, not from the code. Paste into its *VM
options*:

```
--enable-native-access=ALL-UNNAMED,javafx.graphics --sun-misc-unsafe-memory-access=allow
```

The same two are in `pom.xml` (`javafx-maven-plugin` `<options>` and jpackage `<javaOptions>`), and
`Enable-Native-Access: ALL-UNNAMED` is in the shaded jar's manifest for `java -jar`. A bot's own JVM is a
third command line, built in `runtime/BotJvm`.

## Code Style

Prefer minimizing mutable state — favor a functional OOP style. Use immutable values (`record`s like
`ProjectConfig`, `UserLibrary`) and pure transformations; pass dependencies in via constructors rather
than holding mutable fields or reaching for static/singleton state. Keep side effects (file I/O, process
launching, event publishing) at the edges in the service layer.

**Don't re-derive what shared already models.** Studio consumes shared's types, so a label, key or probe that
shared can answer belongs there, not in a dialog. Concretely: use `EmulatorInstance.brand()` /
`PlatformId.displayName()` rather than a local id→name switch (Studio's own `brandOf` had silently drifted
from shared's naming), `EmulatorInstance.identity()` for any cache or de-dup key (never the display name —
instances routinely share one), and `Platforms.PlatformStatus.statusLine()` for the per-product summary. The
editor-side counterpart is `emulator/EmulatorProbe` (liveness, `screencap`, `installedApps`), shared by both
pickers so they can't drift on timeouts or failure handling.

**One conversion, one place.** `ScreenCaptureService.toFxImage` is the single `BufferedImage` → FX `Image`
path and is null-tolerant, so best-effort callers (a window that wouldn't capture, a stopped emulator) pass
their result straight through instead of keeping a private null-returning copy. It lives on `ScreenOverlay`
now and the service delegates.

**Add nothing to `ScreenCaptureService`.** New overlay behaviour goes on `ScreenOverlay`, which names no
capture target and nothing in `com.botmaker.shared`; anything that has to know what a capture target is goes
on `TargetCapture`. **Put nothing that decides anything in `services/ImageTemplateLibrary`**, a façade over
the SDK's picture folder. Why, and what else left for the SDK plugin: `docs/architecture/capture-and-moved-editors.md`.

## Setup

User projects live in `~/BotMakerProjects/` by default (not inside this repo), and **a project is identified
by its directory, not its name (2026-09-18)**: *Open Folder…* opens one anywhere, recents remember the path,
and `ProjectConfig.forDirectory` is the door. The list still scans only the default root — a folder of
repositories holds every other Maven project too — and a project elsewhere is found one remembered directory
at a time. Archive, restore and delete move folders, so they act only on the root's projects; an outside one
is *Removed from Recents*, never moved. The template (`../botmaker-gamebot`) is opened this way, and its SDK
upgrade goes through *Project ▸ Upgrade…*, not a hand edit. Each project is a standard **Maven** project with
the layout `src/main/java/com/<projectnamelowercase>/<ProjectName>.java` — or, for a template, the package of
its class holding `main`. The BotMaker-Studio app itself is also a Maven project (`pom.xml`): build with `mvn compile`, run with `mvn javafx:run`, test with `mvn test`.
