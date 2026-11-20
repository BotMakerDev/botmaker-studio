# BotMaker Studio

A bot watches a game or app on your screen, decides what it sees, and clicks. **BotMaker Studio** is the
desktop IDE you build one in: assemble the logic from visual blocks, and Studio writes real Java for you — a
standard Maven project you could open in any IDE.

Studio is a **plugin host**. It edits, runs, debugs and publishes Java; what a bot can *do* — see the screen,
click, launch a game, walk a map of the game's screens — comes from the plugins the project declares in its own
`pom.xml`. Studio bundles none of them. The usual one is the
**[BotMaker SDK](https://github.com/BotMakerDev/botmaker-sdk)**, added from **Project ▸ Plugins & Libraries…**.

Built with **JavaFX**. The Java itself is read and rewritten by the
[BotMaker core](https://github.com/BotMakerDev/botmaker-core), a headless process that owns the open bot, through
the **Eclipse JDT** AST; Studio is its client.

## What Studio does

- 🧩 **Visual block programming** — press **+** between two blocks and pick one from *Search blocks…*; every
  edit is a JDT rewrite of the real source, and an edit made in another editor comes back as blocks
- 🔍 **Type-aware suggestions** — a slot offers what fits its type, from your own code and every library on the
  classpath, indexed straight from bytecode (ClassGraph)
- ⚡ **Diagnostics and debugging** — JDT compiles as you edit and marks the failing block; **🐞 Debug** stops at
  breakpoints set in a block's gutter and shows the call stack and variables
- 🧭 **Navigation** — find usages, rename across the project, search text
- ✍️ **Overlay editor** — edit a method's blocks on top of the running game instead of beside it; plugins add
  their own tools to it
- 🎚 **Parameters** — the values the bot reads, in cards by group; each is a method of a `@Param` group, kept
  private to your computer, with the starting values whoever runs the bot gets
- ▤ **Reports** — every run keeps a log; a report shows what the run did, charts of what it read, and its
  problems
- 🕘 **Versions** — every change is kept; review what changed, go back to any version, sync with GitHub
- 🔌 **Plugins & Libraries** — add, upgrade and remove plugins and Maven dependencies, with live Maven Central
  search and no restart; *Dev mode* loads a plugin's local `-SNAPSHOT` build for one project
- 🚀 **Publish and get bots** — release a bot from your GitHub repository and list it in the catalog; run one
  someone else published, from the home screen's *Get more bots*
- 🤖 **Assistant** — a Claude window that reads and edits the open bot through the core, which also serves an MCP
  endpoint so an outside Claude session can do the same
- 🛑 **Stop key and run overlay** — a global key stops a run; plugins draw their status over it

## What plugins add

A project's plugins put their own types, editors, toolbar buttons and overlay tools into Studio, loaded from
that project's resolved classpath. The [BotMaker SDK](https://github.com/BotMakerDev/botmaker-sdk) is
plugin #1, and most of what makes a bot a *game* bot is its:

- the **game button** — the game this bot plays here (a Steam, Heroic, Faugus or Lutris game, an Android app in
  an emulator, a private display or a game VM), started without running the bot;
- ⌖ **Point bot at…** — the window, monitor or emulator the bot reads;
- ✂ **Cut picture** — the pictures a bot finds on the screen;
- 🗺 **Screens** — the map of the game's screens and the buttons between them, with 🎯 Goals (what the bot
  wants: facts, goals and plans), 🔭 Explore (finds screens not mapped yet) and 🧩 Elements (every picture
  and tree element) in its rail;
- vision and OCR, mouse and keyboard;
- 🎮 **Pilot** — watch and drive a run from your phone.

The SDK's [README](https://github.com/BotMakerDev/botmaker-sdk#readme) describes them.
[`botmaker-plugin-basics`](https://github.com/BotMakerDev/botmaker-plugin-basics) adds editors for the JDK
types (`Duration`, `LocalTime`, `Color`…). To write your own plugin, start from
[`botmaker-plugin-archetype`](https://github.com/BotMakerDev/botmaker-plugin-archetype).

## Download

Grab a self-contained build from the [Releases](https://github.com/BotMakerDev/botmaker-studio/releases) page. It
**bundles its own Java + JavaFX runtime**, so there's nothing else to install. Builds are per-OS:

- **Linux x64** — AppImage (updates itself from inside Studio), `.rpm`, `.deb`, zip and tarball;
- **Windows x64** — `.msi` and zip;
- **macOS (Apple Silicon)** — `.dmg` and zip. They are unsigned, so macOS warns on the first open; the macOS
  build is new and not yet tried on a real Mac.

### Linux: install from the package repository

Fedora/RHEL and Debian/Ubuntu can take Studio from a signed repository instead, so updates arrive through the
system package manager:

```bash
curl -fsSL https://botmakerdev.github.io/botmaker-studio/install.sh | sudo bash
```

That registers the signed repository and installs from it, on either distro. The script is
[`packaging/linux/install.sh`](packaging/linux/install.sh) — committed, attached to every release, and
published byte-for-byte; read it before piping it into a root shell if you'd rather.

To do the same by hand, the copy-paste snippets are at **<https://botmakerdev.github.io/botmaker-studio>**:

```bash
# Fedora / RHEL  (dnf offers to import the signing key on the first metadata read)
sudo curl -fsSL -o /etc/yum.repos.d/botmaker-studio.repo \
  https://botmakerdev.github.io/botmaker-studio/botmaker-studio.repo
sudo dnf install botmaker-studio

# Debian / Ubuntu
sudo install -d -m 755 /etc/apt/keyrings
sudo curl -fsSL -o /etc/apt/keyrings/botmaker.asc https://botmakerdev.github.io/botmaker-studio/botmaker.asc
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/botmaker.asc] https://botmakerdev.github.io/botmaker-studio/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/botmaker-studio.list
sudo apt-get update && sudo apt-get install botmaker-studio
```

The repository carries the **latest release only** — it's an upgrade channel, not an archive; older versions
stay on the Releases page. The AppImage remains the password-free option: it needs no root and updates itself
from inside Studio.

## Quick start

1. Launch Studio and press **＋ New bot** on the home screen. Start from **Blank**, or from a published bot
   (cloned at its listed release and linked to its author, so their later releases reach you as updates).
2. For a game bot, add the BotMaker SDK in **Project ▸ Plugins & Libraries…**. Pick the game with the game
   button, point the bot at its window, cut the pictures it looks for, and map the game's screens in 🗺 Screens.
3. Say what the bot wants in 🎯 Goals, write the work a press cannot say as blocks, and press **▶ Run**.

**[WORKFLOW.md](WORKFLOW.md) is the full walkthrough** — from an empty project to a published bot, plus a
diagram of how a run loops. The same text is in the app under **Help ▸ Getting Started**; both are rendered
from one source, so they cannot say different things.

Projects are standard **Maven** projects, under `~/BotMakerProjects/<ProjectName>/` unless you open one from
another folder with **Open folder…**. A bot using the SDK looks like this:

```
~/BotMakerProjects/MyBot/
├── pom.xml                       # a normal Maven pom; declares the plugins and pins their versions
├── starting-params/              # the parameters' starting values, which whoever runs the bot gets
├── src/main/java/com/mybot/
│   ├── MyBot.java                # main() — Bot.run(Sdk.class); a cloned bot keeps its class names
│   ├── Progress.java…            # your own classes: @Facts groups, Custom inputs, helpers
│   ├── params/                   # your parameters, one @Param group per class; private, never committed
│   └── plugins/com_github_botmakerdev/botmaker_sdk/   # the SDK's values (its groupId/artifactId)
│       ├── Sdk.java              #   the bot's SDK values: launch targets, …
│       ├── Screens.java          #   the screen map, one class per screen
│       ├── Goals.java, Plans.java#   what the bot wants, and in which order
│       └── Pictures.java, …      #   one constant per picture
├── src/main/resources/images/    # the pictures themselves
└── .botmaker/                    # Studio's own state for this project; never versioned
```

Every file is yours, and any of them can be edited by hand outside Studio. Studio writes a project's Java
only when it creates it. After that, a plugin's values (`@SdkValue` in `plugins/<groupId>/<artifactId>/`) and the
parameters change through the plugin's windows and **Project ▸ Parameters…**, not on the block canvas, which
shows them but refuses an edit. Studio rewrites only the expression a marked method returns, so comments and
helpers you add around it survive. The `pom.xml` is the single source of truth for dependencies, and
the classpath is resolved in-process with Maven Resolver: **no system `mvn` binary is required**.

## Building from Source

### Requirements

- **JDK 25** or newer (only to *build* — released app-images bundle their own runtime)
- **Maven 3.9+** (or an IDE that bundles Maven)
- **Linux, macOS, or Windows** (Linux is the primary development platform)

JavaFX is pulled in as a Maven dependency — no separate JavaFX SDK and no JavaFX-bundled JDK required.

### Clone & run

```bash
git clone https://github.com/BotMakerDev/botmaker-studio.git
cd botmaker-studio
mvn javafx:run
```

Common Maven tasks:

```bash
mvn compile     # build
mvn test        # run the test suite (JUnit Jupiter)
mvn package     # build the shaded (fat) jar under target/
```

Studio resolves the core, the contract, `botmaker-plugin-host`, `botmaker-shared` and `botmaker-session` from
JitPack. Working on those alongside it? Clone the umbrella repo and run `mvn -pl botmaker-studio -am javafx:run`
from its root instead, so the sibling modules are resolved from the reactor rather than downloaded.

## How It Works

Studio is the **client of the BotMaker core**, a process of its own that Studio starts when none is running. The core owns the open bot: it
parses the Java with JDT, applies every edit as an `ASTRewrite`, compiles, runs the bot in its own JVM, keeps the
history and serves the MCP endpoint. Studio sends it edits and draws what it answers.

Opening a project (`BotProject`) builds the per-project `EventBus` and the services that bridge it to the
core (`CoreEdits`, `CoreRuns`, `HistoryOnBus`, `PluginsOnBus`…), then the UI.

**Blocks** implement `CodeBlock` → `AbstractCodeBlock`, in three kinds: a `StatementBlock` (if, while, print,
declaration…), an `ExpressionBlock` (literals, identifiers, binary ops…), and the `{ … }` container,
`BodyBlock`. `BlockConverter` turns the core's parsed tree into blocks; an edit on a block goes to the core,
and the canvas redraws from the code it rewrote. **Run** starts a separate JVM; **Debug** attaches to it
over JDI with breakpoints set on blocks.

**Plugins** are read with `botmaker-plugin-host`'s `PluginLoader` from the project's resolved classpath, and
talk to Studio only through the contract, `botmaker-studio-api`. Studio never names an SDK or toolkit type.

For the architecture in depth see [`docs/architecture/`](docs/architecture), indexed by
[`CLAUDE.md`](CLAUDE.md); the changelog is [`CHANGELOG.md`](CHANGELOG.md).

## Packaging a Release

The `dist` Maven profile produces a self-contained app-image (bundled Java + JavaFX runtime) via `jpackage`:

```bash
mvn -Pdist package
# → target/dist/BotMaker Studio/   (run the launcher inside)
```

`jpackage` builds **only for the OS it runs on**, so run the profile on each platform you want to ship.
Releases are cut from the umbrella repo with `sluice` (`--studio <version>`); the tag runs
[`.github/workflows/ci.yml`](.github/workflows/ci.yml), which builds the app-image on Linux, Windows and
macOS, publishes the GitHub Release, and refreshes the apt/dnf repository behind the install one-liner above.

The build is tuned in ways that are easy to break by accident — a jlinked runtime with a hand-maintained
module list, host-platform-only native libraries, and hand-written GUI package dependencies for the `.rpm` and
`.deb`. Each is explained in a comment next to the thing it configures in [`pom.xml`](pom.xml); read those
before editing that profile.

## Project Structure

```
botmaker-studio/
├── pom.xml                            # Maven build (the Studio itself)
├── WORKFLOW.md                        # generated from studio/docs/Workflow.java — do not hand-edit
├── src/main/java/com/botmaker/studio/
│   ├── BotMakerStudio.java            # JavaFX Application
│   ├── Launcher.java                  # jar/app-image entry point (must not extend Application)
│   ├── blocks/                        # concrete blocks: expr/ flow/ func/ loop/ misc/ var/
│   ├── core/                          # CodeBlock hierarchy + component/ render/
│   ├── parser/                        # the core's tree → blocks
│   ├── plugin/                        # the host side of the contract: what a plugin is handed and reads
│   ├── project/                       # BotProject, migration
│   ├── services/                      # the bridges to the core, libraries, capture, overlay, terminal
│   ├── runtime/                       # the stop key
│   ├── nav/                           # find usages, rename, text search
│   ├── assist/                        # Studio's own MCP tools: the ui driver and its findings
│   ├── events/                        # per-project EventBus + CoreApplicationEvents
│   ├── docs/                          # Workflow + RuntimeDiagram: the one source WORKFLOW.md renders from
│   ├── ui/                            # app/ (shell, menus, windows, dialogs) dnd/ render/
│   └── config/, util/, validation/
└── src/main/resources/                # css/ fonts/ icons/ terminal/
```

## Troubleshooting

**No projects on the home screen**
*My bots* shows what sits directly under `~/BotMakerProjects/`, plus the projects you opened from elsewhere. A
project needs a `pom.xml` and a `src/main/java` folder. One kept in another folder is opened with
**Open folder…**, and is listed from then on.

**A plugin's types or toolbar buttons are missing**
Open **Project ▸ Plugins & Libraries…**. The Installed tab lists each plugin the project declares and says why
one did not load — most often a plugin built for a newer Studio than this one. **Reload plugins** reads them
again. Plugins resolve from JitPack, so the first fetch of a version needs a network connection.

**A plugin has a newer version**
The Installed tab offers **Upgrade to …** on its row (or **Upgrade all**). The upgrade repairs the calls that
changed, marks and lists what it could not, and leaves everything else you wrote untouched.

## Contributing

1. Fork and branch (`git checkout -b feature/my-feature`).
2. Keep the functional-OOP style: prefer immutable values and pure transforms; push side effects to the
   service layer (see [`CLAUDE.md`](CLAUDE.md) → Code Style).
3. Add tests (JUnit Jupiter) and run `mvn test`.
4. Sign the [CLA](CONTRIBUTING.md) when the pull request asks, then open it.

> **Note:** Studio loads only released plugin versions. To try a local plugin build, tick *Dev mode* in the
> project's *Plugins & Libraries*: it loads `-SNAPSHOT` jars for that project on this computer only.

## License

[PolyForm Noncommercial 1.0.0](LICENSE): free for any noncommercial use; any commercial use needs a license from
LiQiyeDev. Versions up to 1.13.1 were released under CC0 1.0.

## Acknowledgments

- **Eclipse JDT Core** — in-process Java parsing, AST manipulation, and compilation/diagnostics
- **ClassGraph** — fast bytecode-level type indexing
- **Maven Resolver** — dependency resolution without a system Maven
- **pty4j** — the Terminal tab
- **JavaFX** — desktop UI
- **Scratch / Blockly** — inspiration for block-based programming
