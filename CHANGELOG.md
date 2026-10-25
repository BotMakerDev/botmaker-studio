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

- **Deleting a branch deletes it on GitHub too** (2026-10-25): *Delete…* of a branch your copy has asks first,
  with *Also delete it on GitHub* ticked. A `studio/<branch>` line merged on GitHub is deleted there, and pushes
  go to the branch again.
- **The remotes are `origin` (your copy) and `upstream` (the author's)** (2026-10-25), git's usual names; a
  project's `mine` and `original` are renamed when it opens.
- **A branch switch reloads only when the files changed** (2026-10-25).

- **A sandbox home for trying Studio** (2026-10-25): `BOTMAKER_HOME=<folder>` (or `-Dbotmaker.home`) keeps the
  projects, `botmaker-config.json`, the bots opened from a file, the settings, the preferences and the
  programs-menu entries under that folder, so a test never touches the user's own bots. The cache, which holds
  the GitHub login and `mcp.json`, stays the real one.
- **The `ui` MCP tool, dev Studio only** (2026-10-25): with `BOTMAKER_MCP_UI=1`, the MCP endpoint serves `ui`,
  which lists Studio's windows, reads what one shows (a label its width cuts short says `cut`), presses and types,
  takes a picture and waits for a text. It answers on the bot list and in the Run window too, where no project's
  endpoint runs. An installed Studio never serves it. `read` names the menu bar's menus and lists their items
  only with `menus: true`; with no window named, `read` and `look` take an open menu first; and a press that opens
  or closes a project answers what it pressed, since the endpoint now closes only once the press has answered.
- **Get runs a bot; Edit in Studio gets its code** (2026-10-25): *Get* on a Get more bots card downloads the
  release's `.bmk` that the catalog checked, opens it, and opens its Run window, with no second warning. It shows
  the download's progress on the card. *Edit in Studio* clones the release, linked to its author, as *Get* did
  before. A bot with no checked `.bmk` yet offers only *Edit in Studio* and says so. A bot already in My bots says
  *In My bots*, matched by its author's repository, not by its name.
- **⤴ Add shortcut in the Run window** (2026-10-25): a shortcut on the desktop and an entry in the programs menu
  that open this bot's Run window. It then reads *✓ Shortcut*, and a click offers to remove it. With *Add my bots
  to the programs menu* off, the menu still holds the bots that have a shortcut, from the next time My bots is
  shown. A file of yours under the bot's name on the desktop is never replaced or deleted. A development run greys
  it out.
- **Select several bots in My bots** (2026-10-25): *Select* in the My bots heading puts a check box on each card.
  A bar at the bottom counts them and offers *Archive*, *Remove from My bots* and *Cancel*. Each button acts on
  the selected bots it applies to: Archive on Studio's own projects, Remove on the rest. *Done* leaves.
- **A new loading page** (2026-10-25): the bot on the left (its icon, name, author, version and description),
  the open's steps on the right — Dependencies, Libraries, Project, Files, Editor — each ticked with the time it
  took, the current one with its own bar. A failure is said on the page, with *Try again*, *Back to My bots* and
  *Details*, never an error window.
- **＋ New bot and Get go straight to the loading page** (2026-10-25): its first step is *Copying*, or
  *Downloading* with a percentage and the size.
- **A branch switch that changes files keeps the editor in view** (2026-10-25), dimmed under a small box:
  "Switching to *branch*…", a bar and how many files change.

### Fixed

- **Every open goes through the loading page** (2026-10-25): a `.bmk` file opened from `--open`, a double-click or
  a drop unpacks on its first step, *Unpacking*, then the Run window opens; My bots no longer shows only
  "Opening <file>…". *Edit in Studio*, on a Get more bots card or a run copy, clones on the *Copying* step instead
  of a "Getting its code…" line on the card. Cancelling the trust sheet goes back to My bots.
- **Nothing on a card is cut** (2026-10-25): every card in My bots, Get more bots and New Project is 280 px wide,
  and a long name or description wraps. New Project opens at 940×680, three cards a row, smaller on a smaller
  screen.
- **Words, not records or ids** (2026-10-25): Versions ▸ Dev's remote list reads `upstream — LiQiyeDev/Firestone
  · 4 versions to pull`; a bot opened from a file is called by its name, not its version folder ("1.0.2").
- **A development Studio says its own version** (2026-10-25), the pom's, not v1.0.5.
- **Every button is in English** (2026-10-25): *Cancel* and *Warning*, not the computer's language; numbers and
  dates keep its format.
- **Archiving a bot whose name is already archived** (2026-10-25): it is kept as *Name (2)* instead of failing
  with a bare path, and restoring it gives back its own name when that is free. A failed archive says why in a
  sentence. Archiving, restoring or deleting leaves no empty `.archive` folder.
- **My bots forgets what is gone** (2026-10-25): an archived or removed bot leaves the recents. A folder of
  Studio's that is gone is dropped from `botmaker-config.json` when it is read, so a new bot in the same folder
  starts with no old *Used* date. A gone folder of yours elsewhere still shows its card. A removed bot opened
  from a file no longer comes back as a "This bot's folder is gone" card.

- **The plugin prompt of a bot opened from a file names the plugin** (2026-10-25): a plugin jar packed in the
  bot's `lib/` was named after the bot's tag folder ("1.0.0 lib") and checked under no coordinate. It is now
  named by its `bot.json` line, for example `botmaker-sdk 2.0.0`, and the catalog's check finds its row.
- **Export as .bmk starts in your Downloads folder** (2026-10-25): the XDG download folder, else `~/Downloads`,
  else the desktop, else the home. It no longer starts in the project folder, where the file was swept into the
  next version. `*.bmk` is ignored in every project: in the `.gitignore` of a new one, and in the local
  `.git/info/exclude` of every one, so an older project's `.gitignore` is left as its author wrote it.

- **Export as .bmk packs only the jars Maven Central does not serve** (2026-10-25), as `botmaker bot export` does:
  a Central jar under a `com.github` groupId is listed, so the catalog no longer refuses the release.

## [1.8.0] — 2026-10-25

### Added

- **A `.bmk` opens with a double-click** (2026-10-25): the deb, rpm, msi and dmg register the file type
  `application/x-botmaker-bot`, and Studio opens the file it is given on its home, the trust sheet first.
- **My bots in the programs menu** (2026-10-25): each bot that can run gets an entry in the system's programs menu
  that opens its Run window, with its icon: Linux `~/.local/share/applications`, the Windows Start menu, macOS
  `~/Applications/BotMaker bots`. A bot removed from My bots loses its entry. The home's *Add my bots to the
  programs menu* turns them all off. Installed Studio only; `--run=` also takes a bot's folder.

- **Publish attaches the bot's `.bmk`** (2026-10-25): after the release, the bot at its tag is written as a `.bmk`
  and attached to the GitHub Release, and the catalog entry pins its sha256; the catalog's check rebuilds it from
  the commit. Publish refuses an `icon.png` that is no PNG or is over 512 KB, as the catalog does.
- **A bot's libraries resolve to the versions Maven picks**: test dependencies now take part in choosing versions
  before they are left out, and the pom's `<exclusions>` and own `<dependencyManagement>` apply, as in Maven.
  Firestone ran with JUnit 5.7.2's platform jars that a library pulled, where Maven picks 1.9.3, so Studio's
  `.bmk` differed from the cli's; both now give the same file. A BOM the pom imports is still not read.
- **Open and export a `.bmk`** (2026-10-25): drop a `.bmk` on the bot list, or start `botmaker-studio
  --open=<file>`, and the bot opens in its Run window. A file the catalog lists must be the very file it pins;
  the first open of a catalog bot asks once ("… runs code on this computer"), and a file nobody checked asks
  every time, with a stronger warning. The bot is unpacked into the user data folder
  (`bots/<owner>/<repo>/<tag>/`), its Maven Central jars fetched once and checked against the file's sha256s,
  and it shows in My bots; *⋯ ▸ Remove from My bots…* deletes it. It runs with exactly the jars the file lists.
  *Edit in Studio* on it clones the author's repository at that tag into your projects, linked as *Get* links
  it (an unreachable repository gives an unlinked copy instead), and My bots then shows the clone.
  `--run=<owner>/<repo>` opens a bot's Run window straight away. *Project ▸ Export .bmk…* writes the tagged,
  saved version of a project as a `.bmk`.
- **A bot's icon** (2026-10-25): *Project ▸ Project Settings…* has a *Bot* section with the bot's name and
  description (the pom's `<name>` and `<description>`, nothing else in the pom rewritten) and its icon: *Choose
  file…* or *Cut from game…* (a window you pick), then a square crop. It is saved as `icon.png`, 256×256, at the
  project's root. The icon shows on My bots, Get more bots and New Project's cards, and in the Run window's
  header; a bot without one shows its first letter on a colour from its name. A catalog bot's icon is read from
  GitHub at the release the catalog pinned. A name set there is the one My bots and the Run window show.
- **The Run window's new header** (`ui/app/runner/RunnerHeader`, 2026-10-25): the bot's name, "by owner ·
  version" (or "mine"), and one status line saying whether it can start, from the plugins (the SDK: "Ready —
  game window found", "Not ready — signed out of Steam"…), asked again every 5 seconds. *▶ Start* and *⏹ Stop
  (Pause)* moved up beside it; the Stop button names your stop key. When the catalog lists a newer release, a
  strip under it says "1.1.0 is available — Update · What changed · Later". *Update* saves a version, fetches
  the release and merges it. When a file conflicts or the result does not compile, nothing changes and the
  window says "This update needs Studio to finish." with *Edit in Studio*.
- **The runner's values are kept apart from the bot** (`project/params/RunnerValues`): each value *Start* writes
  is also kept in the user data folder, per author and repository (`runner-values/<owner>/<repo>.json`). When
  an update brings the author's defaults back, the window writes the kept values again. A kept value whose
  setting is gone, or no longer fits its type, is said once ("Firestone 1.1.0 no longer has: Settings.speed.")
  and forgotten.
- **My bots says when a bot doesn't compile**: each card is compiled once in the background, again only after a
  source changes. A bot with errors says "Doesn't compile: open it in Studio to fix it." and its *▶ Run* is off.

- **BotMaker opens on your bot list** (`ui/app/home/RunHome`), in place of the project selection screen and of
  reopening the last project. *My bots* shows every project as a card: who it is by (or "mine"), its version,
  when it was last used, and a "<tag> available" badge when the catalog lists a newer release. Each card has
  *▶ Run*, which opens the bot's runner window, and *Edit in Studio*, which opens the editor. A remembered folder
  that is gone says so and offers *Remove from My bots*. *＋ New bot* opens New Project, and the runner window
  has *← My bots*. The list is most recently used first, and the sort menu and "My projects only" filter are
  gone. `--project` still opens a project straight away.

- **Get more bots is on the home** (`ui/app/home/GetBots`), under My bots, in place of the Browse Bots window:
  the catalog's bots as cards with search, *Show community bots and plugins*, *Hide* / *Show hidden*, the ★ count
  and *Get*, the same linked clone Install made (a community bot is confirmed first). A bot got appears in My
  bots, whose update badge replaces the Installed tab. *Project ▸ Get More Bots…* goes home, scrolled to it.
  Starring a bot from Studio and the sort menu are gone: the ★ count is the catalog's, and the order is official,
  trusted, then community, the most starred first.

- **The catalog can be asked again within a session** (`CatalogClient.refresh`), with the date of the copy kept
  for offline (`keptAt`), and each catalog plugin says which other catalog plugins it brings
  (`PluginRegistry.Plugin.brings`, from its newest release). New Project uses both in its card rework. A refresh's
  answer says whether GitHub gave it (`CatalogClient.Reply.fresh`).

### Changed

- **New Project: the name alone, and *Start from* as cards.** The name field has no rule labels and no example; a
  name that breaks a rule shows the reason in one red line under it. *Start from* is a grid of cards — Blank
  ("An empty project"), then each bot with its owner, "based on …" for a fork, its rank, newest tag and
  description — one picked, by click, Space or Enter, the arrow keys moving between cards. Picking a bot fills in
  its name when none was typed (a repository's `firestone-bot` becomes `FirestoneBot`); a typed name is never
  replaced. The note under the cards is the picked bot's trust warning only.
- **New Project offline keeps the kept bots on show**, greyed and not creatable, with "Offline — showing the bots
  from <date>" above them; until 2026-10-24 Blank was left alone.
- **New Project's plugins are cards too**, the ✓ toggling each (a checklist until 2026-10-24). A plugin the project
  gets anyway is shown ticked, greyed and not tickable, with who gives it: the picked bot's own "used by <bot>",
  and one another plugin brings "brought by <plugin>", followed down the chain (`PluginLock`). A plugin the user
  ticked turns locked once another ticked one brings it, and leaves the picks, so the pom never declares it beside
  the plugin that brings it. The arrow keys pass over a locked card.

### Fixed

- **New Project asks the catalog again each time it opens**, so a bot published since Studio started (Firestone)
  is offered; "Checking for new bots…" shows meanwhile, over the cards of the catalog kept from the last read.

- **A bot is compiled for the Java its pom names**, whatever Java Studio itself runs on: every compile passes the
  pom's `maven.compiler.release` to javac. A bot built by a Studio on a newer Java no longer stops another Studio
  with "Unsupported class file version: 70".

## [1.7.0] — 2026-10-24

### Changed

- **Publish takes the same steps as `botmaker bot publish`.** It checks the saved version against the catalog's
  rules before tagging anything (a new *Check* row), and the catalog entry keeps every version you published, each
  pinned to its commit, instead of only the newest. A retry carries on from where the publish stopped.
- **Every release the catalog approved is offered, and only those.** Plugins & Libraries lists a catalog plugin's
  approved versions, newest first, and any of them loads without a question. A plugin whose jar or pom is not the
  file the catalog approved is not loaded ("changed after the catalog approved it"), nor is a version the catalog
  removed, which names the version to upgrade to. A bot installs, and *Get vX.Y* updates, only at the commit the
  catalog approved, and *Get vX.Y* offers the newest approved release. Reads `catalog.json` schema 4 only.

## [1.6.0] — 2026-10-23

### Changed

- **New Project starts from Blank or any published bot.** Blank is the first row and the default. A bot row makes a
  copy linked to its author's repository, as Browse Bots' *Install* does: it keeps their package and classes, and
  their later releases arrive in the Versions tab. The "template" kind is gone, from New Project and from the
  Publish sheet.
- **No vetted tier and no launch targets.** Every bot installs and updates to its listed release. The Vetted
  badge, the tier filter and the Publish sheet's *Tested on* boxes are gone.
- **Listings are ranked by who published them.** Every bot and plugin row has a badge: *Official* (BotMaker),
  *Trusted* (a vetted developer) or *Community* (anyone, listed automatically). Lists show official first, then
  trusted, then community, the most starred first. A bot that uses a community plugin counts as community.
  Community rows are hidden until you tick *Show community bots and plugins* (Browse Bots, New Project, *Plugins &
  Libraries ▸ Browse*; remembered on this computer), and the assistant's `list_bots` follows the same choice.
  Starting from a community bot shows the warning that nobody reviewed its code and asks first; a trusted one names
  its publisher; an official one says nothing.
- **Studio asks before it first runs a community plugin.** Before a project loads a community plugin, or one the
  catalog does not list, on this computer, Studio asks you. A yes is remembered for that plugin; a no leaves it
  unloaded, says so in *Plugins & Libraries*, and asks again next time. Dev builds you installed on this computer are
  never asked about, nor are official and trusted plugins, or your own listed plugin, at the release the catalog
  lists, provided the jar is the one JitPack built for that release (Studio checks its checksum once per release).
  Any other version, or another jar at that version, is asked once per version. Offline with no catalog ever read,
  BotMaker's own plugins load as before.
- **A library cannot stand in for a plugin.** A jar that declares no plugin but has classes in a loaded plugin's
  package is not loaded, and *Plugins & Libraries* names the package and the plugin.
- **A plugin must be the one its listing names.** A plugin loaded from a catalog listing whose id is not the
  listing's is not loaded, and *Plugins & Libraries* says which id it has and which the catalog lists.
- **The assistant's `list_templates` is now `list_bots`.** `create_project` takes `bot`, may start from any listed
  bot, and asks you on screen first, naming the bot and its author.
- **One catalog lists every bot and plugin.** Browse Bots, New Project and *Plugins & Libraries* read one file,
  `catalog.json` from `BotMakerDev/botmaker-catalog`, instead of a GitHub search, a pom read per bot and the plugin
  registry. Each listing names one release that a machine check passed: installing a bot or a plugin, and a bot's
  update, go to that release, never to a newer one. New Project shows a bot's plugins from the catalog without
  reading its pom. The last catalog read is kept on this computer and used when GitHub cannot be reached.
- **Publishing lists a bot through the catalog.** After the release, Publish opens a pull request in
  `BotMakerDev/botmaker-catalog` from your account, adding or updating your bot's entry; the catalog's check builds
  the bot and merges it when it passes, and nobody reviews it. The Publish sheet shows where it stands — open,
  waiting for the new-entries limit, listed, or refused with the check's reason — and *Refresh* reads it again.
  Before opening it, Studio refuses what the check would: a release that is a draft or a prerelease or was made by
  someone else, and a bot whose pom uses no catalog plugin. *Unpublish* opens the pull request removing the entry,
  and *Stop following…* on a published fork the one marking it `detached` (adding `original` back clears it). The
  topic `botmaker-bot` is no longer written; your tags are still added to the repository's topics, which the
  catalog shows.
- **Hide a bot.** Browse Bots' *Hide* keeps a bot out of Browse Bots, New Project and the assistant's list on this
  computer; *Show hidden* brings it back.
- **A copy's link to its author is its git remote alone.** Nothing new writes `botmaker-source.json`; older projects
  that have one keep working. A bot whose author deleted its repository keeps running but can no longer be
  installed.
- **An update compiles before it is saved.** When *Get vX.Y* merges the author's release, Studio compiles the result
  first. If it has errors, a sheet lists them: *Fix them first* keeps the update open in the editor, and *Finish the
  update…* checks again. *Finish anyway* saves it with the errors, and *Undo the update* puts the project back.
  The check runs no annotation processor, so a dependency the update adds runs no code of its own before you accept.
- **The assistant names a bot by owner/repo when two share a name.** `create_project` refuses a name that two listed
  bots have, and `list_bots` quotes each description as its author's words.

### Added

- **Publish your copy of someone else's bot as a bot of its own.** Once *Save to my copy* has made your fork,
  *Publish vX.Y…* releases it there and lists it. It stays linked: the author's new releases still reach you, and
  Browse Bots and New Project show it as "based on" the author's bot. Your fork also opens for editing on every
  computer.
- **Stop following.** The Versions tab's *Stop following…* unlinks a copy from the bot it came from: no more update
  notices and no *Suggest to author…*, and the bot becomes your own. A published fork stops showing "based on …".
  Adding a remote named `original` in Dev follows it again.

- **Rename works on a plugin's set members.** Renaming a member of a plugin's open set, such as a screen's constant
  `Screens.MARKET`, a picture or an activity, is no longer refused for being in the plugin's file. Studio renames it
  as the plugin itself would, so a screen's class is renamed with its constant. This works from the canvas at any
  use, and from the assistant's `rename`. Every other edit of a plugin's file stays the plugin's.
- **A plugin's long value is laid out one call per line.** A managed value whose chain passes 120 columns, such as
  a screen's `setup()`, is written one call per line. A short one stays on its line, and only what the write
  changed is laid out.
- **A plugin can append a call to a bot's method** (`PluginValues.appendCall`, contract 0.11.0). Studio writes
  `Owner.call(arguments…);` as the method's last statement, before a closing `return`, and imports the class. Each
  argument is written as its parameter's type, or as the named constant or field of a plugin's set. The bot must
  still compile, and the write is one History step. The SDK's 🗺 *Record into* is its first user.

- **A plugin's `@Fills` methods declare types** (contract 0.11.0): each return type is declared beside the
  plugin's own, starts as a call to the method and is drawn as a pill saying its label, with the Java in the
  tooltip (`plugin/FilledType`). A refused method is a warning on bind.
- **`ValueContext.declared(Class)`** is answered from the grammar, on a slot and on a value alike.

### Fixed

- **A plugin's holder is found by plugin and name, never by its class name alone** (`project/managed/HolderFiles`).
  A bot class, or another plugin's holder, that shares a holder's name no longer stops that holder being
  written on bind, and an entry point's `Sdk.class`-style arguments no longer read or import the wrong class;
  a second holder of one simple name in the same call is written in full. A holder the user moved still counts
  while it carries its plugin's mark.
- **A plugin's write no longer waits seconds on Project History.** The safety version before it staged with
  JGit's `add .`, which hashes every tracked file each call — 2.3 s a call, twice per write, on a bot with
  70 MB of screens. It now stages only what `status` names: 40 ms (`ProjectVcs.checkpoint`).
- **The toolbar relabels after a plugin writes the bot's Java** (`FilesRewrittenEvent`): picking a game showed
  the previous one on 🎮 until the next settings change.

## [1.5.0] — 2026-10-18

### Added

- **A plugin's set of nested classes** (contract `ofClasses`, 2026-10-16): Studio reads a holder of `static final
  class X extends <element>` classes, each with its singleton constant, its `public final` fields and its one-return
  setup method (`project/managed/ManagedClasses`). A field reference — `Screens.MARKET.beerBuy`, or `MARKET.close`
  inside the holder — reads as the plugin's value and is written back as `Screens.MARKET.beerBuy`. Fields are added,
  renamed, repointed and removed by binding like any set's constants; a class is added with its constant and an
  `@Override` setup, renamed with its constant (the class follows when it was named from it, `FAVORED_SHOP` →
  `FavoredShop`), and removed only while nothing outside it uses the constant, the class or a field. A bare `close`
  inside the class is not read as a reference yet: write `MARKET.close`.

- **Project ▸ Migrate…** carries the bot over a plugin change by its author's `@Migrate` rules. Each row is an old
  element the bot still uses, with its uses (double-click one to go to it) and its rule's state. **Write rule**
  writes the element's signature and a default body into `Migrations`, marked to be written, and opens it in the
  editor. **Preview** lists each use before and after. **Apply** rewrites every use a written rule covers, as one
  version after a safety one. **Remove finished rules** removes each rule whose element the bot no longer uses,
  and the file when that empties it. A retired type's values still go through **Copy values…** (the migration
  window). Migrate compares the bot with the classpath the project loaded before its last change: an upgrade, a
  removal, or a dev build rebuilt under the same version. It also opens from a banner above the canvas when that
  change broke something, from a Review tab row, and from the upgrade's question, whose **Migrate first** moves the
  version and leaves the code for Migrate.
- **`migration write`** (MCP): the assistant sets a seeded rule's body — one value, or one statement for a `void`
  rule, with the imports it names — checked to compile, and the seed's `@Refactor` mark goes. `edit_code`'s values
  could not say `self.markers(List.of(marker))`, so a rule could be seeded and never written over MCP.
- **The assistant's `migration` tool is Migrate** (MCP). `list` gives Migrate's rows, each element with its first
  uses and its rule, the rules naming nothing, the finished rules and the retired types' values. `seed` writes an
  element's rule, which the assistant then writes with `edit_code`. `apply` previews each use before and after,
  and with `confirm` writes them. `remove_finished` removes the finished rules. `copy_values` copies a retired
  type's values by the bot's own `@Migrate` method for the pair, and `switch_uses` and `remove_migrated` stay as
  they were. `read` and `set`, with their mapping lines, are gone: the rule is the mapping. `plugins set_version`
  takes `rulesFirst`, the upgrade's Migrate first: only the pom moves, and the answer counts what broke.
- **One migration engine behind `@Migrate` rules** (`migrate/`). A bot's rule names the
  old method, constructor, field or whole type by its own signature, and Studio finds that element in the jars the
  bot used to compile against. It then rewrites every use in the project through the rule's body, keeping each
  use's own spelling. A chain such as `named("home").marker("a").marker("b")` comes out whole. Rules with neither
  or both of `value` and `name`, rules that match nothing, and two rules for one element are refused with a reason.
  With no rule, nothing is rewritten. A rewrite that would not compile is left as written and its function is
  marked `@Refactor`. `migrate.Broken` lists what the new jars break and whether a rule covers each element.
- **The old API survives a plugin change.** Each load records the classpath it loaded, and one that differs keeps
  the last as the classpath before (`~/.cache/botmaker/api`). A `-SNAPSHOT` jar is recorded as a copy of the bytes
  loaded, so a dev reload can still read what the bot used to call.
- **One finder of uses** (`nav/Uses`): calls, `new`, `this(…)`/`super(…)`, method references, field reads and
  writes, `case` labels, type names and imports, matched by binding. Signature changes (`MethodReferences`) and
  migrations both use it.

- **The assistant's costly answers show up.** `tool-times.tsv` in Studio's cache now also records how much text and
  how many images each tool call answered, so `sort -k5 -n` finds the replies that cost the most. A file written
  before these columns is moved aside to `tool-times.old.tsv`. At connect, the MCP server names the bot's plugins
  that have a guide and points to `plugin_guide`. It no longer pastes the guides into its instructions, which
  clients cut short.
- **A plugin's assistant tool may take nested values and one of several actions.** Studio serves a nested record
  as an object, a list of records as an array of them, and an action tool as one flat object: `action`, whose
  description says what each action needs and does, and the actions' fields, each naming the actions that take
  it when not all do. No `oneOf`, which Gemini reads poorly.
- **Studio's own MCP tools are declared as a plugin's are**: each an `AssistantTool` over a parameter record, in
  `assist/FileTools`, `StudioTools`, `RunTools` and `ProjectTools`; `McpTools` only serves them. The hand-written
  schemas and the `text()`/`number()`/`flag()` readers, which turned a missing value into `""` without a word, are
  gone: a missing or ill-typed argument is refused, `REFUSED: <tool>: 'value' is required`. `add_file`'s `kind`
  and `set_setting`'s `key` are enums, sent as a JSON `enum`; `add_method`/`edit_signature`'s `params` are typed.
  One wire change: each of `apply_edits`' edits names its kind as `action` (was `op`).
  `edit_variable`'s `name` and `type` may be left out, as its description always said. An argument a tool does
  not take is refused, where a tool with none ignored it; an answer loses its trailing blank lines.
- **Studio serves 37 MCP tools where it served 58, and a third fewer characters** (23,847, was 31,650 —
  `McpBudgetTest`'s ceiling is now 26,000). Families of verbs became one tool of several actions, `action`
  picking one:
  - `edit_code` (insert, set, delete, move, add_argument, edit_variable) replaces `insert_block`, `set_slot`,
    `delete_block`, `move_block`, `add_argument`, `edit_variable` and `apply_edits`; one edit is a list of one;
  - `review` (list, mark, unmark, undo) replaces `list_review`, `mark_reviewed`, `remove_mark`, `undo_change`;
  - `versions` (list, checkpoint, revert), `plugins` (list, search, add, set_version, remove), `migration` (list,
    read, set, switch_uses, remove_migrated) and `params` (list, set_default, reset) replace their 16 tools.

  A field another action takes is refused, naming the action's own. While a plugin moves, `plugins list`,
  `plugins search` and `versions list` still answer. The descriptions are shorter; how a slot's value is spelt is
  said once, in `edit_code`'s. Studio's answers name the new tools (`versions revert`, `plugins list`).

- **A plugin's value writes are undone with ↶.** Each `PluginValues` write is one step of the editor's undo, named,
  and a plugin's `History.batch` makes several one step under its own label; ↶/↷ put every file it wrote back. Before,
  only a Project History safety version could take one back. A batch takes one safety version, not one per write.
  The history moved out of `CodeEditorService` into `services/EditorHistory`; `plugin/HostHistory` is the contract's
  `History` over it.
- **The assistant's `undo` and `redo`** take the editor's last step back or do it again, as ↶/↷ do, and say which
  step and what is next each way. `undo_change` stays: it puts a review item's function back from the versions.
- **A plugin lists the bot's boolean methods and adds one** (`PluginValues.booleanMethods`,
  `createBooleanMethod`): `public static boolean name() { return false; }` in the class it names, or a new one.
- **A tool's answer in several text parts reaches the client as one text**, the parts a blank line apart.
- **quit_studio** closes Studio as its window's close button does, half a second after it answers, so a client
  can restart it with the bridge's start_studio.
- **`/mcp-hello`** proves to a client that it talks to this Studio before the client sends the token: it signs
  the client's nonce with the token (HMAC-SHA256) and says nothing of the token itself. Every other path still
  asks for the token.

### Fixed

- **A fast scroll lagged and showed members being drawn** (2026-10-18). Three causes, each fixed in
  `EditorCanvas`:
  - A scroll's draw ran after the frame that painted the new position, so nearly every frame of a fast scroll
    showed blanks. It now runs before the pulse lays the scene out.
  - Every forced layout called `applyCss` on the scroll pane. JavaFX restyles every node under a node
    `applyCss` is called on, clean or not. So each draw restyled every member drawn so far, and every render
    restyled the hidden kept roots too. Now only the new member (`MemberPlaceholder.draw`) or the new root is
    styled. A switch back went from 11 to 2 ms on the benchmark.
  - Once a render settles (300 ms), the members still blank are drawn about 6 ms per pulse, nearest the
    viewport first, the member at the top kept in place. A fast scroll after that costs about 8 ms a frame
    instead of 70.
- **The file tabs were sometimes hidden** (2026-10-18). Since the canvas moved below the tab strip, a canvas
  or viewer asking for more height than the centre column had squeezed the strip to nothing, because a tab
  pane's minimum height is 0. The strip now keeps its height (`CenterTabs`), and the area below gives way,
  clipped.
- **Each render walked the whole bot again for its parameters and its sets' members** (2026-10-18). The
  bot's `@Param` fields (`JavaParameters.over`), a set's members (`ManagedSets.members`) and a set's nested
  classes (`ManagedClasses.all`) are now worked out once per parse of the bot and per plugin bind
  (`BotIndex.memo`). An edit makes a new parse, so it is never answered stale. The packages the bound plugins
  catalogue (`PluginHost.cataloguedPackages`) are computed once per bind; the type index asked for them on
  every lookup. Together these were about a quarter of a render's time in a profile of ordinary editing.
- **Switching back to a file parsed, drew and styled it again** (2026-10-18). A file left unchanged, with no
  other file written meanwhile, now shows the canvas it had, scrolled where it was. Its root stays in the
  scene, hidden, for each of the 8 files the editor keeps for a switch back, so nothing is styled again either.
  A breakpoint set while it was away still shows. On `RenderBenchmarkTest`'s file, switching back takes about
  11 ms, not 95 ms. A rebind, or a change to the file or to any other file, renders it as before.
- **Opening a big file drew every member, on screen or not** (2026-10-18). The canvas now draws only the
  fields, methods and nested types near what it shows. Each other one is a blank of about its height, drawn
  when a scroll, a resize or a zoom brings it near, or when a find, an error, a Structure row or the debugger
  goes to it. The member at the top of the view stays put while blanks are drawn. On `RenderBenchmarkTest`'s
  file of 300 constants, a render draws 929 nodes, not 16806, and takes about 350 ms, not 1.3–1.5 s;
  switching back to it takes about 95 ms, not 0.64 s.
- **Opening a file styled every block twice** (2026-10-18). The canvas was moved into the selected tab after
  each render, and a node that leaves the scene is restyled whole when it comes back. That happened on a
  project's first file and on every file opened from Project Files, a usage or the assistant. The tab strip
  now only switches files, and the canvas stays in one place below it, hidden while a viewer is in front.
  On `RenderBenchmarkTest`'s file of 300 constants, the extra pass drops from 544 ms to 60 ms on first open
  and from 181 ms to 48 ms from Project Files.
- **Switching back to a tab rebuilt every block of its file** (profiled 2026-10-18). The last eight files
  switched away from now keep their blocks, and a switch back reuses each block whose text is unchanged, as
  an edit does. Nothing is kept after a plugin rebind, a change of editor for a type, or a write to any
  other file. `PluginHost.shipsInPlugin` also answers each name once per bind, so a palette's curation stops
  failing a `Class.forName` for each name every time a menu is built.
- **Opening a project read every library's cached index to offer types from one or two** (profiled
  2026-10-18): all 74 jars' ClassGraph JSON was deserialized, a thread pool each, to keep 94 types. Each cache
  now has a `.packages` list beside it, and a jar is read only once one of its packages is under what the bound
  plugins offer, or when asked for by name. Cache files of an older format (`.json`, `.ser`) are deleted.
- **Drawing a file walked it once per question about a variable's scope** (profiled 2026-10-18): a call block asked
  about seven times while it was drawn, and each question walked the whole file. This was 55% of the FX thread's
  time on a big constants file. `VariableScopeVisitor` now indexes a parse once, keeps the index on the
  `CompilationUnit` while its `modificationCount` holds, and shares one scope snapshot until a scope changes.
  `ProjectAnalyzer.getAvailableScopes` asks once instead of three times.
- **`edit_code` refused a constant whose value names another constant.** `Screens.MARKET` (built `Way.of(Screens.TAVERN,
  …)`) and `Pictures.PLAY_OFF_TAVERN` (`.activities(List.of(Activities.Tavern.class))`) were "not a GameScreen", "not
  an ImageTemplate": the constant's initialiser was read without the bot's own names. It is read with them now.
- **A dev reload that broke the bot showed nothing broken.** The bot's parsed index was cached by its jars' paths,
  and a `-SNAPSHOT` rebuilt in place keeps its path, so Migrate and `migration list` read the bot as it compiled
  against the old jar. Each jar's size and time are part of the key now.
- **Migrate's Open rule and a use's double-click showed nothing.** The window was owned by Studio's, so it stayed
  in front of the editor it had just moved. It is a window of its own now, closed with Studio's, and brings the
  editor forward.
- **One assistant tool call is one Project History version.** A plugin tool's writes, on whatever thread they are
  made, take one safety version before the first of them: a `crop_pictures` of six boxes took one for every picture,
  tag and screen it wrote. Each write is still its own undo step.
- **Writes to a bot are cheaper**: Project History hashes with the JDK's SHA-1, and the Java parser's options are
  read once, not for every value read.
- **A plugin's record value is added only when it reads back whole.** A link the writer could not write was left
  out of the call, so a screen lost its way in. Now the add is refused and says why. A part that another constant
  holds is written as that constant (`Way.of(HOME, …)`).
- **A picture filed under an activity matched no constant.** A value written with the bot's names
  (`Activities.Tavern.class`) is now matched by its value first, so it is written as its `Pictures` constant.
- **Text blocks read as javac reads them**: indentation stripped and escapes read, and a text block written back
  holds exactly its value.

- **Every assistant tool call's time is kept.** Each call over MCP, Studio's tools and the plugins', adds a row
  to `tool-times.tsv` in Studio's cache (`~/.cache/botmaker/`): when it ended, the tool, its milliseconds, and
  `ok` or `error`. Past 4 MB the file moves aside to `tool-times.old.tsv`.
- **`list_palette` hides no name.** Called empty it gives an index, one line per class of calls or category of
  statements with each entry's bare name, about sixteen lines where it gave over a hundred JSON objects. A filter
  gives one `id → returns` line per entry; its words are OR'd (`wait gone`) and each looks in the id, label,
  category, parameter and return types, so a class, a type or a word in a name all find it.
- **A refused value shows how its type is written.** `set_slot` refusing `Duration.ofSeconds(3)` now adds "It is
  written like `Duration.ofMillis(1000L)`", so the assistant fixes it in one more call.

- **The assistant keeps what it learns about the game.** `write_notes` saves it in the bot's `GAME.md`, one
  section at a time (how to reach a screen, a hotkey, what Escape does, how slow a screen is), and `read_notes`
  reads it, which the instructions say to do before exploring, so the next session starts from it. The file is
  plain Markdown in the bot, versioned and published with it, edited through its CommonMark tree: a `##` in a code
  block is never taken for a section. Its text never enters the MCP instructions, as a gallery bot's maker wrote it.
- **Markdown files open as a page.** `GAME.md` is listed first in the explorer, and any `.md` file opens drawn in the
  theme's colours, with Edit to change its text and Save. The page runs no script, loads nothing (a picture shows
  its words), and a link keeps it where it is. Clicking the row again keeps an edit in progress, and Save refuses
  when the file changed meanwhile, so it never undoes what the assistant wrote.
- **The assistant is told how each plugin lays a bot out.** A plugin's guide (`StudioPlugin.assistantGuide()`)
  is added to the MCP instructions under the plugin's name, and the new `plugin_guide` tool reads it again after
  a plugin changes.
- **The assistant can write a test into a condition.** `set_slot` takes a palette call whose result fits the
  slot (`call:ImageFinder.find(ImageTemplate)`) or an operator (`op:NOT`, `op:AND`, `op:LESS`…), and `read_tree`
  lists the slots inside it, so an If or While can check the screen. `op:LAMBDA` writes the action a call such
  as `ImageFinder.whileFindAny` runs, its body a body to insert into; `var:<name>` passes a variable in scope (a
  lambda's value), and `op:NEGATE`/`op:CHOOSE` write `-x` and `?:`. New tools `edit_variable` (rename, retype a
  declared variable) and `add_argument` (one more argument to a call taking any number); `read_tree` lists the
  slots inside a call's arguments, casts and `?:` too.

### Removed

- **Dependencies nothing used** (2026-10-18): `opencv` (the GrabCut wand left for the SDK; shared still brings
  it), `javafx-fxml`, `dbus-java-core` and its unixsocket transport and `gst1-java-core` (the Wayland preview,
  dropped 2026-07-09), the `org.eclipse.core.runtime`/`equinox.common`/`core.resources` pins (JDT brings its own),
  and `slf4j-api` (it comes with its binding).
- **The `@ReplacedBy` repair**: `ApiMigrationRunner`, `Redirects`, `Pairing`, `UpgradeDiff`, `ApiReferences`, the
  fix sheet, the two-plugin simple-name guard (uses are matched by binding now), and the upgrade-only call edits
  (a default standing in, a deleted call, a retargeted receiver). `ApiModel` reads only what is new and `@Since`.

### Changed

- **Major dependency upgrades** (2026-10-18): ClassGraph 6.0.0, JDT core 3.47.0 (Java 25 sources), JGit 7.8,
  lsp4j 1.0.0, maven-model and resolver-provider 3.10.0 with resolver 2.0.24 (`maven-resolver-supplier-mvn3`,
  which brings the basic connector and the file and Apache HTTP transports), JUnit 6.1.3. ClassGraph 6 has no JSON
  serialization, so the type index keeps its own records (`index/LibraryType`): what Studio reads off a class, its
  methods, constructors and fields, cached as JSON (`<jar>.v3.json`). An old `v2` cache is swept and its jar
  scanned once again. A cache loads without a ClassGraph thread pool, and no scan stays open for a session.
- **Minor dependency upgrades** (2026-10-18): pty4j 0.14.0 (kotlin pins 2.4.20), MCP SDK 2.1.0, Jetty 12.1.14,
  jackson-databind 2.22.4, slf4j-simple 2.0.20; plugins shade 3.6.2, surefire 3.6.0, clean 3.5.0, resources 3.5.0, antrun 3.2.0, jpackage
  1.8.0. commonmark stays at the SDK's 0.21.0, a pin shared with an upstream.
- **JavaFX 27** (2026-10-18), from 25.0.4, with the rest of the platform. JavaFX 27 needs Java 25.
- **A plugin upgrade or removal carries the bot over with its `@Migrate` rules** (2026-10-16). What a move
  breaks is javac's answer over the bot against both classpaths, listed per element with its uses and whether a
  rule covers it. The bot's rules are applied; for each broken element no rule names, *Upgrade anyway* (and a
  removal) writes a default rule into `Migrations`, marked `@Refactor("write the replacement")`, and applies it:
  the type's default value, the receiver for a method that gives its own owner back, or the call deleted. A whole
  type, or a member taking a type that is gone, gets no default: its uses stay as written, listed, and their
  functions marked. An import of a gone class nothing uses any more goes. The code is repaired before a plugin's
  values, so a value a rule rewrote is not reset.
- **The upgrade no longer asks a fix per place.** The fix sheet went with the plugins' `@ReplacedBy` pointers;
  one question names the broken elements no rule covers before anything is written.
- **A type migration's method is a whole type's rule**: written `@Migrate(value = Old.class, kind = Migrate.Kind.TYPE)`.
  Only such rules copy values. A bare `@Migrate(Old.class)` is now a method rule.
- **An upgrade lists an open set's constant, or a parameter, that no longer compiles** instead of passing over it in
  silence. It is kept as written, with a hint to write a `@Migrate` rule for what it uses.
- **A refactor's compile check compares errors by what javac says, not where.** An edit that shortens a file no
  longer makes an error further down look new.
- **Migrating a retired type no longer preselects a plugin's replacement.** Contract 0.10.0 took the plugin's
  `replacedBy` pointer away: the bot's author picks the target type, in the migration window or with
  `migration copy_values`.
- **The bot's notes on its game moved to the SDK.** `read_notes` and `write_notes` are the SDK's tools now,
  and Studio names no `GAME.md`. The explorer lists every Markdown file at the bot's root first (its README
  too). It lists the files again after a plugin's tool adds, removes or replaces a file there.
- **Run activity flow inserts `Bot.runFlow(Sdk.class)`** with an SDK whose entry point takes only the values
  classes: the run reads its own parameters off `Sdk.java`.
- **A try runs in a warm JVM.** ▶ Try and the assistant's `try_statement` run in one JVM the project keeps
  between tries, with the bot's classes loaded afresh each time: a try no longer pays a JVM's start, the SDK's and
  the private display's join, and its start-up lines come once. The JVM starts again when the dependencies, the run
  properties, the trace or the display change. Stop interrupts the try; one that ignores it ends the JVM.
- **The assistant is told to look in text.** The MCP server's instructions name the SDK's `describe`, `click`, `key`
  and `scroll`: find a step by acting and reading what changed, and take a screenshot only to cut a picture or
  when text cannot answer, a part at a low scale.
- **A game frame reaches the assistant as a JPEG.** A plugin tool's large opaque image goes out over MCP as a
  JPEG when that is smaller than its PNG; an image under 32 KB (most pictures and crops) stays an exact PNG.

### Fixed

- **A method run on its own names itself to the plugin.** ▶ Run on a target and `run_activity` set
  `botmaker.trial.target` to it, so the SDK goes home first when the activity's card does.
- **`set_plugin_version` to the local build the bot is on reloads it.** A rebuild into `~/.m2` at the same version
  runs with `confirm`, as *Reload plugins* does, and its tools are served anew: no Studio restart, no reconnect.
- **An edit's answer is an outline.** The methods an edit reshaped come back as each body's and statement's id with
  the statement's first words, not their whole tree with every slot, which ran to thousands of characters per edit.
- **A seeded argument is written by its short name.** A plugin's seed and the JDK ones are written in full so they
  compile anywhere; a call inserted from the menu or by the assistant now reads `Key.A` with `Key` imported, not
  `com.botmaker.sdk.api.input.Key.A`, unless that simple name already names another class in the file.
- **The assistant's entry point is handed the bot's values.** `insert_block` of `call:Bot.runFlow(Class)` in
  `main` writes `Bot.runFlow(Sdk.class)`, as the statement menu does, never `Bot.runFlow((Class) null)`, which no
  slot could fix: a class literal is no plugin's value. Each step of a turn now sees the bot's other files, so the
  holder of values is found.
- **A loop dropped inside another loop compiles.** Its lambda's value takes a free name (`it2`, `it3`) when the
  name it would get is already in scope there; a loop beside another keeps `it`.

### Added

- **An assistant can move a plugin to another version.** `set_plugin_version` is the Installed tab's version
  menu: a release, or in dev mode this computer's own build. Without `confirm` it says what the move repairs;
  a confirmed move asks the user on screen and answers at once, then, once they agree, saves a version, repairs
  the calls (each choice taking the plugin's suggestion, marked for review) and serves the plugin's tools anew.
  `list_plugins` says where the move is; until it ends, the tools that write the bot refuse.
- **An assistant can see what the bot has to migrate.** `list_migrations` lists the values of each type a plugin
  retired, with its replacement and how many are copied, and the bot's `@Migrate` methods. A move's preview names
  each set the new version drops and whether its values can be migrated first. `read_migration` shows one pair as
  the migration window seeds it: each part of the new type and what it is made of (or each old constant and what it
  becomes), as the bot's `@Migrate` method has it or first guessed, what else each can be, the pairs it goes
  through and why it cannot be written yet. `set_migration` is the window's Apply: lines in the same words change
  that mapping (`grey: fixed true`, `Area.w: fixed 0`, `RED: NAVY`); without `confirm` it lists each value before
  and after, with it the `@Migrate` methods and `@Migrated` copies are written as one version, the new type's
  holder too when the bot lacks it. A mapping that would not compile writes nothing. `switch_uses` points the bot's
  uses at the copies (a use the copy does not fit left and marked), and `remove_migrated` removes the originals
  nothing uses and then the `@Migrate` methods nothing needs, previewed without `confirm`; each writes one version
  after a safety one.
- **An assistant can put leftover fields right.** `reset_param` sets a plugin's parameter back to its first value,
  or removes and writes it anew when a move left it as another type (`List<String>` for `List<Launcher>`);
  `delete_field` deletes a field nothing uses, listing its uses otherwise. Each saves a version first, and one
  that would stop the bot compiling changes nothing.
- **`list_plugins` gives a template's SDK version**, not the pom property (`${botmaker.sdk.version}`) that pins it.
- **An assistant can turn on dev mode.** `set_setting dev_mode on` (or `off`) is *Plugins & Libraries*' Dev mode:
  the project loads its plugins' local `-SNAPSHOT` builds, the plugins reload, and their tools are served anew.
  Turning it on asks the user on screen first, as adding a plugin does.
- **An assistant can start a bot.** The MCP endpoint serves `list_templates` and `create_project`. They make a
  project as New Project does, from a vetted template or blank, with registry plugins the user agrees to on
  screen. Then Studio opens the project in place of the current one. The dialog and the tool share one
  creation path (`sharing/NewProjects`).
- **New, Rename and Delete in Project Files.** A right-click in the user's packages offers *New ▸ Class /
  Interface / Record / Enum…*; a class of theirs, *Rename…* and *Delete…*. A delete is refused while another file
  uses what the file declares (each use listed, a link to it) or while the bot would stop compiling without it,
  and never touches the entry point, Parameters.java or a plugin's file; a rename or a delete takes a safety
  version first. A plugin's file and Parameters.java are no longer offered for renaming: they are found by
  their names. The header's **+** makes a class in the selected row's package without hunting for the right
  row to right-click. The assistant gets the same through `services/ClassFiles`: `add_file` takes `kind` and
  `package`, `rename` of a class moves its file instead of refusing, and `delete_file` is new. The tree
  follows a file the assistant adds.
- **Java files open in tabs.** Each Java file you open gets a tab above the canvas, beside the pictures and
  data files; clicking a tab switches to that file, and a file opened from anywhere else (Project Files, a
  usage, the assistant) brings its tab forward. A tab's menu offers Close, Close Others, Close All and Close
  Tabs to the Left / Right; with every tab closed the centre says how to open one. The open tabs come back
  when the project is reopened, and a renamed or deleted file's tab closes.
- **Parameters.java has its own package, `<bot>.params`.** Studio owns it there: the Parameters window writes
  it, and Project Files lists it apart and neither renames nor deletes it. New bots and the game-bot template
  have it there; a bot made before keeps its file beside its classes, an ordinary class the window still reads.
  A block naming a parameter imports its class.
- **Project Files tells your code from Studio's.** *My code* lists only the files you write; Parameters.java
  and the plugins' files sit in a closed *Managed by Studio* group, each row's tooltip saying which window
  changes it.

### Fixed

- **A flow card's body compiles in `Flows.java`.** A value a plugin hands over as the Java it is written as, a
  card's body `Research::body`, was written with no import, so a body in the bot's own package broke the bot
  (`Flows.java` sits in `plugins/sdk`). Every plugin value Studio writes now imports the bot's classes it names
  and writes a full name short with its import: `ActivityBody.none()`, not
  `com.botmaker.sdk.api.flow.ActivityBody.none()` (`plugin/grammar/ShortNames`). A full name is left as it is
  when the value already imports another class by that name; the target file's own imports are not checked.
- **An MCP client can give a method one of the bot's own types.** `add_method` and `edit_signature` refused
  `Activities.Research`, so an activity's body could not be written over MCP. They now take any type the bot
  declares, enums and member types too. A class the bot declares is written as its file names it, its outer
  type imported, never as `com.bot.….Activities.Research` (Parameters, migrations and signatures alike).
- **`set_slot` writes an activity's outcome.** A seeded `return null;` read as a slot of type `null`, and an
  enum no plugin declares had no value form, so a body could not return `Activities.Research.NEXT`. A return's
  slot now expects its method's type, and an enum slot takes one of its constants (`NEXT`, `Research.NEXT`).
- **An edit an MCP client makes is saved at once.** It stayed in the editor until the next run, so closing
  Studio lost edits the client had been told were applied. The answer now says "and saved".
- **A palette call's plugin-typed argument starts as the plugin's fresh value**: `ImageTemplateGroup.of(<the
  placeholder picture>)`, not `(ImageTemplateGroup) null` or a `new ImageTemplate("")` that throws at run. The
  seed looked the type up by its bare simple name, which no plugin declares.
- **`set_slot` reads the bot's constants inside a value**: `ImageTemplateGroup.of(Pictures.CLOSE_X,
  Pictures.CLAIM_OFFLINE)` is accepted and written with each constant kept.
- **`read_tree` lists a plugin call statement's arguments as slots**, and `delete_block`/`move_block` take the id
  it gives that statement.
- **Every palette call inserts code that compiles.** Calls taking a `Class` (`ActivitySwitch.active`), an
  `ImageTemplateGroup`, `Combo`, `KeySequence` or `OcrOptions` were seeded with a constructor the type does not
  have and were refused; such a value now starts `null`, cast to its type so an overloaded call
  (`ImageClicker.click(MatchResult)`) stays unambiguous.
- **A return slot typed by an activity's outcomes offers them.** `return Activities.Collect.NEXT` showed no
  values to pick: the slot took the return type by name, which never reads as an enum. A nested enum's constant
  is written `Activities.Collect.NEXT`, not `Collect.NEXT` (which named the activity's class).
- **An assistant reads each traced line once.** A bot's trace line is also printed, so `read_trace` and a run
  tool's answer showed it twice (`[Debug Source] …` and `[Source] …`). The trace copy stays, with its source
  line; the level is named only when it is not debug detail (`[Warning Vision] …`).
- **A window's title never matches Studio's own window.** The screen a plugin watches is found by title, and
  "Firestone" found "BotMaker Blocks - Firestone" while the game ran in a private display: the assistant was shown
  Studio. BotMaker's own windows now match only a title that names BotMaker, as shared's `WindowMatch` does.
- **Starting a run no longer takes the focus away (Linux, X11).** The run layer over the desktop took the focus when it was
  shown, and its first remap dropped it to no window at all, so the game (or the window you were typing in) lost
  it. While the layer is not yet click-through, focus goes back to the window that had it
  (`DesktopLayer.handFocusBack`).
- **A `for` loop reads as written.** `for (int attempt = 0; attempt < 5; attempt++)` was drawn "while Off each
  time 0": a value editor claimed the condition and the step by their type and showed a default. A slot holding
  a computation (`attempt < 5`, `attempt++`, `!found`, a local's name) is now drawn as its own block.
- **A plugin parameter whose type changed is repaired on upgrade.** A field such as `List<String> launchTargets`
  left behind after the SDK made it `List<Launcher>` still compiled, so the upgrade kept it and the plugin read
  nothing from it. It is now removed like any value declared another way, and written again with its new type.
- **Show in file manager no longer freezes the desktop on Linux.** It opened through AWT on the UI thread while
  the right-click menu held the mouse, so no window took a click until Studio was killed. Every link and
  folder Studio opens now goes to the system opener (`xdg-open`, `open`, `rundll32`) from a background thread.
- **`mvn javafx:run` starts on JDK 26 and later.** The plugin put only `javafx.*` jars on the module path, so
  `javafx.web` could not find OpenJFX's `jdk.jsobject` jar: "Module jdk.jsobject not found". It now runs on the
  classpath through `Launcher`, as the shaded jar and the app-image do.

## [1.4.6] — 2026-10-12

### Fixed

- **A plugin installs at its newest release.** New Project, Browse and Installed offered the SDK at v1.4.2,
  the registry's verified version, while v1.4.4 was out: the registry's update for it never merged. Studio now
  asks JitPack for each plugin's newest build, and Installed marks it *(newest)*.
- **A template whose pom has a self-referencing property no longer exhausts Studio's memory** in New Project:
  property substitution stops after 32 replacements.

## [1.4.5] — 2026-10-12

### Fixed

- **New Project copies a template's newest release.** A template's gallery entry is vetted at one release, and
  New Project installed that one: gamebot v0.2.0 with v0.2.6 out. A template now installs its newest release,
  vetted or not; an ordinary vetted bot still installs its vetted one.
- **New Project shows the plugin versions the copied template pins**, read off the pom of the release it copies.
  It showed the gallery entry's `requires`, written when the template was last published: SDK 1.2.3 for a
  gamebot pinning 1.4.4.

### Changed

- **Publishing writes no `requires`**, and the Publish sheet has no Requires row; a gallery card no longer shows
  one. The gallery stores the repository, and an installed release's pom says what it needs.

## [1.4.4] — 2026-10-12

### Fixed

- **A new project's SDK is 1.4.4.** SDK 1.4.3 never built on JitPack (it pinned a shared tag that never built), so
  1.4.3's fallback SDK version named a release no bot could resolve. Studio 1.4.4 is 1.4.3 otherwise.

## [1.4.3] — 2026-10-12

### Fixed

- **A type's block says what it extends or implements.** An enum block now shows `implements Outcome` (an
  activity), an interface reads *Interface: Step extends Runnable* instead of *Class: … implements …*, and a
  class shows its type parameters (`Box<T>`). One reading of the header, `blocks/TypeClauses`.
- **Every kind of type has its block, nested ones too.** An interface is an `InterfaceBlock`, a record a
  `RecordBlock` (`Record: Spot(int x, int y) implements Point`), each on the shared `TypeBlock` with the class's;
  a record file, and a class, interface or record nested in a class, drew nothing before. Only a class keeps the
  *+ Add Function* / *+ Add Enum* toolbar; the members of the other two are edited one by one. On the class's card
  an interface has a dashed edge in the Functions colour, a record a solid one in the Variables colour; an
  `extends`/`implements` clause takes the theme's text instead of a fixed grey that read 2.08:1 on Dark.
- **A plugin's set constant and its own parameter are read and written with the bot's names.** An open-set constant
  opened for a plugin (`PluginValues.open(set, member)`) had no bot constants behind it, so a flow naming
  `Activities.Collect.class` read as nothing and every write of one was refused: the SDK's flows stayed `Flow.NONE`.

### Added

- **`PluginValues.classes()`**: the bot's own top-level classes, the plugins' holders under `plugins/` left out,
  for a plugin's *Create method* to offer.

### Changed

- **Studio hosts contract 0.9.0**: `HostPluginValues`, `HostValueContext` and `HostRuns` implement the typed
  operations — a plugin's value addressed by its `ManagedValue`, an open set's constant by a `Member`, a run property
  by a `RunProperty` — over the same spelled lookups as before. The debug toggle reads and writes `Runs.DEBUG`.
- **A plugin's conditional parameter** (`ManagedValue.Reason.when`): written into a bot on bind only while it
  applies, listed in the Parameters window and the Runner only while it applies, read against the bot through
  `HostParamScope`. A plugin opens its own parameter (`PluginValues.open(param)`, the field carrying its mark,
  written as a Parameters row is) and asks for one its bot lacks (`create(param)`).

### Added

- **A reference to a constant can cross by its name** (`ManagedValue.referredToAs`, read in
  `project/managed/ManagedConstants`). In a set declared so, `Flows.DAILY` inside another value reads as the
  plugin's value for "DAILY" and is written back as `Flows.DAILY`. Its initialiser is no longer read, so an empty
  flow, or one equal to another, keeps its name, and one flow naming another never reads the other's contents.

- **A class written by several factories** (`DeclaredCall.when`, `plugin/grammar/ValueGrammar.writerOf`). The
  host writes a value with the first declaration of its class that accepts it, and reads a call of any of them.
  A link declared twice under one name (`.whenOff(Class)`, `.whenOff(Flow)`) is read as the one whose part
  reads (`ExpressionReader`).

- **A plugin can move one constant of its open set above another** (`PluginValues.moveBefore`, done in
  `project/managed/ManagedSets.moveBefore`). The declaration moves whole, javadoc and annotations included,
  after one Project History snapshot. The move is refused when it would stop the bot compiling, when the
  constant shares its declaration with another field, and always for an enum's constant, whose `ordinal()`
  would change. A constant already above is left alone and takes no snapshot.

- **An upgrade warns before it loses values with their set.** When the plugin's target version no longer
  declares an open set the bot has values in, under the same id and type, *Upgrade* lists each set: how many of
  its values have no `@Migrated` copy yet, and what its plugin points to. It then asks *Migrate first*, which
  closes the window and opens the migration window on the version the bot has now, or *Upgrade anyway*, which
  runs today's repair after its safety version. *Migrate first* is offered only when the version bound now has
  a type to copy them into (`project/managed/MigrationSteps.stranded`, `PluginUpgradeService.Report.stranded`).
- **After a migration: switch uses, remove originals, remove the migration.** In the migration window,
  *Switch uses to the copies* points each use of `OldShots.ORE` at `Shots.ORE`; a retired value's right-click
  does it for that one value. Only a copy the migration wrote counts — still marked `@Migrated`; one the author
  made or edited is switched to by hand. A use the copy does not fit (held as the old type) is left as it is, and its
  function is marked `@Refactor` so it shows in Review; the bot never stops compiling. *Remove originals…*
  deletes, after asking, each old value that nothing uses and whose copy is still marked `@Migrated`. Once no value
  of a migrated type is left, Review offers to remove the `@Migrate` methods nothing needs, and `Migrations.java`
  with them when it holds nothing else (`project/managed/MigrationCleanup`, `ui/app/MigrationActions`). A method
  is kept while its old type cannot be read (its plugin not loaded) or is named anywhere outside the `@Migrate`
  methods, its own file included. Each step is one version after a safety one.
- **Migrate a retired type's values in guided steps.** The Review tab lists each type a plugin retired that the
  bot still has values of ("312 values of ImageTemplate, which its plugin retired for Picture; 12 copied.") with
  *Migrate…*; a managed value's right-click offers *Migrate type…* for any type that fits. The window
  (`ui/app/MigrationWindow`, model `project/managed/MigrationSteps`) lists the new type's parts. Each part comes
  from an old part, is built from one by its type's one-part factory (`Path.of(file)`), or is a fixed value in
  the type's own editor. Parts are matched by name, then type, at first. A part of another type opens its own
  step; an enum pairs its constants. *Preview* shows each value before and after. *Apply* writes the steps as
  `@Migrate` methods (in `Migrations.java`, or over the method already mapping the pair) and the copies as one
  version, and creates the new type's holder when the bot lost it. A method written by hand is said, and
  replaced on Apply; two mapping the same pair are refused until one is deleted. A file edited while Apply
  worked is never overwritten: nothing is written and Apply asks again.
- **A migration copies a deprecated type's values into its replacement** (`project/managed/ManagedMigrations`).
  The author's `@Migrate(Old.class) static New m(parts…)` methods are read off the bot and
  substituted, never run: each constant of `Old`'s open set is taken apart, its parts put where the parameters
  stand, and the result written into `New`'s open set under the same name, marked `@Migrated`. A part whose type
  moved too goes through its own `@Migrate` method; an enum's is a `switch` over the constant. Originals are
  never touched; a rerun rewrites only constants still marked, and editing one through its window takes the
  mark off. The plan compiles as the whole bot before it is offered. Deprecated types leave the type menus.
- **▶ Start the bot from main.** Inside `main`, the + menu leads with *START THE BOT* and the plugins' entry
  points (*▶ Run activity flow*) while `main` calls none; they stay in their plugin's submenu too. Running a bot
  whose `main` starts nothing shows a Problems line: "main never starts the bot: add ▶ Run activity flow".
- **Claude outside Studio changes the bot only through Studio.** Opening a project adds rules to its
  `.claude/settings.json` (keeping yours) that deny Claude Code's file edits under `src/` and in-place shell
  edits, and *Copy Claude Code setup* now copies a full `claude …` command that starts it as the Assistant tab
  does, file edits denied, to run in the bot's folder. Its token stays in a private file, not on the command.

- **The assistant looks at a failed step's own frame.** Its instructions say that a wait which timed out may name
  a frame the run saved, to be looked at with the plugin's tools instead of a fresh screenshot.

- **A plugin's own parameters.** A `@Param` field that also carries a plugin's mark (`ManagedValue.param`) is the
  plugin's. The Parameters window shows its name, type, category, note and audience as fixed, and changes its
  value only; it cannot be removed. On every bind Studio writes such a field back into the plugin's holder when
  it is missing.
- **A parameter initialised with one of the bot's constants is editable.** `Sdk.flow = Flows.MAIN` and
  `Set.of(Activities.Rest.class)` read as values in the Parameters window and the Runner, instead of being kept
  as written.

### Fixed

- **Run activity flow inserts a call that compiles.** It landed as `Bot.runFlow(new Flow(), false, new Set(),
  new Class())`, refused with "The constructor Flow() is undefined". The index holds `Class<?>...` as `Class`
  with varargs set, so the entry-point fill never ran, and the bot's `Sdk.java` counted only once an editor had
  parsed it. Now it inserts `Bot.runFlow(Sdk.flow, Sdk.doSetup, Sdk.skip, Sdk.class)`, imports `Sdk` from its
  own package, and writes a parameter's first value (`Set.of()`) where the holder lacks the field.
- **A constant picked by name stays that name.** A Parameters row writes the constant an editor names
  (`ValueContext.writeMember`) and says which one it holds (`member`), so `Sdk.flow = Flows.EZF` is written and
  shown as EZF even when another flow is equal to it. A field that only holds a value with a name of its own
  (`EZF = Flow.NONE`) no longer takes that name over: `Flow.NONE` is written as `Flow.NONE`.
- **A holder the bot has gets back the values it lacks.** A plugin's method-shaped value missing from a holder
  the bot already has is written into it, with its starting value, when a plugin window asks for it. It is not
  written on bind, because the user may have deleted it. A method of that name already in the class is left
  alone. A `Sdk.java` an older SDK wrote had no `run()`, so the Activity Flow window could not save,
  and reopening the project never fixed it.
- **A generic plugin parameter is written.** A parameter typed `Set<Class<? extends Outcome>>`, the SDK's `skip`,
  was skipped with a warning when its holder was written or kept. Its type now reaches the grammar as a set.
- **"Run activity flow" writes every argument.** In `main` the insert menu fills each argument before the values
  classes with the plugin parameter of that type: `Bot.runFlow(Sdk.flow, Sdk.doSetup, Sdk.skip, Sdk.class)`. It
  wrote `Bot.runFlow(null, false, null)` since the entry point gained them.

- **Fewer calls for an assistant over MCP.** `run_bot`, `run_activity` and `try_statement` take `wait`
  (seconds, up to 50) and then answer with the run's end and its own trace, or say it never started. `read_trace` reads the latest run
  only unless `this_run` is false, and `errors_only` keeps warnings, errors and exceptions. An edit that adds,
  removes or moves statements answers with the methods it reshaped and their ids, so `read_tree` is not called
  again.
- **An upgrade repairs the plugin's managed values like the rest of the bot.** The upgrade loads the target jar
  beside the bound one and reads the bot against it. A value whose id is gone, or that the plugin declares
  another way now, is removed, with its file when nothing else is left in it; while the bot's own code still
  names it, it is kept as the user's code with its mark removed. A value that no longer compiles takes its first
  value and is marked `@Refactor` for the Review tab. The fix list shows each of these first, and the success
  sentence names them. Nothing refuses the upgrade (`ManagedRepairs`, `PluginUpgradeService.repairValues`).
- **A macOS build (Apple Silicon).** CI's `package` job gains a `macos-14` leg
  that builds an unsigned `.dmg` and a zipped `BotMaker Studio.app`. The jar keeps only the arm64 macOS
  natives (`natives-mac`, `natives-mac-arm64`). The `.icns` is built from the PNGs by `iconutil`
  (`installers-mac`). Capture and input come from shared's `MacController`, untried on a Mac. A manual
  `macos` CI job runs the suite there.
- The updater picks the `-macos-arm64.dmg` on Apple Silicon (an Intel Mac is offered nothing), opens it in
  Finder and says to drag the app onto Applications. The macOS package leg is `continue-on-error` until a tag
  builds it green, so it cannot cost a release its Linux and Windows assets.
- **The stop key and View ▸ Stop Key… work on macOS** (shared's event tap). A Mac has no Pause key, so its
  default is F12 (fn+F12 on a laptop); the chooser says so. Key names come from shared's `KeyNames` where
  there is no libX11. Studio needs Input Monitoring; without it the run says the stop key is unavailable.
- On a Mac opened from Finder, the Assistant finds Claude Code and the other AI tools in Homebrew's bin too;
  the terminal falls back to `/bin/zsh` when `$SHELL` is unset; and the program picker starts in the given
  folder and returns an `.app` as its path, without the trailing slash.

### Removed

- `ScreenCaptureService.resizeTarget`, `TargetCapture.resizeTarget` and `ResolutionChoices`, which nothing
  called: the SDK's run resizes a game window to the bot's screen size itself.

### Fixed

- **An upgrade no longer drops a value the new plugin version lost.** A bot marking
  `@SdkValue(SdkValue.Id.OUTCOMES)` was read against the version it moved to, which has no `OUTCOMES`, so the
  mark read as none and the upgrade went ahead, leaving code that did not compile. Such a mark now still names
  its value, and the upgrade is refused unless the new version converts it.
- **A managed constant inside a value is read and written.** `Flow.edge(Activities.Battle.WON, …)` used to read as
  nothing: the bot's constants were consulted only for a value that was itself one constant. They are now
  consulted at every depth (`plugin/grammar/BotNames`), when reading and when writing.

### Added

- **An entry point names the bot's values classes for you.** Inserting a plugin's `@EntryPoint` that takes
  `Class<?>...` — the SDK's *Run activity flow* — writes every values holder the project has:
  `Bot.runFlow(Sdk.class)`, not `Bot.runFlow()`. The overlay editor's palette no longer offers entry points.
- Open sets of nested enums (contract `ofEnums`): their constants and class literals are read and written, and
  the holder is created as an empty class. Whole enums can be added, renamed and removed (`ManagedSets.addGroup`
  and `removeGroup`). Constants inside a nested enum are named `Group.NAME`.
- `PluginValues.methods` and `createMethod` (`project/managed/BotMethods`): the bot's methods that return a
  nested enum, or `void`. A new one is written into an existing class, or into a new file next to the main
  class.
- The statement menu offers an `@EntryPoint` member only directly inside `main` (`StatementPlacement.inMain`).

### Changed

- **Getting Started and `WORKFLOW.md` describe the new activity flow:** each activity is an enum of its
  outcomes, a body returns that enum and is picked or created under *Runs*, and `main` calls
  `Bot.runFlow(Sdk.class)`.

No source changes since v1.4.1; re-released for updated upstream pins.

No changes since v1.4.0 but the SDK it falls back to: v1.4.0 named SDK v1.4.0, which never built on JitPack,
so a project created by it could not resolve its SDK. This one names SDK v1.4.1.

## [1.4.2] — 2026-10-10

No source changes since v1.4.1; re-released for updated upstream pins.

No changes since v1.4.0 but the SDK it falls back to: v1.4.0 named SDK v1.4.0, which never built on JitPack,
so a project created by it could not resolve its SDK. This one names SDK v1.4.1.

## [1.4.1] — 2026-10-10

No changes since v1.4.0 but the SDK it falls back to: v1.4.0 named SDK v1.4.0, which never built on JitPack,
so a project created by it could not resolve its SDK. This one names SDK v1.4.1.

## [1.4.0] — 2026-10-10

### Added
- **Enum open sets.** A plugin's set declared `ofEnum` is an enum in the bot: its members are the enum's
  constants, and adding one writes a bare constant after the last. Rename, repoint and remove work by binding as
  they do for a class's fields. A missing holder is written as an empty enum carrying the plugin's mark and
  implementing the element type. A reference to a constant reads as the value the plugin makes of its name, and
  a value equal to one is written as that constant (`ManagedSets`, `ManagedConstants`, `ManagedHolders`). A
  marked enum is locked like a marked class (`LockResolver`).
- **Values written as named chains.** A type its plugin declares with withers is written as its factory call
  followed by a link for each part that differs from what the factory makes —
  `Flow.activity(COLLECT, Collect::body).described("…").goesHome()` — and read back with the links in any
  order, a link left out reading as the factory's. A chain setting one part twice, or a link no declaration
  has, stays as written. The Parameters window labels a wither's field by the wither's name.

### Changed
- **A plugin's typed marker is a managed value's mark, and `@Managed` is no longer read.** Studio reads a
  plugin's own annotation marked `@ManagedMarker` — `@SdkValue(SdkValue.Id.FLOW)` — as the value it names, on
  a method or on an open set's class: the plugin's window opens and rewrites it, the canvas locks it, ▶ Try
  names its holder, and its pill reads as a plugin's. A missing holder is written with the plugin's annotation
  and a method named after the constant (`flowLayout()` for `FLOW_LAYOUT`). A bot still marked `@Managed` is
  ordinary code until it moves to its plugin's annotation.
- A file whose open-set constants were read before its plugin was bound is read again once the plugin is, so
  the constants are found.
- Adding a constant to an open set that two classes carry is refused, naming both, instead of going into
  whichever was found first.
- A plugin's toolbar button that shows a run property refreshes when the plugin sets it: `Runs.setProperty`
  announces the change as every other settings write does. The SDK's 🎮 game button needs this.

### Removed
- The unused global list of recent launch targets in the preferences file (`recentLaunchTargets`). Nothing
  wrote it since the launch-target dialog moved to the SDK plugin, whose game dialog keeps its recents per
  computer in the run properties. An older preferences file still reads.

### Added
- **Installing Studio installs gamescope and Xephyr too**, the two servers a bot's private display runs on. The
  rpm and the deb recommend them (`Recommends:`), so dnf and apt install them by default and you can remove
  them afterwards.
- **Dev mode: try a plugin before it is released.** *Project ▸ Plugins & Libraries* has a *Dev mode* box, per
  project and only on this computer. While it is on, plugins pinned at a `-SNAPSHOT` version (the build
  `mvn install` put in `~/.m2`) load. *Reload plugins* picks up each rebuild, and a banner above the canvas
  names what is loaded. A plugin refused outside dev mode offers *Use dev mode* on its row. A Studio run from
  source gives a dev-mode project its own unreleased contract. Publish still refuses a `-SNAPSHOT` pin, and
  turning dev mode off moves the contract entry back to the released one.
- **Dev mode offers your local builds.** In a dev-mode project, *Browse* lists every plugin built into
  `~/.m2` at a `-SNAPSHOT`, marked *(local build)*. That includes your own and, from the umbrella, the SDK,
  plugin-basics and the rest. Each *Installed* row's version list offers its local build too, the SDK's
  included, through the usual checked pass.
- **A plugin declared twice is one row, and Studio loads one copy.** A pom naming a plugin under both
  `com.github.LiQiyeDev` and `com.github.BotMakerDev` (the SDK pinned before the move, then installed again)
  had both jars on one loader, so Studio ran a mix of the two versions. Studio now loads the new groupId's
  copy and its tree, or the old one's when the new one is a dev build outside dev mode. *Installed* shows one
  row flagged *Declared twice*, with *Keep <version>* for each copy. Browse no longer offers to install a
  plugin the pom already declares under the old groupId.
- **Installed says what each plugin loaded.** A *Loaded* column names the jar each plugin came from,
  `(dev build)` included, or *not loaded* with the reason. Plugins another plugin brings (plugin-basics with
  the SDK) are listed read-only, *comes with another plugin*. Browse's *Included* is now *Installed with
  another plugin — view…*.
- **Installed: one button per row.** A row opens on its installed version. Its button reads *Upgrade to X*
  when a newer release is known, or *Switch to X* once another version is picked in the menu, a downgrade
  included. One click runs that row alone. *Upgrade all* moves every row that has an upgrade, in one pass.
  The *Check* button, the report under the table, *Snapshot, repair & switch* and *Pin released versions* are
  gone; a pass still saves a version first and repairs the bot's calls. A dev build in a dev-mode project
  shows *Dev build* and is never offered an upgrade.
- **An upgrade asks a fix for every place it breaks, before writing anything.** When a move breaks calls,
  a list opens with each place in source order and the fixes for it as buttons: Studio's suggestion, each
  member a split one became, a default value marked for review, or deleting the call. A type the bot writes
  that is gone is listed to acknowledge. Picking a fix moves to the next one; *Use the suggested fix for the N
  left* answers the rest. *Upgrade* stays disabled until every place has a fix, and Cancel changes nothing.
- **Remove asks in a window that fits.** One sentence counts the calls and types it changes, the plugin's
  files are a scrolling list with one checkbox, and the buttons stay at the bottom. It replaces an alert that
  could open taller or wider than the screen.
- **Libraries points to Installed and marks unneeded editor dependencies.** A link says how many plugins are on
  the Installed tab. A library only an absent plugin lists as an editor dependency is marked *not needed*.
- **The overlay editor docks beside the game.** ⧉ Overlay opens a panel beside the window your bot watches —
  with the SDK, the one `Sdk.captureSource()` names — without asking; ⇄ Change picks another. It follows the
  window, drags off to float (⇲ docks it again) and resizes from either edge. Chips across the top are your
  activities: pick one and its file opens in the editor and the caret goes inside it. The script below has one
  ⋮ per row (configure, move, delete); ▲▼ always move the caret. The tabs at the bottom are the plugins' tools —
  with the SDK, *Picture* cuts a picture off the game and can insert a click on it, *Point* inserts a click at
  a spot, *Flow* opens 🔀 Activity Flow — then *Actions* (the plugins' overlay buttons) and *Blocks*. While the
  bot runs, its stop, pause and trace sit in the panel's header instead of a second window.
- **The overlay editor shows what each row would do now.** A row whose call a plugin can check — with the SDK,
  finding, waiting for or clicking a picture — gets ✓, ✗ or ? (hover for *found 0.94 at 412,230*). The row the
  caret is on is checked twice a second and its match is boxed on the game, orange where a click would land;
  ⟳ checks every row, and so does the end of a run. Nothing is clicked. A bot that watches its private
  display session is checked on the session's own pixels, and the box is drawn on the window showing it.
- **▶ Try one statement, or run one activity.** Right-click a block on the canvas, or open an overlay row's ⋮,
  and pick *▶ Try this statement*: it runs on its own against the screen as it is now, as a run — Stop, the
  run bar, the Run and Trace tabs — and the Run tab says what it found or returned. Values it reads from earlier
  lines are taken from the last debug pause, computed again when they only look (a picture search), or asked
  for with the type's own editor. A static method that takes nothing — an activity's body — offers *▶ Run …
  on its own*, and so does the overlay's ▶ Run ▸ *Run this activity*. Needs a plugin with a trial entry (the
  SDK's); nothing is written into the bot.
- **The assistant works on the whole bot, and runs it.** Over MCP, every file tool takes a `file`, so Claude
  edits the activity it is automating rather than only the open file — an edit to another file opens it in the
  editor first, as one undo step there. `apply_edits` makes several inserts, slot sets and deletes in one call,
  kept all together as one undo step or not at all. A slot takes one of the bot's constants by name
  (`Pictures.ORE`). New tools: `list_files`, `list_methods`, `list_targets`, `set_target`, `move_caret` and
  `show_area` (the overlay editor's caret and a box on the game), `run_bot`, `run_activity`, `try_statement`,
  `stop`, `run_state` and `read_trace`. Nothing starts while something runs. The plugins' own tools are served
  too — with the SDK, `sdk_screenshot`, `sdk_crop_picture`, `sdk_find_picture` and the rest, pictures coming
  back as images. The game is still touched only by running the bot's code; there is no click or key tool.
- **The assistant shapes the code, not just the blocks.** `add_method` (types Studio offers, by name),
  `edit_signature` (the file's calls follow; refused when another file's call would break), `move_block` and
  `add_file`; `find_usages`, `rename` (every file, one undo step) and `open`, each naming a declaration as
  `Collect.body`; and the Review list — `list_review`, `mark_reviewed`, `remove_mark`, `undo_change`.
- **The overlay editor sees what the bot sees.** With a plugin that grabs its own frames (the SDK), the
  probes, the tool tabs and the assistant's screenshots read the bot's capture source itself: a bot narrowed
  to a region is checked in that region, a bot watching a monitor on that monitor. The panel still docks
  beside the watched window, and Studio grabs that window itself when the plugin gives no frame. The private
  display session is still Studio's own.
- **The assistant keeps versions, picks plugins and sets parameters.** `list_versions`, `checkpoint` (a named
  version, a milestone in Versions) and `revert` (what the bot is now is saved first; the project reloads, which
  ends an assistant session in the Assistant tab). `list_plugins`, `search_plugins`, `add_plugin` — you are asked
  on screen first — and `remove_plugin`, which only says what would change until it is confirmed, and asks you
  too. A plugin added or removed this way serves its own tools at once. `list_params` and `set_param_default`
  set a `@Param` value in the code; `get_settings` and `set_setting` change 🐞 Debug output and what the Trace
  tab hides. The server's instructions now walk a client through the loop: look, pictures, blocks, try, read the
  trace, fix. A bot that is a submodule (the template) keeps its versions to you: the assistant saves and
  restores none.
- A key stops a running bot from anywhere on the desktop, without bringing Studio back: Pause by default,
  changed in View ▸ Stop Key…. It works while Studio has a run or a debug session going, X11 and Xwayland
  windows only (Linux), and the focused program receives the key too.
- **A run overlay: a small window over the desktop while the bot runs.** It stops, pauses, resumes and runs
  the bot again, and shows the trace's last three lines. Drag it anywhere; it opens there next time. ✕ hides it
  until the next run, and View ▸ Show Run Overlay turns it off. Pause works for a plain run on Linux and
  macOS; a debug session pauses through its debugger. A plugin can add its own part: a line in the bar, or
  marks drawn over the desktop that let every click through. Studio shows those marks only where it can make
  the window click-through (X11 and Xwayland), and draws nothing of its own there during a run.

### Changed
- The pom carries a real version and names its upstreams' versions instead of `0.0.0-SNAPSHOT`, and the
  module's `.deps.env` is gone (umbrella `docs/refactor/43-real-versions.md`). A local SDK build now shows as
  its real `-SNAPSHOT` in the version lists. A release is built against the upstream releases its pom pins,
  resolved from JitPack, and the contract a new `@Param` brings into a project is that pin. *About* calls a
  locally built shared *local build*.
- **Plugins and the SDK are `com.github.BotMakerDev` now** (were `com.github.LiQiyeDev`): a new project, an
  installed plugin and the contract entry Studio declares all name the new groupId, and the plugin search
  looks there. A project whose pom still names `com.github.LiQiyeDev` is not migrated: it keeps resolving
  its old tags, but Studio no longer recognises those entries as its SDK or contract.
- **A Review row is a menu.** Click one to go to it, mark it reviewed, remove the mark, undo the change (the
  function comes back as the newest version without the mark had it, with the imports it used), or rename or
  delete the function — the same rename and delete as its header's, refusals included. The *Mark Reviewed*
  button is gone; each action saves a version first.

### Removed
- **⏺ Record is gone from the overlay editor**, with its *Record with* menu. The panel's tool tabs do its job
  — turn what is on screen into a call — one picked thing at a time, and the window picker lists only windows
  that are open now.

### Fixed
- On a scaled screen, the overlay editor's desktop grab and its screen crops are in device pixels, the same
  pixels as a window's rectangle. The grab was at the logical size, and with mixed scales the screen chooser
  cropped a second monitor by the wrong offset.
- The 🎮 Pilot failed with `NoClassDefFoundError: io/javalin/websocket/WsContext` after an SDK declared under both
  `com.github.LiQiyeDev` and `com.github.BotMakerDev` was reduced to one. Removing the old copy also removed the
  libraries the Pilot needs (javalin, zxing), which the kept copy still used. Removing one copy now keeps them.
  A registry entry under either groupId now counts for the plugin. Opening a project puts back any of these
  libraries its pom has lost, but only ones the plugin's own pom marks `optional`, so the registry cannot add
  anything else. A bot opened read-only is left alone.
- The type chooser (Declare Variable, a function's inputs and result, Parameters) could not take a pick back:
  clicking `Map` in `Map<List<String>, Integer>` and picking `char` replaced the whole type. Its header now has
  *↶* beside *Done*, and Ctrl+Z works too. Each press goes back one change. In Declare Variable and Add Function
  the history also outlasts the menu. Edit Variable and Parameters rebuild the chooser after each write, so
  there the editor's Ctrl+Z, or the dialog's own undo, takes a change back. On the canvas, picking `HashMap` for a `Map` part keeps
  `<List<String>, Integer>`, and Ctrl+Z takes back a type change as one step.
- Pressing ▶ Run again while the bot was still compiling started a second bot beside the first, and Stop pressed
  during the compile was lost, so the bot started anyway. A run now counts from the press, and a Stop during
  the compile ends it before the bot starts.
- Removing the last plugin from a bot no longer leaves it unable to compile its own `@Refactor` marks
  (`package com.botmaker.plugin.api.meta does not exist`): the plugin brought the contract, so Studio now
  declares the contract after any pom change that took it away, not only when the project opens. That entry
  goes again once a plugin brings the contract at the same version or newer — left beside the plugin, it
  would pin the bot to it instead of the plugin's. A plugin with an older contract, or one whose entry
  excludes it, leaves it in place.
- A refactor that marks several functions of one file writes one `import …Refactor;`, not one per function.
- Removing a plugin offers to delete its files (`plugins/<id>/Sdk.java`, `Pictures.java`…), ticked by
  default, and lists the lines of your code that still name them. Ticked, the files go, their imports go,
  and each function still naming one is marked for review; the version saved first brings them back.

## [1.3.0] — 2026-10-05

### Changed
- New Project opens on the templates, not on Blank: it shows the gallery's last list at once, and Create
  waits until the gallery confirms it. On a first run it says it is loading; Blank is offered only when the
  gallery cannot be reached.
- The theme styles the contract's new text roles (`text-strong`, `-warning`, `-error`, `-ok`, `-muted`,
  `-small`, `-mono`) in every theme, so a plugin's status lines read in the dark themes.
- New Project's plugin checklist names each plugin's version: the template's pin for its own plugins, the
  registry's verified release for the rest.
- A value a plugin's editor hands over that Studio has no Java for is no longer dropped without a word: the
  field keeps what it had and the status line says why. A plugin can ask itself through the contract's new
  `ValueContext.write`.
- A plugin adding a constant to one of its open sets (a picture to `Pictures`, an activity to `Activities`)
  is refused, with both classes named, when the value is not the kind the set declares holding.
- Studio loads released plugins only. A plugin pinned to a dev build (`-SNAPSHOT`) is not loaded; the
  editor says so, and *Project ▸ Plugins & Libraries ▸ Installed* offers **Pin released versions**, which
  moves each one to a release through the usual checked upgrade.
- A Studio built from source gives a new project the latest released plugin contract instead of
  `0.0.0-SNAPSHOT`.
- The getting-started text describes activities and outcomes as constants (`Outcomes.BAG_FULL`,
  `Outcome.NEXT`), matching the SDK's typed flow names.
- Publish refuses a bot whose pom pins a `-SNAPSHOT` or an undefined `${property}`, naming each one: the
  release would compile on this computer and nowhere else.
- The plugin upgrade report opens with one line saying what the move costs ("Studio repairs 4 calls · you
  finish 1 removed type · nothing else changes", or "Nothing breaks"). The "What breaks in this bot" list
  is gone: it repeated the repair card and the finish-yourself list. The repair card now lists where each
  repaired call is.
- The welcome screen's project list is one list, sorted as chosen. Projects opened from outside
  `~/BotMakerProjects` were always listed last under their own header, whatever the sort; they now carry an
  *Elsewhere* tag instead. *Newest first* reads when Studio last opened a project, or its folder's date when
  that is later, so the project you just opened is on top.

### Added
- A gallery bot whose author deleted its repository still installs, from the copy of its releases the gallery
  keeps (the `mirror` release of `botmaker-gallery`). A template unpacks from the same copy, and the newest
  release is read from it. The installed bot has its own history and no original to update from, and Browse
  Bots says so: "author removed the repository · no updates".

### Removed
- Local `~/.m2` builds of plugins and the SDK are no longer offered in Plugins & Libraries, Browse or New
  Project, and the `(local build)` labels are gone. Pin a local build in the pom by hand to try it.

### Fixed
- A project opened a second time drew its function-call blocks as nothing, logging "Classpath element is not
  known for this classpath element" on every redraw. The library index is reloaded from its cache then, and
  the class dropdown asked ClassGraph which jar each class came from, which a cached class cannot answer; the
  index now keeps that itself.
- Studio takes no automatic version (before a run, a debug, an AI session, a rewrite) in a git submodule,
  whose history is also the umbrella's releases. A "Run" version had swept an old Studio's leftover files
  into the gamebot template, one push from its `main`. Versions you save, updates and upgrades are still
  written there.
- An old Studio's `src/main/resources/botmaker-project.properties`, which nothing reads since 2026-09-27, is
  kept out of every version (`.git/info/exclude`); it is never deleted, and a project that tracks it keeps it.
- A push GitHub refuses as "git-receive-pack not permitted" says what it usually means on an organization's
  repository: the organization has not granted BotMaker Studio access, with where an owner approves it.
- A project checked out as a git submodule (its `.git` is a file) has its history again. Studio read it as
  "no history" and *Publish* failed with "Creating directories for …/.git failed".
- An SSH remote (`git@github.com:owner/repo`) counts as a GitHub repository, so a project cloned by hand reads
  as published. A submodule's `origin` is copied to *My copy*, never renamed, so tools that push to `origin`
  keep working.
- Publishing a project whose copy lives under an organization you can push to (the BotMakerDev templates)
  publishes there: the last release, the listing and its Vetted tier are read under that owner, instead of
  under your account, where the bot looked never published. Studio still creates repositories only on your
  own account.
- A provenance file naming the project's own repository (written by an earlier publish, or before the
  repository moved to an organization) no longer links that repository as somebody else's original.
- The plugin version picker starts on the installed version, and offers the registry's verified one only
  as a move up. A bot on SDK 1.2.3 opened on 1.1.7 and, when JitPack's list came back empty, offered
  nothing else. Both are labelled in the list, and JitPack gets 20 seconds instead of 8.
- A plugin pinned as `${property}` resolves its jar in the plugins window, so it is recognised as a plugin
  and its type names count when two plugins clash.

## [1.2.3] — 2026-10-01

### Added

- **Pick plugins when you create a project, whatever you start from.** New Project lists the same plugins as
  *Plugins & Libraries ▸ Browse*; tick any to have them in the project's first version. The chosen template's
  own plugins are shown ticked and locked. With no network the list is empty and creating still works.

### Fixed

- **A project pinning a plugin as `${property}` loads it.** Both templates pin their plugin that way
  (`${botmaker.sdk.version}`, `${botmaker.basics.version}`); Studio handed the placeholder text to the
  resolver, which found no such version, so the plugin did not load. The property's value is used now.
- **Installing a plugin that brings another plugin you already declare removes the redundant entry.** Adding
  the SDK to a project made from the Base template left the template's own `botmaker-plugin-basics` entry in
  the pom, which pinned a basics version the SDK was not built against. The entry is now removed, and the
  status line says so.

## [1.2.2] — 2026-10-01

### Added

- **A bot's question is a real dialog.** When a bot calls `Ask.choice(…)`, `Ask.yesNo(…)` or another `Ask`
  question, Studio shows the prompt with a list of the choices, Yes and No buttons, or a text field, and sends
  the answer back on the run's telemetry channel. Closing the dialog cancels the question. This replaces the
  generic "The bot is waiting for input" box, which could not show what was asked. Needs the SDK release that
  brings `Ask`; a bot on an older SDK that still calls `BotMaker.readLine()` gets no prompt.

- **The Trace tab shows every call your bot makes into a plugin**, with no trace code in the plugin:
  `[ImageFinder] find(ORE) → Match[…]  12 ms`, or `threw …`. Studio starts each Run and Debug with a small
  agent that rewrites the bot's own calls into the plugins' offered classes as they load. A call repeated in a
  loop is one line and a count. A plugin can leave a member out with the contract's `@Untraced`.
- **Writers lists the traced classes before they write anything.** Untick a class or a method and its calls
  are not traced at all from the next run.

### Fixed

- **The run diagram describes today's entry point**: `main` calls `run(goHome, Sdk.class)`; it still named
  `Sdk.install()` and `Bot.start`, both gone from the SDK.
- **A recorded wait before a click is a `Duration`.** The recorder hands the wait its timeout in
  milliseconds, so `ImageWaiter.waitFor(Pictures.ORE, Duration.ofMillis(6000L))` is written; the SDK's
  whole-seconds shape it used to fill is gone.
- **A plugin call from the statement menu no longer lands unresolved.** `Mouse ▸ click` and `Wait ▸ time`
  wrote `new Point(0, 0)` and `Duration.ofSeconds(1)` without importing `Point` or `Duration`; each seeded
  argument's type is now imported with the call.
- **Set Variable asks which variable first.** It is a submenu of the variables in scope where the block goes;
  it assigned the first one and let you change it afterwards, and with none in scope it inserted nothing.
  With no variable it now says so.

### Changed

- **The class dropdown lists only what a plugin offers.** `ActivityContext`, `Debug`, `Point` and every other
  value or plumbing type left it; "Java & Libraries" and the expression menu's "Library (static)" leave out
  every class of a plugin's jar, and a plugin class its palette does not reach offers no members. Plugin
  classes are listed alphabetically.
- A slot editor can ask which of the bot's methods it sits in (`Owner::method`), for the outcome picker.
- The Workflow guide describes an activity body as `public static Outcome body()` returning
  `Activities.outcome("…")` or `Activities.next()` (SDK 2.0.0).

## [1.2.1] — 2026-09-29

### Added

- **Find (Ctrl+F).** A bar over the canvas finds text in the open file — names, values, comments — and jumps
  from block to block with Enter and Shift+Enter.
- **Find in Project (Ctrl+Shift+F).** Lists every line of the bot holding the text; pick one to open its file
  on that block.

### Changed

- **A plugin upgrade or removal is never blocked.** Everything that used to refuse one is now repaired, or
  left as written and marked `@Refactor` so the Review tab lists it. Studio says what it left when the upgrade
  finishes. The cases:
  - A class the new version no longer has: calls on it get a default value, and places that write the type
    itself are left for you to change.
  - A file that does not parse, or a `case` label whose enum cannot be told: that file is left as written and
    the rest is repaired.
  - A name two plugins both declare: it is left alone.
  - A call nobody chose a replacement for: it gets a default value and a review mark.
  - A check that could not read the project: the version still moves.
- **One window for plugins and libraries.** *Project ▸ Plugins & Libraries…* replaces Manage Libraries,
  Manage Plugins, Reload Plugins and Upgrade…:
  - **Installed** lists every plugin with its version. Update or remove it there, or press **Update all**.
  - **Browse** installs from the registry or a local build.
  - **Libraries** holds your other Maven dependencies. Plugins are no longer rows there, so a plugin's version
    only moves through its checked upgrade.
  - **Reload plugins** and the list of plugins that did not load sit at the top and bottom of the window.
- **Manage Imports is gone.** Every block you add already imports what it needs.

### Fixed

- **A maximized window stays maximized.** Opening a project, or going to the Run view and back, could drop
  a maximized Studio to its smaller size, and Studio then remembered the smaller size for the next start.
  The screen change no longer touches the window at all.
- **A plugin added outside Studio appears by itself.** A `pom.xml` edited by `mvn`, an IDE, a `git pull` or
  a restore now reloads the plugins without pressing Reload.

- **A plugin that did not load now says so above the canvas.** It also says whether the plugin is older or
  newer than this Studio, and offers **Update plugins…**. Before, a project whose SDK was built for an older
  Studio opened with an empty palette. The reason appeared only inside Manage Plugins, and it wrongly said
  *newer*.
- **Installing a plugin that cannot be downloaded no longer claims success.** Studio used to write the pom,
  fail to resolve the jar and report *installed*, and the plugin never appeared. Now the pom is put back and
  the dialog names the coordinate and the reason. Other dependencies that fail to download are listed in the
  same banner.
- **Plugin reloads no longer race.** An install and a background reload could overlap, and whichever finished
  last decided which plugins the editor used. Reloads now run one at a time.
- A version lookup that failed during install no longer leaves Manage Plugins disabled.

## [1.2.0] — 2026-09-29

### Added

- **A Trace tab next to Run** shows the running bot's debug lines, one row each, coloured by level. It shows
  the time, what wrote the line (`[Vision]`, `[Mouse]`, your own class) and how many times it repeated. Filter
  by level, pick one source, or search. Clicking a line shows the block that wrote it. Each run starts with an
  empty tab, and the last 5,000 lines are kept.
- **Choose whose debug lines the Trace tab shows, down to one method.** *Writers* opens a tree of everything
  that wrote a line, grouped as *This bot*, each plugin by name, and *Libraries*. Unticking a class or a method
  hides its lines. Right-clicking a line offers *Hide lines from Mouse.click()*, *Hide all lines from Mouse* and
  *Show every writer*. What you hide is remembered for the project on this computer.
- **🐞 Debug on the toolbar** (in the Run group) chooses whether runs on this computer print and trace their
  debug lines. Each press moves to the next choice: *bot* (the bot's own setting decides), *on*, then *off*. The
  choice is saved for this computer only and takes effect on the next run.
- **Pictures and data files open beside the canvas.** Clicking a `.png`, `.json`, `.properties`, `.txt`,
  `.yml`, `.xml`, `.csv` or `.md` file in Project Files opens it in a tab next to the canvas: a picture at its
  own size, JSON as a tree, a properties file as a table of keys and values, anything else as text. Viewing
  only. The tab strip appears only once something is open beside the canvas.

### Changed

- **Every plugin's method help shows on its blocks**, not only the SDK's: the summary and parameter notes
  come from each installed plugin's published sources, when it publishes them.
- **A plugin renames, repoints and removes its own constants by binding.** The picture library's rename
  used to be a find-and-replace over the bot's text; it goes through the same rename the canvas uses now, so
  the declaration is renamed with its uses, a local of the same name is left alone, and a change that would
  stop the bot compiling is refused. One Project History entry per change.
- **Studio spells the style classes a plugin wears with the contract's `StyleClasses`.** Thirty-two files
  typed `"primary-button"`, `"block-chip"` and the rest by hand; they use the constants now, and
  `StyleClassesTest` fails when a constant names a class `blocks.css` does not define, so renaming one can no
  longer leave a plugin's widgets unstyled in silence.
- **The Parameters window's row type is Studio's own.** `ParameterRow` and `Visibility` left the plugin
  contract, which no plugin used them from; nothing changes on screen.

- **A template needs no `botmaker-template.properties`.** Its package is the one holding its `main`, both when
  New Project copies it and when Publish checks it. A copy of an older template that still ships the file has
  it removed. The Publish sheet starts on *A starting template* when the gallery already lists the project as
  one.
- **The bot's settings moved into its own Java.** Delays, match confidence, real input, debug output and the
  private display are edited in the SDK's **⚙ Bot Settings** and written into `Sdk.settings()`; Studio's
  🖱 Input window and 🐞 Debug toggle are gone, and Studio no longer writes a `botmaker-project.properties` (an
  old one is left alone). What this computer launches for a bot stays on this computer and is passed to each
  run. The Publish sheet's **Tested on** is a row of checkboxes, filled in from your existing listing.
- **Go to Definition on a library shows the whole class as blocks.** The class opens in a tab beside the
  canvas, locked, with the member you asked for open and highlighted and every other function folded (open
  one to see its code). A library with no sources shows its signatures, each saying "No source for this
  library — signature only" where its code would be. This replaces the read-only text window.
- **A refactor that guesses says so with `@Refactor`, and you pick every guess.** Where an upgrade, a
  signature change or a plugin's rewrite puts a value you did not choose, the function is marked with the
  plugin contract's `@Refactor`; *Mark Reviewed* keeps it as `done = true`. Studio no longer writes a
  `NeedsReview.java` into your bot. In the upgrade window a call nothing replaces starts unanswered, and
  Apply waits until you choose a default value or deleting the line. Deleting a variable that is still used
  is refused with its uses listed and buttons to point them at another variable or put a default in.
- **Annotations show on the canvas.** Each one is a pill above its function, field or class. `@Param` and
  `@Managed` are read-only there; the others have a × that removes them.
- **A value its type's factory would lose part of is written as the chain that keeps it.** A held combo is
  written `Combo.of(Key.CTRL, Key.S).held(Duration.ofMillis(200))`; before, the hold was dropped on write. Only
  a chain a plugin declares, one link deep; every other value is written as before.
- **Local | UTC on a time parameter.** Beside a `LocalTime` or `OffsetTime` field's type, one click switches
  between this computer's clock and a time at an offset from UTC, keeping the hours and minutes (07:30 stays
  07:30). A value equal to a constant its type names is written as that constant: `ZoneOffset.UTC`.
- **An enum is always picked from its constants.** A `DayOfWeek`, `Month` or any other enum parameter offers
  **One of** and **Any of**, never "Any value": with nothing written down it is one of every constant (a list
  of it, any number of them). Its choices are a strip of toggles, all on at first; every one on is written as
  no `options` at all. An enum of more than 16 constants (`Key`) keeps the add-a-choice rows with its own
  picker, and a list of one is rows rather than a hundred toggles.
- **The type chooser changes the part you click.** Its header spells the type so far one name at a time —
  `Map` `<` `String` `,` `List` `<` `Point` `>` `>` — and a click selects the part a pick replaces, so a map's
  key can be changed as well as its value. *Wrap in* wraps the selected part; *Unwrap* unwraps the container
  at or around it. It starts on the innermost last argument, as before.
- **"Any of" keeps its type.** The type button shows the type being ticked (`int`), never the `List<Integer>`
  it is written as, and picking another type in "Any of" or "One of" stays in that mode, starting from the
  new type's first choice.
- **Choices on a list.** A `List<T>` can be one of several lists: a choice written as a whole list makes it
  "One of", and each choice is edited with the list's own rows.
- **A list or set of an enum is toggles**, one per constant in the enum's order — a list of days is seven
  day toggles, also in the canvas's container popover.
- **Choices are edited with the type's own editor everywhere**, text included, and a number's choices keep
  to its Range.

### Fixed

- **A type in the chooser is picked on the first click**, every time; a double-click picks it and closes the
  menu, and **Done** leads the header at full size.
- **An on/off switch is filled red when Off and green when On**, whatever block it sits in; it took the
  block's colour before.
- **Choices replace a Range.** Declaring choices on a number drops its range and hides the Range row: the
  choices are the limit.
- **Parameters offers no type only a running bot fills** (the vision results); a variable can still hold one.
- **A remembered window opens wholly on screen.** One saved on a wider monitor opened with its right side,
  and the buttons there, past the edge.
- **A Range limits the number.** A parameter's `@Param(min, max)` is handed to its editor, and a value written
  outside it is written at the nearest end.
- **The type chooser stays open** while a type is put together: wrap, pick, unwrap, then Done, Enter or a
  click outside commits it once; Escape changes nothing. Its header shows the type so far.

### Added

- **Live values while debugging.** While the bot is paused, each variable declaration, assignment and name
  block whose local is in scope shows its value in a small chip at the end of its row, with the whole value
  in the chip's tooltip. A vision result reads as a sentence from its field names: "found at 120, 340 · 93%",
  "“Start” at 10, 20 · 87%", "not found", "3 found". The chips go when the bot resumes or stops. Collections
  show their size ("ArrayList (2)") in the Debug tab too, and a bot frame's objects are read two levels deep.
- **Sets and stacks/queues.** A `Set<T>` (written `Set.of(…)`) and a `Deque<T>` (written
  `new ArrayDeque<>(List.of(…))`) are values like a list: wrap a type in them in the type chooser, edit them as
  rows. A set drops a repeated element rather than write one `Set.of` would refuse.
- **List, set and map rows move and copy.** Each row has ↑/↓ and ⧉ (duplicate) beside ✕.
- **A list, map, set or deque on the canvas is one pill** — "List · 3 items" — that opens the same rows in a
  popover; Apply writes it back, Cancel and Escape write nothing. A variable or another call there is left as
  written.
- **A value no plugin draws still gets a picker.** In the Parameters window and a Choices row, an enum is a
  dropdown, a number or text a field, a declared call one row per part (each drawn by its own picker), and
  anything else its source with **Reset** when a fresh value is known. Untouched, it writes nothing; an
  unreadable value changes only through Reset.

### Removed

- Studio's own `LocalTime`, `LocalDate`, `DayOfWeek` and `Month` canvas pickers. Basics draws the JDK types;
  without basics, the enum dropdown and the by-shape fallback do.

### Changed

- **Only a type its plugin draws gets that plugin's editor** (contract 0.3.0, `EditableType`). A plain
  `PluginType` is drawn by another plugin's editor for it, or shown as written until Studio's fallback lands.

## [1.1.11] — 2026-09-27

### Changed

- **Recording Ctrl+S writes `Keyboard.combo(Combo.of(Key.CTRL, Key.S))`** when the project's SDK has the
  `Combo` shape, and the old `combo(Key.CTRL, Key.S)` otherwise. Keypad keys record as `NUMPAD_*` (they were
  `NUM*`, the top row's names), Home/End/Page Up/Page Down/Insert and the keypad operators record as keys, and
  punctuation inside a chord (Ctrl+[) is recorded rather than dropped.

- **One type chooser everywhere.** Parameters, local variables, and a function's inputs and result all pick
  their type from the same searchable list: *Java* first, then *This project* (your own records and classes),
  then one group per plugin, each alphabetical. Types are named as Java names them — `int`, not "Whole
  number" — with a short hint beside each.
- **Wrap in any container, at any depth.** *Wrap in* List, Map… works for every type and wherever a type is
  chosen, so a function can take a `Map<String, List<Point>>`.
- **The toolbar is grouped by owner.** Studio's own buttons come first under a *Studio* caption, then each
  plugin's buttons under that plugin's name.
- **The GitHub account lives in the Versions tab.** Its button is at the end of the tab's top strip; the ⑂ VCS
  and GitHub buttons are gone from the toolbar.
- **How a parameter is picked is chosen beside its type.** *Choices* is a strip — *Any value*, *One of*, *Any
  of* — rather than something the type implies. *One of* starts the set with the value the parameter already
  has; *Any of* makes the field a `List` of its type with that value ticked, and is the only mode that changes
  the type. A true/false parameter offers no *One of*.
- **New parameters are shown to the user by default.** The add bar has a *Show to user* tick, on to begin with.
- **Declare Variable picks any type.** The statement menu's Variables are *Declare Variable* and *Set Variable*.
  Declare Variable asks for a type from the type chooser (wrap in List or Map included) and a name that follows
  the type until you type one. Int/Double/Bool/String Variable and Create List left the menu.
- **Enums are added from the class.** *+ Add Enum* sits beside *+ Add Function*; Define Enum left the statement
  menu.
- **The "+" follows the pointer.** Anywhere over a stack of blocks, one "+" glides to the gap nearest the pointer
  and along it, instead of appearing only on the thin strip between two blocks.
- **Double-click sets a breakpoint on every block that can hold one**, including a click on the block's own
  text or controls; inside a loop it is the statement clicked, not the loop.
- **Pickers look clickable.** A value picker that draws no outline of its own (a point, a picture) is framed
  like a field and lights up on hover.

### Added

- **A plugin's editor can read the other arguments of its call**, as values (`SlotContext.argumentValue`) —
  what lets the Precision picker show the colour it is a tolerance around.
- **Go to Definition on a call.** Right-click any call block: your own function jumps to its block; a
  library's opens read-only in its own window, from the library's sources (downloaded when Maven has them) or,
  when it has none, as an outline of its public signatures. Works on read-only code too.
- **Nested parameter values are editable.** A `List<List<Point>>`, a `Map<String, List<Integer>>` or a record
  holding a list is edited part by part, as deep as it goes, instead of shown read-only.

- **Only differences** in the Versions tab: a changed function shows just the statements that changed, as
  blocks or as Java. Remembered between sessions.

### Fixed

- **"New int variable…" no longer crashes.** It proposed `int` as the variable's name; a primitive now gets
  `number`, `flag` or `decimal`.
- **Parameter choices show the value, not its Java.** A choice of a Point, a colour or any other type is drawn
  with that type's own picker, in the Choices list and in the Value list, and can be edited in place.
- **Changing a parameter's type starts clean.** The old type's choices, and a range that no longer applies,
  are removed, so no greyed-out row of the old value is left behind.

## [1.1.10] — 2026-09-26

No source changes since v1.1.9; re-released for updated upstream pins.

### Added

- **A better Project Files panel.** Files show as a folder tree again, with package chains folded into one
  row (`com.mybot`), plus a **Resources** group for pictures and data files. Icons say what each file is:
  the entry point, parameter files, files a plugin handed the bot, pictures. A file changed since the last
  saved version is coloured (unsaved edits included). A filter field narrows the tree as you type, and a
  collapsible **Structure** section lists the open file's classes, fields and functions — click one to go
  there. Right-click a file for *Show in file manager*, *Copy path* and *Rename…*: renaming a class renames
  every use of it in the bot, after a safety version.
- **Studio's editor state moved to `.botmaker/settings.json`.** It sat in `src/main/resources`, so every jar
  the bot was built into carried your window layout. An existing project's file is moved on open; git never
  records the folder (a new project's `.gitignore` names it, and every checkout excludes it locally).
- **Usages and Debug tabs.** *Navigate ▸ Find Usages* (Alt+F7) lists every place in the bot the selected
  block's function, variable or class is used, grouped by file; click one to go there. When the bot stops
  at a breakpoint, the Debug tab comes forward with the call stack and the selected frame's variables (one
  level of fields, array elements); picking a frame of your code shows its block. Values are read without
  running any of your code.
- **Fixed: a debug run could start before its breakpoints were set.** The first lines of `main` sometimes
  ran straight past a breakpoint; the debugger now lets the bot start only once it is listening.
- **A Navigate menu.** *Go to Line…* (Ctrl+G) jumps to the block on a line of the open file; *Go to File…*
  (Ctrl+Shift+N) opens any source by typing part of its name; *File Structure…* (Ctrl+F12) lists the file's
  classes, fields and functions to jump to; *Go to Declaration* (Ctrl+B) takes the selected block to where
  the function, variable or class it names is declared, in another file if need be; *Quick Documentation*
  (F1) shows what the selected block names — its type, where it is declared and its documentation. Ctrl+Q
  stays *Exit*.
- **The Publish sheet can be widened.** It sits beside the version history in a split; drag the divider,
  and the project remembers where. Publishing no longer asks *Listed* or *Unlisted* — publishing lists the
  bot, and *Save to my copy* is how to keep a release off the gallery. *Your listing* is a short status line
  at the bottom of the sheet.
- **A template needs no `botmaker-template.properties`.** Its package is the one holding `main`; the file is
  still read when present, for an author who wants another answer.
- **A plugin's own Java file appears when the plugin does.** Opening a project, or adding a plugin to it,
  writes each file the plugin keeps its values in (the SDK's `plugins/sdk/Sdk.java` and `Pictures.java`)
  when the project has none — never over a file, never when you already have a class of that name
  elsewhere, never in an installed bot, and never touching `main`. The file list refreshes when a plugin is
  added.
- **A Versions tab** (was VCS; *Project ▸ Versions*). *Save version*, optionally named; a list of every
  version with your saves in bold and Studio's automatic ones folded out of the way; click one to see which
  files it changed and how; right-click to restore the whole project to it or to give it a name. Edits you
  have not run yet now count as unsaved changes, and saving a version includes them.
- **See a version's change as blocks.** Each changed function shows before and after side by side, with the
  blocks that were added, removed or changed outlined; a change inside a loop marks that one block. Changed
  parameters show as *was → now*, pictures as thumbnails. Switch any card to *Java* to read the code.
- **See where your bot lives.** The top of the Versions tab shows *This computer*, *My copy* on GitHub and,
  for a bot you installed, the *Original* — with how many versions your copy is missing and whether a newer
  release is out. One button says where it goes: *Publish…*, or *Save to my copy*, which saves, creates your
  private repository (or your own copy of someone else's bot) the first time, and uploads your versions.
  It replaces *Push*; a project that had a backup repository keeps it as its copy.
- **Installing a bot keeps its author's history.** An installed bot is now a copy of its author's
  repository at that release, so later updates can be merged with your changes. Bots installed before are
  linked to their author's release the first time you open the Versions tab; your files do not change.
- **Updating a bot keeps your changes.** When its author releases a new version, the Versions tab offers
  *Get v1.4*: the release is merged with your changes instead of replacing them. A file you both changed is
  shown side by side — yours and theirs — and you choose which to keep; *Cancel update* leaves everything as
  it was. Browse Bots' *Update* now points you there.
- **Suggest to author…** sends your versions to your copy and asks the author to take them, as a pull
  request — or shows the one already open. It replaces *Propose…* and *Get latest from original*.
- **Publishing is part of the Versions tab.** *Publish…* (and *Project ▸ Publish…*) opens beside your
  versions instead of in its own window, with the same form, card preview, steps and listing. A publish now
  saves the project as a version, tags it and uploads your versions to your copy on GitHub, which becomes
  public with its history; a repository published by an older Studio keeps its history and gains yours.
  Someone else's bot is suggested to its author rather than published.
- **A Dev view in the Versions tab.** *Simple | Dev* at the top right, remembered for you. Dev lists every
  commit with its short SHA and author (right-click to *Copy SHA*), shows changes as Java text with
  *Discard…* for an unsaved file, and has a commit box for a full message. It also has branches — create,
  switch (unsaved work is kept as a version first), merge another in, delete a merged one — remotes with how
  far ahead or behind you are, *Fetch*, *Pull*, *Push* and *Add remote…* (HTTPS only), and *Open in
  terminal*. Your GitHub sign-in is only ever sent to GitHub. Simple now says *On <branch>* when you are not
  on `main`.
- **Put one function back.** *Restore this function* returns it as that version had it — or *Add this
  function back* if you deleted it — as an ordinary edit you can undo. *Restore this file* does the same for
  a whole file.
- **Studio keeps versions for you.** Each time the bot compiles and runs (or debugs), and when an AI tool
  session opens and ends, Studio saves a version of the project — only when something changed. Every version
  in the history now says who made it: *Saved*, *Automatic*, *AI session*, *Safety snapshot*, *Restored*…
- **A real Terminal tab.** Open shells in the project folder (*+ New shell*, one tab each). Full-screen
  programs such as `vim` or `htop` work, the size follows the panel, and Ctrl+Shift+C / Ctrl+Shift+V copy and
  paste. Closing a tab or the project ends its shell.
- **A plugin can ask Studio for its missing file.** When a project has no `Sdk.java`, the Activity Flow
  window's *Create Sdk.java* has Studio write `plugins/sdk/Sdk.java` once, with an empty flow and the whole
  desktop as the capture source. An existing file is never overwritten, and `main` is left for you to edit.
- **An Assistant tab that runs your AI tool inside Studio.** Pick Claude Code, Codex, Gemini CLI or opencode
  and press *Open*: it runs in a terminal in the tab, logged in as you (a Claude subscription works), and
  changes the open file's blocks through Studio's tools. It cannot edit your files directly — each tool is
  started with its file-writing tools turned off, and Codex in its read-only sandbox — so every change is
  compiled first and is one step Undo takes back. Shell commands are off too unless you tick *Allow shell
  commands*. A tool that is not installed is greyed, with the command that installs it.
- **Studio as an MCP server.** Opening a tool in the Assistant tab starts it; *Serve to other MCP clients*
  keeps it on for Cursor or a terminal outside Studio. It listens on `127.0.0.1:7431` only and needs a token;
  *Copy Claude Code setup* copies the `claude mcp add` command with both. Each accepted edit is applied at
  once, as its own undo step.
- **The tool layer an assistant edits blocks through** (`assist/AssistTurn`). A model reads the palette and the file's statements and slots by id, and inserts, sets or deletes
  through the same `CodeEditor` calls the canvas makes. It cannot write Java: an insert names a palette entry,
  and a slot value is read by the value grammar as the slot's type and written back in the host's spelling.
  Each edit is compiled against the project's classpath and dropped if it adds an error; a turn lands as one
  undo step.
- **An insert that would not compile is refused, and you are told why.** A block from the insert menu, a
  pick from a value menu, a dropped palette block or a new variable is compiled before it is written. If it
  adds an error the file did not have, nothing changes and the status line names the error. An error the
  file already had never blocks you.
- **The insert and value menus say what each entry does.** A row shows a dot in its block's colour and a
  one-line description (a tooltip; a second line in search results, which also count their matches). The
  insert menu opens with your last five blocks under *Recent*, then *From plugins*, then *Java*.
- **A call block says whose code it runs**: the plugin's name (*BotMaker SDK*) on a plugin call, *Java* on
  a JDK call, *Library* on another jar's, and *Call* on your own function.
- **New value and block entries.** *Array Item* (`scores[0]`) for each array in scope whose items fit the
  slot; *Yield Value* inside a case block of a switch value; *+ case* and ✕ on a switch value's cases (a new
  case takes a label the switch does not use yet).
- **Define Enum asks for the name and the values first.** OK stays disabled while the name is taken or not
  a valid Java name. *View ▸ Ask for Names When Inserting* turns this off, and the variable window after a
  new variable with it.
- **Types are picked, not typed.** Every type on a block is a chip drawn by its structure: `int[]` is `int`
  with `[ ]`, `Map<String, List<Point>>` is a `Map` chip holding two smaller ones. Click a part to pick it
  from a list: primitives, *This project*, *From plugins*, *Java*, and other libraries one level down. A
  type the lists lack is typed in the search and offered as *Use "…"*. This covers casts, type checks,
  `Foo.class`, `new int[…]`, a `catch`, a `for` counter and the variables in `for`/`try (…)`; a variable's,
  a field's and a function's types are drawn the same way.
- **A catch offers only exceptions**, and *+ or* adds another one (`catch (A | B e)`).
- **View ▸ Block Font.** Blocks are written in Nunito, which Studio now ships, or in your system font, or in
  any font installed on your computer (*More Fonts…*, a searchable list showing each font).
- **Four more block fonts ship with Studio**: Lexend, Atkinson Hyperlegible Next, Fredoka and Space Grotesk,
  each with its own heavier cuts for keywords and connecting words. *More Fonts…* shows them at the top, each
  as a line of block text, and **Import font…** adds a `.ttf` or `.otf` file you have not installed: Studio
  keeps a copy in its settings folder and loads it every time it starts.
- **The Run console and the terminals use JetBrains Mono**, which Studio now ships, so their columns line up
  on every computer.

### Changed

- **The bot's output is the Run tab.** It was called Terminal and was not one. It now has Stop and Clear
  buttons, says whether a run or a debug session is going, and hides the colour codes a logger prints.
- **The Event Log tab is gone.** It listed Studio's internal events, which told a bot author nothing.
- **A type change is compiled first.** A cast, check or catch that would not compile — a checked exception
  the body never throws, `o instanceof List<String>` — is refused with javac's reason, as an inserted block
  is.
- **A new `synchronized` block locks on `this`** in an instance method; a static one still locks on the
  class.
- **Blocks stack touching, like Scratch.** The gap between two blocks is gone; each filled block has a notch
  on top and a tab below that fits the next one. Hover the join between two blocks for a line and a round
  "+" to insert there; drag over it and the same line shows where the block will land. An empty body is a
  "+ Add a block" pill.
- **The value buttons are easy to see.** "+" fills an empty slot and "▾" changes a filled one (both were
  a faint "+"); each is a solid disc in the block's text colour.
- **Keywords are heavier and words are rounder**: Nunito ExtraBold for `while`, `if`, `Print`, SemiBold for
  the words between the values, at a 13px base.
- **A loop, an `if` or a function holds its blocks snugly.** The blocks inside touch the C's arm and its
  header, where they floated 18px in with a gap above and below. An `else if` no longer draws a second foot
  under the chain.
- **Dark and Black blocks are deep colours, not near-black.** Each category keeps its hue at about a third
  of full lightness, white text still clears 5:1 on every one, and the dropdowns and fields on a block sit a
  shade darker than it.

- **A plugin's editor is handed the resolved call and a type it can only ask about by class** (contract
  0.3.0). `plugin/HostTypes` builds the `TypeRef` from the binding (its erasure's binary name and every
  supertype) and the `Executable` from the call's `IMethodBinding`, loaded on the plugin loader by name and
  erased parameter types. `PickerContext` carries the call's binding rather than the scope text and method
  name. A call JDT could not resolve is no call, and a type known only by a bare simple name is unresolved:
  a call-site or type-keyed editor is absent there instead of matching a spelling.

- **A value is written as a JDT tree, never as text.** The grammar builds the expression node by node
  (`plugin/grammar/ValueWriter`) and answers a `JavaValue` — the node plus the classes it names by simple name
  — which replaces `ValueGrammar.Written`. Every sink copies that tree into its own file's tree: a Parameters
  row (`JavaParameterEdits`), a `@Managed` method's return (`JavaManagedEdits`), a canvas slot
  (`CodeEditor.replaceWithValue`), a varargs run (`setTrailingArguments`) and a recorded call
  (`CodeEditor.insertStatement`). `createStringPlaceholder` is gone from all of them, and a new parameter's
  field is a `FieldDeclaration` node laid out by the formatter. `HostValueContext` holds the value an editor set
  and its tree instead of a source string; a list, map or record cell composes its parts as trees
  (`ValueGrammar.compose`, `BotRecords.compose`), and a part nothing edited is written back as its own
  expression. A `@Managed` constant reference is matched through the file's imports (`SourceNames`), not by
  its spelling. Visible differences: a retyped or added field names `List`/`Map` by simple name with the
  import, and a field Studio adds imports `@Param` when the file did not.

- **A value's type is a `java.lang.reflect.Type`, and `ValueForm` is deleted.** A leaf is the plugin's own
  `Class`, a list or map is a `ParameterizedType` (`ValueTypes.Parameterized`), a bot's record is
  `ValueTypes.BotClass`, and anything else is `ValueTypes.Unknown`, shown and never written. A field's written
  type is resolved once, by `project/source/ValueTypeResolver`: from the binding when the classpath resolves,
  otherwise through the file's imports the way javac does. A `Duration` imported from another package is no
  longer read as `java.time.Duration`, and a nested record the bot declares is found by its canonical name.
  `ValueGrammar.qualify`, `type(String)` and `containerForJava` are gone; `JdkLiterals` is keyed by `Class`;
  a plugin factory's receiver and a `new` expression's class are matched the same way (`Factory.names`).

- **`@Param` and `@Managed` are identified by class, not by a name ending in `Param`.**
  `project/source/BotAnnotation` answers from the JDT binding when the unit was parsed against the project's
  classpath (`project/source/BotParser`), and otherwise resolves the written name through the unit's imports
  the way javac does (`SourceNames`). An annotation named `Param` from any other package — or a bare one
  nothing imports — is no longer read as a parameter. The pre-2.0 `botmaker-plugin-basics` annotations are
  still accepted by their exact names. With bindings, `@Param(category = SOME_CONSTANT)` reads the constant's
  value instead of dropping it. Replacing an annotation keeps the author's spelling of its name.

### Fixed

- **Follow no longer freezes the window.** A bot printing in a tight loop under Follow or Debug sent the
  Run tab one update per line, which froze Studio until long after Stop. Its output now reaches the tab in
  batches, like a run's, and a flood keeps the newest lines and says how many it skipped.
- **Follow is calmer and keeps up.** The highlight moves at most four times a second; when the bot loops
  faster than that, it rests on the loop instead of flickering through its body. The canvas scrolls to the
  running block, gently, only once it leaves the middle of the view — and opens the file the bot is in.
- **Breakpoints work in every file**, not only in the bot's main class, and a stop in another file opens it.
- **The running block and breakpoints are easier to see.** The block the bot is on has a thicker ring and a
  soft glow; a breakpoint is a wider red bar with a dot on the block's first line, drawn in the theme's colours.
  Paused on a breakpoint shows both. A method header, enum, initializer or comment no longer takes a
  breakpoint — it could never be hit — and the old red circle is gone.
- **Switching a call's method now switches everything with it.** Changing `Wait.seconds` to `milliseconds`,
  `time` and back sometimes kept the old method's value editor, and an overload picked from the overlay
  palette kept the old method's name. Both came from the editor keeping a block that should have been redrawn.
- **Call Function lists your functions.** It is a submenu of the functions you can call where the block goes
  (each overload its own row), and search finds them by name; it used to insert whichever came first.
- **The "+" between blocks follows your pointer again**, and steps aside rather than covering a block's own
  buttons.
- **A changed parameter or constant shows as blocks in Versions**, Was beside Now, instead of two lines of
  Java — a list turned into a map reads as the two editors you saw.
- **A text parameter's Choices take what you type.** In the Parameters window, typing a choice and pressing
  Enter added nothing, and the text stayed in the field to be joined onto the next choice (`slow` then
  `turbo` became one choice, `slowturbo`). Enter now adds the choice and clears the field, for a text
  parameter and for a list of text.
- **The "+" at the bottom of a function that ends in `return` works.** Every activity body and every function
  that gives back a value ends with a `return`, and a block added after it is code that can never run, so
  Studio refused it and said so only in the status bar. That "+" (and a block dropped there) now lands just
  above the `return`, which stays last. The same applies after `throw`, `break`, `continue` and `yield`.
- **A call is either "call" (your own function) or "use" (someone else's), and both look like blocks.**
  - A call to one of your bot's own methods reads *call ‹method›*. It is now painted in the Functions colour;
    before, it had no fill and its white text was hard to read on the canvas.
  - A call into a plugin, Java or a library reads *use*, then a small pill naming the owner (the plugin's
    name, *Java* or *Library*), then the class and method. A plugin's call is no longer a pale framed box of
    its own.
  - The class dropdown on a *use* block lists each plugin's classes under the plugin's name, then Java's and
    the libraries' classes. Picking a class rewrites the call onto it right away, so moving a call from a Java
    class to a plugin's class makes it that plugin's call. A *call* block lists your own classes only.
  - A call with a body (such as a *while found* call) is a Functions block with a mouth, like a loop.
- **Stacked blocks join cleanly, delete is easy to hit, and blocks read without punctuation.**
  - A block inside a function, loop or branch has square corners. Its rounded corners let small white
    wedges show where it met the block above, the block below or the frame around it.
  - The delete button is a round 24px button that is always visible, with a gap from the block's edge. It
    was a faint 18px "X" pressed against the edge.
  - The "+" that inserts between two blocks now appears at the left, beside the joint. It used to follow
    the pointer, and at the right end it covered the delete button.
  - Calls and other blocks no longer show `(`, `,`, `)`, `.`, `::` or `:`. Each argument is its own pill.
    Some symbols became words: `!` reads *not*, `super.name` reads *super name*, and case labels are joined
    with *or*.
- **The insert menu is organised by plugin, and you can pin entries.**
  - Each plugin now gets one submenu, named after the plugin, with its classes inside. A plugin with a
    single class lists its calls directly, as `Class.method`.
  - Right-click any entry to pin it. Pinned entries show first, under PINNED, in every project, and
    survive a restart. Right-click again to unpin.
  - The *Declare Bot Variable* submenu is gone. A variable's type picker already offers those types.
  - A category with a single entry (Print, Comment) is now that entry itself, not a submenu.
- **A nested type no longer breaks the build through its import.** Picking a type such as `LaunchTarget.Epic`
  wrote `import com.botmaker.sdk.api.launch.LaunchTarget$Epic;`, which javac refuses. Imports are now
  written with the canonical name (`LaunchTarget.Epic`), whichever path adds them.
- **Imports the file no longer uses are removed.** Every edit on the canvas, deletes included, takes out
  the single-type imports nothing in the file uses any more. Static and `*` imports are left alone. This
  runs only once the project's classpath is resolved, because until then every import looks unused.
- **A second insert menu opens.** With a menu open, clicking another "+" used to close the first menu and
  open nothing. Now the second menu opens.
- **Menu entries that led nowhere are gone.** *Declare Function* is no longer offered inside a body (it
  inserted nothing). *Parameters*, *Enums*, *Variables* and *Call Function* are left out when they would
  open empty, and *Sub-List* is offered only inside another list. In `main`, a call block's method list no
  longer offers this class's instance methods; in instance code it now offers them.
- **Blocks you insert compile in more places.**
  - *Break* and *Continue* are no longer offered inside a switch value's case.
  - *Assign* and *Switch* in `main` no longer name an instance field; value menus in static code no longer
    offer one. A block inserted after a variable can use it.
  - A value for a `Class<?>` slot no longer crashes the menu, and a JDK type with no public constructor is
    never written as `new T()`.
  - *Call Function* in `main` no longer writes `clone();`. A static method is offered only static methods,
    and `Object`'s own methods are never offered.
  - A value for a type with no public constructor is one of its constants (`Key.ENTER`) instead of
    `new Key()`, or `null` when it has none.
  - A second *Define Enum* is named `MyEnum2`.
  - A `throw` offers exceptions only. Changing the class resets the arguments, so `new ArrayList("")` is
    gone.
  - A loop's variable, and a `try` resource, are no longer offered after the loop or the `try`.
- **Only one block menu is open at a time.** Opening a menu closes the one that was open.
- **The zoom control is always there.** Bottom right of the canvas, "− 100% +": the percentage resets.
- **Manage Plugins no longer offers *Install* on a plugin another plugin already brings.** For example,
  basics under the SDK now reads *Included*, and its tooltip explains why.
- **A blank project's first parameter compiles.** Adding a parameter wrote `@Param` into a project whose pom
  named no plugin, so nothing brought `botmaker-studio-api` and the build failed with `package
  com.botmaker.plugin.api.params does not exist`. Studio now declares the contract (the tag it was built
  against) when the project's classpath lacks it — never beside an SDK that already brings it — and a
  project already in that state is repaired when it is opened.

- **A plugin pinned as `${property}` reads and upgrades through the property.**
  `MavenService.readDependencyVersion` answers the property's value, and *Project ▸ Upgrade…* moves the
  property rather than overwriting the placeholder. The gamebot template pins its SDK as
  `${botmaker.sdk.version}`, and a project copied from it would otherwise resolve no SDK jar.

- **A zoomed-in canvas wraps instead of scrolling sideways.** Rows holding a comparison or a sum refused to be
  narrower than their one-line width; they wrap now, and at 200% a 700px window still fits the program.

- **Every word on a block clears WCAG AA in all four themes**, measured on the rendered canvas: the TRUE/FALSE
  chips (white on green was 2.1:1), a method's red × (1.5:1 on purple), a variable's name (black on every
  dark theme), "+ Add Function" on High Contrast's yellow, and a locked file, whose statements faded again at
  every level of nesting (a word four deep was drawn at 42% opacity).

- **The debugger's highlight and a compile error are rings round the block**, so they show on every colour —
  an error on a red block used to be invisible.

- **`new X(…)` reads on every block.** It kept a light teal card of its own, so its words were white on
  near-white (1.1:1) on every filled block, and its class name #333 on the dark themes' locked grey. An
  expression with no block yet (`i++` as a value) was muted grey on the block's colour (1.5:1 on the loop
  green). Both now take the block's own colours.

- **`a + b + c` shows `c`.** A sum or a chain of `&&` with three or more operands drew the first two; the
  rest were in the file and missing from the block.

- **A value dropped inside an operator keeps its meaning.** Picking *Addition* for `n` in `n * 2` wrote
  `0 + 0 * 2`, which Java reads as `0 + (0 * 2)`; it writes `(0 + 0) * 2` now. The same holds for a value
  typed as Java.

- **`new int[n]` keeps its size.** It was drawn as an empty list and lost `n` on the next edit.

- **`flags |= MASK` no longer shows "set to".** An operator missing from the selector showed its first entry,
  and picking anything wrote over the author's operator.

### Added

- **Every statement is on the canvas.** A `try` / `catch` / `finally` is a block with a body per clause,
  `+ catch` and `+ finally`, a catch type you type (`IOException | InterruptedException` works) and a name
  that renames only inside its clause. So are the counting loop (`for (int i = 0; i < 10; i++)`, drawn as
  *for int i from 0 while … each time …*), `throw`, `synchronized`, `assert`, a labelled loop (and the label
  on `break outer`), `this(…)` / `super(…)` in a constructor, and any other expression run for its effect
  (`new Worker();`). What still has no block of its own — a class declared inside a method, `int a = 1, b;` —
  is shown as the Java it is, with ✎ to edit it as text; text that is not Java is refused, not written.
  These statements used to be missing from the canvas while they stayed in the file.
- **Insert menu: Counting Loop, Try / Catch, Synchronized, Throw Error, Assert.** Each is seeded to compile
  where it is dropped (a fresh loop index, `catch (Exception e)`, the lock on the enclosing class).
- **An `if` or loop whose body is one statement without braces gets braces when the file is opened**, the
  way an arrow `case` already did, so its body has somewhere to drop a block. A file Studio does not rewrite
  (locked, read-only) shows such a statement as its Java instead.
- **Every value has a block.** `c ? a : b` reads *if … then … else …*; `(int) x` reads *x as int*; `o
  instanceof Point p` reads *o is a Point called p*, and renaming `p` renames it where it is in scope; a lambda
  is its parameters and either a value slot or a body to drop into; `a[i]` reads *item i of a*; `new int[n]`
  reads *new list of int size n*; `-x`, `~x`, `++i` and `i++` show their operator where Java writes it; a
  switch that is a value (`case A -> 1;`) is a block with a row per case. `this`, `super.x`, `super.m(…)`,
  `X.class`, a character, a text block and a number written as `0xFF` or `1_000` each have one too, and a
  type in a cast, a check, a class literal or a new array is typed in. An assignment used as a value and a
  declaration inside `for (…)` or `try (…)` are blocks as well. An anonymous class and a `case X:` switch
  value are shown as their Java, with ✎.
- **Expression menu: If … Then … Else, Negative, Convert Type (cast), Is A (instanceof), Empty List of Size,
  Character, Class, Code Value (lambda).** Each is offered only where it fits — a lambda where a functional
  interface is expected, with one parameter per parameter of its method.
- **All twelve assignment operators** in the assignment block's selector: `%=`, `&=`, `|=`, `^=`, `<<=`,
  `>>=` and `>>>=` joined `=`, `+=`, `-=`, `*=` and `/=`.

- **Blocks look like blocks.** Every block is drawn in its category's colour with a darker lip under it; a
  loop, an `if` or a `switch` wraps its body like a C, so what is inside it is plainly inside; a method is a
  rounded "hat" its lines sit in; values are round and conditions square-ended, so a condition slot and what
  fills it can be told apart; an `if` / `else if` / `else` chain reads as one block. Typed-in values and
  dropdowns are as wide as what they show (a `0` is no longer a 150px box), keywords are bold and full size,
  and the `+` / `x` buttons are quiet until pointed at. **View ▸ Block Style** switches to *Outlined* — a
  plain card with the category as a bar down its edge — in every theme, and remembers the choice.
- **The block style is documented** in `docs/refactor/37-block-styling.md` (umbrella): the classes, the
  tokens per theme, each shape's anatomy, the states, and the recipe for a new block, which needs no CSS.

- **Zoom the block canvas.** View ▸ Zoom In / Zoom Out / Reset Zoom (Ctrl+=, Ctrl+-, Ctrl+0), Ctrl+wheel and
  a trackpad pinch, from 50% to 200%. Blocks re-wrap to the width they are drawn in rather than being
  stretched, the line under the pointer stays put, and a chip in the corner shows the zoom and resets it.
  Remembered across projects and restarts.

- **⏺ Record is back on the overlay HUD, and it inserts at the cursor.** Press Record, act in the game, press
  Stop: each click, double click, right or middle click, drag, scroll, typed text, key, key combination and
  pause becomes one statement at the cursor, each its own undo step, with its imports. Which call a gesture
  becomes is the plugins' to say; with the SDK, a click on one of your pictures is written
  `ImageClicker.click(Pictures.X)`, a click elsewhere `Mouse.click(…, x, y)`, and a pause that ends on a
  picture `ImageWaiter.waitFor(Pictures.X, …)`. Linux (X11) only, as before.
- **You choose which plugin records a gesture two plugins can write.** Right-click ⏺ Record: *Record with*
  lists each such gesture with *Automatic (highest rank)* and one choice per plugin, saved per project. The
  chosen plugin is tried first; a gesture it cannot write still falls to the next one.

### Changed

- **A plugin's factory is read as a constructor or method, not a name.** `plugin/grammar/Factory` is the
  host's view of the contract's `ComponentType.factory()` — constructor, static method, or an instance
  method on part 0 — and the grammar asks it how a call is spelled. The host's `List.of`, `Map.ofEntries`
  and `Map.entry` are real `Method`s too.
- **A value is read off the parsed Java, not its text, and hand-written chains are editable.** The grammar
  walks the JDT expression (`plugin/grammar/ExpressionReader`), so `Precision.TIGHT.minArea(400)` and
  `CaptureSource.window("G").region(new Rect(…))` draw as editable pills rather than as text, and a
  `public static final` constant of a class a plugin declares (`Precision.TIGHT`, `Color.RED`) reads as its
  value. An edited chain is written in the one-call form the plugin declares. A part nothing reads is shown
  exactly as you typed it. `SourceSplit`, the depth-zero comma splitter, is gone.
- **The HUD's activity picker lists the methods your flow runs.** It lists `Collect::body` for each method
  reference in a plugin value (the SDK's flow), opens the file declaring the class and scopes the tree to the
  method. It searched for `Activities.define("…")`, which SDK 2.0 deleted, and so found nothing. A reference
  to a class your project does not declare says so on the status line. The canvas's *Activity name* submenu
  on `Activity.enable("…")` is gone with the `Activity` class it keyed on; the SDK's own editor for
  `Activities.enable`/`disable`/`active`/`setEnabled` offers the names.
- **A plugin value named by one of your constants reads as that value, and a picked value is written as
  the constant.** A picture slot holding `Pictures.ORE` shows the ore picture, a run of pictures shows each
  one, and choosing a picture your `Pictures` class already holds writes `Pictures.X` rather than its path.
  The Parameters window does the same for a `@Param` field holding `Pictures.ORE`.
- **A capture source is a value everywhere.** `CaptureSource.window("Game")`, a monitor, an emulator, a
  region and `Source.current()` read and write through the SDK's declarations, and a recorded click writes
  `Mouse.click(Source.current(), x, y)` with the import rather than the fully qualified name. A narrowed
  source is written `CaptureSource.region(source, new Rect(…))`.
- **The duration editor has no *Random range* toggle** — it rewrote the call around a slot as text, which
  no plugin can do any more. Write `Wait.between` from the palette.
- **The palette comes from `@Palette` on a plugin's classes.** Studio finds every annotated class in each
  plugin's jar when the plugin builds no catalog of its own, so the SDK's palette now also offers
  `Activities` and `Flows`, which its old hand-written list had missed.

- **A colour, a duration or any other plugin value opened in the Parameters window and closed without an
  edit comes back byte-identical.** Studio used to read a value as text, run it through a codec it could
  not check and write it back — a `java.awt.Color` came out as `new java.awt.Color(255, 255, 255)`. Studio
  now reads every value as the value it is, through each plugin's own declaration of its type, and writes
  it back only when an editor changes it.
- **Every value editor is a plugin's.** The check box, spinners, date and time pickers, direction pad, mouse
  diagram and key list Studio drew itself now come from the plugin that declares the type (plugin-basics for
  the JDK's, the SDK for its own), so the Parameters window, the Runner and a block draw the same editor.
  One thing is lost: a declared `@Param(min, max)` no longer narrows a number's editor; it is still read,
  shown and kept.
- **A value written into your own file uses simple names and adds the import**, where it used to write
  `com.botmaker.sdk.api.geometry.Point` in full.
- **Retyping a parameter to a type with no starting value** (one of your own records) declares it with no
  initialiser rather than refusing.
- `@Param` bounds are written as numbers (`min = 1`); text bounds from older bots (`min = "1"`) still read.
- **Manage Plugins no longer reads an entry's `valueTypeIds`.** Nothing did anything with it, and the
  registry stopped asking for it. Entries that still carry the field read as before.

### Removed

- **Studio no longer writes a plugin's file into your project.** `PluginSourceFiles` ran on every plugin
  bind — opening a project, and again after any pom edit — copying in whatever Java each loaded plugin
  shipped. It is deleted with the contract method behind it.
  Reading those files is unchanged: the flow editor and 🖼 Manage Pictures still find `@Managed` methods in
  your own source and still rewrite one returned expression at a time, and the explorer still groups
  `plugins/<name>/` under *Generated by BotMaker*. What is gone is the writing.
  **A project made from a template already has the file.** A blank project that installs the SDK now gets
  no `plugins/sdk/Sdk.java` — the flow window offering to create one is owed and not in this release.

- **The Parameters window has one kind of section, and it is a class of your bot.** Studio no longer asks a
  plugin for parameter sections, rows or edits: `PluginHost.parameterGroups`/`parameterGroup`/`parameterRows`/
  `parameterEdited`, `HostParameters`' group half and `project/params/ParameterSurface` are all gone, and
  what is left is `JavaParameters`, which reads and writes `@Param` fields off your own source.
  Nothing a plugin ever declared is lost, because no plugin ever declared one — the surface read a
  pre-2026-09-17 project's JSON and answered empty for every project made since. **A plugin that wants a row
  of its own puts a `@Param` field in the file it ships**, and the window finds it like any other: a field in
  `plugins/sdk/Sdk.java` is listed under `Sdk.java`, editable, with the same value cell.
  Visible differences, all of them the sections: a heading reads `Parameters.java` rather than a plugin's
  title, a plugin with no parameters no longer draws an empty section, and the rail's categories are the
  distinct `@Param(category = …)` strings your bot actually uses.

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.9] — 2026-09-23

### Added

- **⏺ Record is back on the overlay HUD, and it inserts at the cursor.** Press Record, act in the game, press
  Stop: each click, double click, right or middle click, drag, scroll, typed text, key, key combination and
  pause becomes one statement at the cursor, each its own undo step, with its imports. Which call a gesture
  becomes is the plugins' to say; with the SDK, a click on one of your pictures is written
  `ImageClicker.click(Pictures.X)`, a click elsewhere `Mouse.click(…, x, y)`, and a pause that ends on a
  picture `ImageWaiter.waitFor(Pictures.X, …)`. Linux (X11) only, as before.
- **You choose which plugin records a gesture two plugins can write.** Right-click ⏺ Record: *Record with*
  lists each such gesture with *Automatic (highest rank)* and one choice per plugin, saved per project. The
  chosen plugin is tried first; a gesture it cannot write still falls to the next one.

### Changed

- **A plugin's factory is read as a constructor or method, not a name.** `plugin/grammar/Factory` is the
  host's view of the contract's `ComponentType.factory()` — constructor, static method, or an instance
  method on part 0 — and the grammar asks it how a call is spelled. The host's `List.of`, `Map.ofEntries`
  and `Map.entry` are real `Method`s too.
- **A value is read off the parsed Java, not its text, and hand-written chains are editable.** The grammar
  walks the JDT expression (`plugin/grammar/ExpressionReader`), so `Precision.TIGHT.minArea(400)` and
  `CaptureSource.window("G").region(new Rect(…))` draw as editable pills rather than as text, and a
  `public static final` constant of a class a plugin declares (`Precision.TIGHT`, `Color.RED`) reads as its
  value. An edited chain is written in the one-call form the plugin declares. A part nothing reads is shown
  exactly as you typed it. `SourceSplit`, the depth-zero comma splitter, is gone.
- **The HUD's activity picker lists the methods your flow runs.** It lists `Collect::body` for each method
  reference in a plugin value (the SDK's flow), opens the file declaring the class and scopes the tree to the
  method. It searched for `Activities.define("…")`, which SDK 2.0 deleted, and so found nothing. A reference
  to a class your project does not declare says so on the status line. The canvas's *Activity name* submenu
  on `Activity.enable("…")` is gone with the `Activity` class it keyed on; the SDK's own editor for
  `Activities.enable`/`disable`/`active`/`setEnabled` offers the names.
- **A plugin value named by one of your constants reads as that value, and a picked value is written as
  the constant.** A picture slot holding `Pictures.ORE` shows the ore picture, a run of pictures shows each
  one, and choosing a picture your `Pictures` class already holds writes `Pictures.X` rather than its path.
  The Parameters window does the same for a `@Param` field holding `Pictures.ORE`.
- **A capture source is a value everywhere.** `CaptureSource.window("Game")`, a monitor, an emulator, a
  region and `Source.current()` read and write through the SDK's declarations, and a recorded click writes
  `Mouse.click(Source.current(), x, y)` with the import rather than the fully qualified name. A narrowed
  source is written `CaptureSource.region(source, new Rect(…))`.
- **The duration editor has no *Random range* toggle** — it rewrote the call around a slot as text, which
  no plugin can do any more. Write `Wait.between` from the palette.
- **The palette comes from `@Palette` on a plugin's classes.** Studio finds every annotated class in each
  plugin's jar when the plugin builds no catalog of its own, so the SDK's palette now also offers
  `Activities` and `Flows`, which its old hand-written list had missed.

- **A colour, a duration or any other plugin value opened in the Parameters window and closed without an
  edit comes back byte-identical.** Studio used to read a value as text, run it through a codec it could
  not check and write it back — a `java.awt.Color` came out as `new java.awt.Color(255, 255, 255)`. Studio
  now reads every value as the value it is, through each plugin's own declaration of its type, and writes
  it back only when an editor changes it.
- **Every value editor is a plugin's.** The check box, spinners, date and time pickers, direction pad, mouse
  diagram and key list Studio drew itself now come from the plugin that declares the type (plugin-basics for
  the JDK's, the SDK for its own), so the Parameters window, the Runner and a block draw the same editor.
  One thing is lost: a declared `@Param(min, max)` no longer narrows a number's editor; it is still read,
  shown and kept.
- **A value written into your own file uses simple names and adds the import**, where it used to write
  `com.botmaker.sdk.api.geometry.Point` in full.
- **Retyping a parameter to a type with no starting value** (one of your own records) declares it with no
  initialiser rather than refusing.
- `@Param` bounds are written as numbers (`min = 1`); text bounds from older bots (`min = "1"`) still read.
- **Manage Plugins no longer reads an entry's `valueTypeIds`.** Nothing did anything with it, and the
  registry stopped asking for it. Entries that still carry the field read as before.

### Removed

- **Studio no longer writes a plugin's file into your project.** `PluginSourceFiles` ran on every plugin
  bind — opening a project, and again after any pom edit — copying in whatever Java each loaded plugin
  shipped. It is deleted with the contract method behind it.
  Reading those files is unchanged: the flow editor and 🖼 Manage Pictures still find `@Managed` methods in
  your own source and still rewrite one returned expression at a time, and the explorer still groups
  `plugins/<name>/` under *Generated by BotMaker*. What is gone is the writing.
  **A project made from a template already has the file.** A blank project that installs the SDK now gets
  no `plugins/sdk/Sdk.java` — the flow window offering to create one is owed and not in this release.

- **The Parameters window has one kind of section, and it is a class of your bot.** Studio no longer asks a
  plugin for parameter sections, rows or edits: `PluginHost.parameterGroups`/`parameterGroup`/`parameterRows`/
  `parameterEdited`, `HostParameters`' group half and `project/params/ParameterSurface` are all gone, and
  what is left is `JavaParameters`, which reads and writes `@Param` fields off your own source.
  Nothing a plugin ever declared is lost, because no plugin ever declared one — the surface read a
  pre-2026-09-17 project's JSON and answered empty for every project made since. **A plugin that wants a row
  of its own puts a `@Param` field in the file it ships**, and the window finds it like any other: a field in
  `plugins/sdk/Sdk.java` is listed under `Sdk.java`, editable, with the same value cell.
  Visible differences, all of them the sections: a heading reads `Parameters.java` rather than a plugin's
  title, a plugin with no parameters no longer draws an empty section, and the rail's categories are the
  distinct `@Param(category = …)` strings your bot actually uses.

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.8] — 2026-09-21

### Changed

- **Rebuilt against the plugin contract's new package layout.** Studio's plugin host imports
  `com.botmaker.plugin.api.slot`, `.parameters`, `.toolbar` and `.source` now. Imports only — nothing in the
  canvas, the Parameters window or the plugin loader behaves differently. A plugin built against
  `botmaker-studio-api` v0.1.5 or earlier will not load in this Studio; rebuild it against v0.1.6.

- **The walkthrough describes the bot you actually get.** Getting Started and `WORKFLOW.md` still said a
  variable's value lived in `activities.json` and was read at startup by a generated `Activities.java`, that
  Studio maintained one source file per activity plus a registry, and that a run was driven by a generated
  `FlowDriver`. None of that has been true since a plugin's values became Java the plugin ships: a variable
  is a `@Param` field in your own `Parameters.java`, an activity is a `public static Outcome
  body(ActivityContext ctx)` the flow names as `Collect::body`, and the flow is a value in your own
  `plugins/sdk/Sdk.java`. The steps, the order and the runtime diagram's shape are unchanged — only the
  sentences that described the old machinery.

## [1.1.7] — 2026-09-21

### Added

- **A value can be a record with fixed parts, not just a list or a map.** A plugin's `Flow` reads as the
  shape it is — activities, edges, start, limits — instead of as an unknown type shown read-only, which is
  what any type without angle brackets did before.

- **A plugin can give your bot a file, and it lands once.** A plugin that ships one gets it written to
  `src/main/java/<your package>/plugins/<plugin>/`, with the package filled in, the first time Studio sees
  the plugin on your project's classpath — installing it, reloading plugins, or just opening the project.
  It compiles and runs as it lands, before you have drawn anything. **It is never written over**: edit it,
  add helpers and comments to it, delete it if you like. Nothing regenerates it, and a re-install brings
  back only a file that is actually missing. One Project History entry per file written.

- **A plugin's own values are read and written in your bot's Java.** A `@Managed("id")` method in a file the
  plugin gave your project is found by its id, its type read off the declared return type, and its value
  edited through the same control a block's slot and a Parameters row use. Studio rewrites the one
  expression that method returns and nothing else: your comments, your helper methods and your formatting
  survive a save, and the `git diff` after changing a plugin's value is one hunk. A body you have edited by
  hand into something other than a single `return` is shown read-only with the reason, and never
  overwritten. Nothing uses this yet — the SDK's flow moves onto it next — and no existing project changes.

### Changed

- **Nothing in your project is "Generated by BotMaker" any more.** That group in the file tree, and the
  read-only 🔒 badge that went with it, existed for one day for a design that was withdrawn the same day.
  Studio writes no file it then owns: a plugin's file is yours from the moment it lands, and it is listed
  under *My code* with the rest of your bot. What is still refused on the canvas is one `@Managed` method's
  body — one expression, not a file.

- **What a plugin locks on the canvas is an annotation you can see.** A plugin used to name a *type*, and
  Studio locked every `static final` field of it, plus any class holding nothing but those. That guessed:
  one picture constant beside ordinary code locked the whole file. A `@Managed("id")` method or class is
  refused, and nothing else is.

- **A plugin's editor is handed the Java a value is written as.** A row of the Parameters window used to
  hand a plugin a stored string and a slot on a block used to hand it an expression, so a plugin drawing
  both had two spellings to keep in step and could only ever be offered one leaf at a time. There is one
  spelling now and a whole type tree with it, which is what lets a plugin claim a value the host has no
  control for. Visible where the SDK's own editors used to differ: a duration in the Parameters window
  commits on OK rather than as you type, and a colour the editor cannot write back leaves the swatch alone
  instead of showing white.

- **A parameter's type picker wraps instead of picking a shape.** *Shape ▸* offered four fixed choices; the
  menu is now **Wrap in ▸** — every container the installed plugins registered, `List` and `Map` included —
  plus **Unwrap**, and the button shows the Java you will get. Two containers deep is as far as it goes,
  which is a limit on the picker and not on your bot: a deeper type written by hand is still listed, shown
  and left alone.
- **A `Map` parameter is edited as a map**, in two columns with an Add row, and a key another row already
  has is marked where you typed it rather than silently dropped. A list is still rows of its own editor, a
  value with a set of choices declared is still ticks or radio buttons — and whether those appear now
  follows the choices you wrote down rather than a shape chosen beforehand, so a parameter never changes
  control because of something you cannot see.
- **A value your installed plugins cannot read is shown exactly as you wrote it**, with the reason, and
  nothing this window does will rewrite it.
- **A parameter's type badge in the Runner names the Java you wrote**, `java.util.List<Duration>` where it
  said "List of Duration". A type is a tree now and there is no prose short enough to say one truthfully; the
  Java is what is in your file, and it is what the type picker's button has said since this release.

### Added

- **A parameter can be one of your bot's own records.** `@Param public static Point origin = new Point(1, 2);`
  is listed, typed and edited component by component, and what is written back is
  `new com.example.bot.Point(…)`. Records only: an ordinary class with several constructors is shown as
  written, because which one to call would be a guess. If a component is of a type no installed plugin
  knows, the window says which component — and it never puts a placeholder into a class of yours.

- **One upgrade door, not three.** *Project ▸ Upgrade SDK…* and *Project ▸ Modernise…* are gone. Both were
  *Project ▸ Upgrade…* with one row chosen for you, and three entries made one report read as three
  operations. The SDK is a row in the upgrade table like any other plugin.
- **The upgrade window says what it is doing.** Picking a version runs that row's check by itself; every row
  carries its own state — *checking…*, *nothing breaks*, *N repairable*, *blocked* — instead of sharing one
  spinner; the status line keeps the outcome of the check instead of clearing it; and when Apply is greyed
  out, a line below it says why and what to do next.
- **The window stays open when an upgrade succeeds**, with what the pass did: the versions that moved, how
  many calls were repaired in how many files, and a button to the Review tab when your own code was
  rewritten. A removal says the same. Previously the window closed on success and the message went with it.

### Fixed

- **A plugin you install is there at once.** Adding the SDK — or any plugin — to an open project used to
  leave the statement menus empty until the project was reopened, and *Reload Plugins* did not help. The
  types a newly bound plugin brings are offered as soon as it binds.
- **One plugin that will not compose no longer costs you every other one.** Two plugins registering the same
  value type id (an SDK older than 1.1.7 beside `plugin-basics`, which is where the nine JDK types live now)
  used to silently drop *all* plugin editors — no pictures, no durations, no colours — with nothing on
  screen to say so. The plugin that clashes is left out on its own, and Manage Plugins names it, the ids and
  who already claims them.
- **Manage Plugins refuses to install a plugin another plugin already brings**, and says to change that
  plugin's version instead. Declaring it twice let Maven pin a version it was never built against.

- **One number stopped blanking a whole file.** `Duration.ofMillis(60000L)` — or any `long`, hex, binary,
  octal or `1_000` literal — was read as an `int`, threw, and left the canvas empty with no message. Numbers
  are read by Java's own rules now. A spelling a number field cannot write back (hex, `1_000`, an exponent)
  is shown exactly as you wrote it instead of being turned into a decimal.
- **A member that cannot be drawn costs that member, not the file.** The rest of the file draws, and the
  status line names what was left out.
- **A field's value gets its real editor.** `static final ImageTemplate COLLECT = new ImageTemplate(…)` shows
  the picture editor, and a `Duration` field its duration editor, where both used to be a generic blue
  *Create …* block. Class fields now ask the same editors a local variable and a call argument do.

### Changed

- **The download page looks like the rest of the project, and its commands copy.** The stylesheet and the
  copy button come from `botmakerdev.github.io/assets/`, the organization's front page, rather than from a
  `<style>` block repeated in four repositories, and the page links to that front page — where one command
  installs every BotMaker tool at once. The installers, the repository and the signing key are untouched.
- **Parameters and plugin-managed constants are shown read-only.** Everything in `Parameters.java`, every
  `@Param` field wherever it lives, and every constant a plugin manages (the SDK's pictures) draws with its
  blocks and its picker — as a preview — and refuses edits, naming the window that owns it. A picker that
  opened a chooser and then had its write refused is gone.

- **An edit on the canvas changes only what it edits.** Every edit used to re-lay-out the whole file, so
  adding one statement could rewrite `private Collect() {}` three methods away. Now only the lines the edit
  wrote are laid out, and everything else stays exactly as you wrote it.
- **What Studio writes is indented with four spaces**, like the rest of the file, not with a tab.
- **A public parameter is written `visibility = Param.PUBLIC`**, the annotation's own constant, not the
  string `"public"`, so a field the Parameters window added reads like the ones beside it.

### Changed

- **The title bar and the Parameters window name the project's directory** (`~/IdeaProjects/gamebot`), not
  only its name. Two copies of one template are common, and an edit that seems lost is usually in the other
  copy.

## [1.1.6] — 2026-09-19

### Fixed

- **One number stopped blanking a whole file.** `Duration.ofMillis(60000L)` — or any `long`, hex, binary,
  octal or `1_000` literal — was read as an `int`, threw, and left the canvas empty with no message. Numbers
  are read by Java's own rules now. A spelling a number field cannot write back (hex, `1_000`, an exponent)
  is shown exactly as you wrote it instead of being turned into a decimal.
- **A member that cannot be drawn costs that member, not the file.** The rest of the file draws, and the
  status line names what was left out.
- **A field's value gets its real editor.** `static final ImageTemplate COLLECT = new ImageTemplate(…)` shows
  the picture editor, and a `Duration` field its duration editor, where both used to be a generic blue
  *Create …* block. Class fields now ask the same editors a local variable and a call argument do.

### Changed

- **The download page looks like the rest of the project, and its commands copy.** The stylesheet and the
  copy button come from `botmakerdev.github.io/assets/`, the organization's front page, rather than from a
  `<style>` block repeated in four repositories, and the page links to that front page — where one command
  installs every BotMaker tool at once. The installers, the repository and the signing key are untouched.
- **Parameters and plugin-managed constants are shown read-only.** Everything in `Parameters.java`, every
  `@Param` field wherever it lives, and every constant a plugin manages (the SDK's pictures) draws with its
  blocks and its picker — as a preview — and refuses edits, naming the window that owns it. A picker that
  opened a chooser and then had its write refused is gone.

- **An edit on the canvas changes only what it edits.** Every edit used to re-lay-out the whole file, so
  adding one statement could rewrite `private Collect() {}` three methods away. Now only the lines the edit
  wrote are laid out, and everything else stays exactly as you wrote it.
- **What Studio writes is indented with four spaces**, like the rest of the file, not with a tab.
- **A public parameter is written `visibility = Param.PUBLIC`**, the annotation's own constant, not the
  string `"public"`, so a field the Parameters window added reads like the ones beside it.

### Changed

- **The title bar and the Parameters window name the project's directory** (`~/IdeaProjects/gamebot`), not
  only its name. Two copies of one template are common, and an edit that seems lost is usually in the other
  copy.

## [1.1.5] — 2026-09-19

### Changed

- **Studio talks to the `BotMakerDev` organization.** The updater, the CLI update check, Help ▸ Report
  Issue and the Help menu's repository links follow the repositories that moved on 2026-09-18. The Linux
  install script and package repository are at `botmakerdev.github.io/botmaker-studio`.

### Fixed

- **Publishing to the gallery as its maintainer commits directly again.** It compared your login with the
  gallery's owner, which is an organization since the move, so the maintainer would have been sent down the
  fork path for a repository they cannot fork.

## [1.1.4] — 2026-09-18

### Added

- **Open a project from any folder.** *Open Folder…* on the project screen opens a Maven project wherever
  you keep it — your own repository, for instance — and it is listed under **Elsewhere** from then on. A
  template that declares its package in `botmaker-template.properties` opens in that package, whatever its
  folder is called. `--project=` also takes a path now; a bare name still means `~/BotMakerProjects/<name>`.

### Changed

- **Browse Bots, New Project's templates and Manage Plugins keep working when the gallery and the plugin
  registry move** to the `BotMakerDev` organization. Studio reads the new location first and the current one
  second, so it needs no update on the day they move.
- **Studio moves only the projects it keeps.** *Archive* works as before for a project in
  `~/BotMakerProjects`. For a project opened from elsewhere the button reads *Remove from Recents*, and it
  forgets the project without touching its folder.

## [1.1.3] — 2026-09-18

No source changes since v1.1.2; re-released for updated upstream pins.

### Added

- **Studio reads the parameters a bot declares in its own Java.** A `@Param` field is found wherever the
  author wrote it, with its type, its value, its category, its description, its bounds and its choices, and
  it can be added, renamed, retyped, re-annotated and removed from the editor — every edit landing in the
  source file as a one-line diff, leaving the author's own formatting and comments where they are. A field
  the editor cannot safely rewrite (not `public static`, `final`, a type no plugin registers, or a value
  written by hand as an expression) is still listed and still shows what it holds, with a line saying why
  its cell is read-only.
- **The Parameters window and the Runner are over those fields now.** One section per class of yours that
  declares any — headed with the file name, saying the bot reads them as `Parameters.<name>` — then one per
  plugin. Adding a parameter writes a field, and a project's first one creates `Parameters.java` and says
  so; renaming one repoints every reference to it in your bot; retyping one rewrites the declared type and
  resets the value, because a value written for one type is not a value of another. Removing one takes the
  declaration and **leaves the places your bot reads it**, with a line saying how many there are: what a
  use should become is your judgement, and the compiler is what points at them.
- **A plugin's section is value-only.** Its rows' names, types, categories and notes are shown as the
  plugin's own facts rather than as fields you can type into — because they are, and a text box you may not
  type in reads as something broken.
- The value cell is the same editor the canvas uses, a plugin's included, so a colour is picked off the
  screen and a duration is entered as hours and minutes wherever you meet one.
- **The categories on the left are yours.** They are whatever your `@Param` fields say, so the rail lists
  what you actually filed something under — the SDK's six fixed ones (Timing, Targets, Vision, Input,
  Limits, Debug) are gone, and an empty project no longer offers six drawers with nothing in them.

### Fixed

- **No more warnings at startup, at project open, or when a bot runs.** The JVM's "restricted method" notice
  named `javafx.graphics` as well as the unnamed module, and ClassGraph's cleaner call needed
  `--sun-misc-unsafe-memory-access=allow`; both are now in the run plugin, the installer and the jar's
  manifest. Opening a project printed six resolver warnings per module about JavaFX's published poms —
  silenced to `error`, where a resolution that really fails still reports. A bot's own JVM grants native
  access too, so OpenCV no longer warns in the output pane the moment a bot starts.

### Added

- **Vetted and Community bots.** Browse Bots shows each bot's tier as a badge and filters by it. A Vetted bot
  is one a maintainer looked at, and installing it downloads the release that was looked at. A Community bot
  was listed automatically, and the install warning says nobody reviewed its code.
- **Open on GitHub** on every Browse Bots row, and a *Requires* line naming the plugins a bot declares.
- **New Project lists Vetted templates.** *Show community templates* adds the rest, and appears only when there
  are any.
- **A new Publish window.** One page in sections — Kind (bot or template), Listing, Details, Release — beside a
  live preview of the Browse Bots card, a checklist of the publish's five steps, and your listing as the gallery
  has it: tier, pull request, whether it is waiting or needs a maintainer, and Unpublish.
- **A failed publish resumes.** *Retry* continues from the step that failed; the repository, upload and release
  already done are not repeated.
- **Listing no longer waits for a maintainer.** The entry goes to the gallery as a pull request its checks merge
  by themselves, and it names the plugins your bot requires. Re-publishing updates your listing; it used to say
  "already listed" and change nothing.

### Changed

- **An installed Vetted bot is offered its vetted release**, never an older one than it has. Everything else
  is offered its newest release, as before.
- Studio reads the gallery's `catalog.json`, and falls back to `index.json` when it cannot.

## [1.1.2] — 2026-09-17

### Added

- **Studio reads the parameters a bot declares in its own Java.** A `@Param` field is found wherever the
  author wrote it, with its type, its value, its category, its description, its bounds and its choices, and
  it can be added, renamed, retyped, re-annotated and removed from the editor — every edit landing in the
  source file as a one-line diff, leaving the author's own formatting and comments where they are. A field
  the editor cannot safely rewrite (not `public static`, `final`, a type no plugin registers, or a value
  written by hand as an expression) is still listed and still shows what it holds, with a line saying why
  its cell is read-only.
- **The Parameters window and the Runner are over those fields now.** One section per class of yours that
  declares any — headed with the file name, saying the bot reads them as `Parameters.<name>` — then one per
  plugin. Adding a parameter writes a field, and a project's first one creates `Parameters.java` and says
  so; renaming one repoints every reference to it in your bot; retyping one rewrites the declared type and
  resets the value, because a value written for one type is not a value of another. Removing one takes the
  declaration and **leaves the places your bot reads it**, with a line saying how many there are: what a
  use should become is your judgement, and the compiler is what points at them.
- **A plugin's section is value-only.** Its rows' names, types, categories and notes are shown as the
  plugin's own facts rather than as fields you can type into — because they are, and a text box you may not
  type in reads as something broken.
- The value cell is the same editor the canvas uses, a plugin's included, so a colour is picked off the
  screen and a duration is entered as hours and minutes wherever you meet one.
- **The categories on the left are yours.** They are whatever your `@Param` fields say, so the rail lists
  what you actually filed something under — the SDK's six fixed ones (Timing, Targets, Vision, Input,
  Limits, Debug) are gone, and an empty project no longer offers six drawers with nothing in them.

### Fixed

- **No more warnings at startup, at project open, or when a bot runs.** The JVM's "restricted method" notice
  named `javafx.graphics` as well as the unnamed module, and ClassGraph's cleaner call needed
  `--sun-misc-unsafe-memory-access=allow`; both are now in the run plugin, the installer and the jar's
  manifest. Opening a project printed six resolver warnings per module about JavaFX's published poms —
  silenced to `error`, where a resolution that really fails still reports. A bot's own JVM grants native
  access too, so OpenCV no longer warns in the output pane the moment a bot starts.

### Added

- **Vetted and Community bots.** Browse Bots shows each bot's tier as a badge and filters by it. A Vetted bot
  is one a maintainer looked at, and installing it downloads the release that was looked at. A Community bot
  was listed automatically, and the install warning says nobody reviewed its code.
- **Open on GitHub** on every Browse Bots row, and a *Requires* line naming the plugins a bot declares.
- **New Project lists Vetted templates.** *Show community templates* adds the rest, and appears only when there
  are any.
- **A new Publish window.** One page in sections — Kind (bot or template), Listing, Details, Release — beside a
  live preview of the Browse Bots card, a checklist of the publish's five steps, and your listing as the gallery
  has it: tier, pull request, whether it is waiting or needs a maintainer, and Unpublish.
- **A failed publish resumes.** *Retry* continues from the step that failed; the repository, upload and release
  already done are not repeated.
- **Listing no longer waits for a maintainer.** The entry goes to the gallery as a pull request its checks merge
  by themselves, and it names the plugins your bot requires. Re-publishing updates your listing; it used to say
  "already listed" and change nothing.

### Changed

- **An installed Vetted bot is offered its vetted release**, never an older one than it has. Everything else
  is offered its newest release, as before.
- Studio reads the gallery's `catalog.json`, and falls back to `index.json` when it cannot.

## [1.1.1] — 2026-09-16

No `## [Unreleased]` section exists — v1.1.0 is HEAD, tagged, no commits since. Nothing to write; an Unreleased section would be empty.

## [1.1.0] — 2026-09-16

### Added

- **A block may declare how it is rendered, and two surfaces read the declaration.**
  `CodeBlock.componentSpec(context)` answers a `ComponentSpec` — the labels, slots, pickers and bodies a block
  is made of, each as a supplier of its widget — and the overlay editor's `CompactSpecRow` draws it at HUD
  density, one line inside a 340px panel, while the canvas draws the same components through its own layout
  builder. Visibility and the lock are `ComponentResolver`'s on both surfaces, so a component hidden on one
  cannot appear on the other; a body is dropped in the HUD, because the tree already shows branches as nested
  rows. The default is an empty spec, and a block that declares none renders exactly as it did before — which
  is what lets this land one block at a time. **Observably a no-op so far: no block declares a spec yet.**

- **A call declares its sentence, argument by argument.** `MethodInvocationBlock` — the SDK badge, the scope,
  the method, the ⚙ overload picker, one component per argument with its ✕, the picture-run row, the varargs
  ＋, the `→ Type` badge and the ⓘ — is now a declaration rather than 130 lines of assembly, so the overlay
  editor can draw a call's arguments in the row instead of only as text. Each argument has a stable id
  (`arg0`, `arg1`, …) that survives a re-parse, which is what lets the HUD keep focus on the argument being
  edited while the block behind it is rebuilt. Everything the declaration needs — the scope selector, the
  method list, the resolved overload — is resolved once per render and only when something actually draws, so
  describing a call costs no classpath lookup. The call config popover keeps its job: a 340px row has no space
  for a parameter list or for a plugin's gallery, and that is a question about size rather than capability.
- **The control-flow blocks declare their shape too, bodies included.** `while`, `do/while`, `for each` and
  the assignment block answer `componentSpec`, which is what makes a body a *declared* part of a block rather
  than something each surface guesses at: the canvas breaks the block into rows at each body and draws it
  between them, the overlay HUD drops it and draws one line, because the HUD's tree already shows branches as
  nested rows. `do/while` is the case that proves the rule — its body is declared between two rows, so the
  closing `while (…)` lands under the body exactly as it always has.
- **Print and Return declare their sentence, and both surfaces read the declaration.** The first two blocks to
  answer `componentSpec` — a print is the word, one slot per argument and the ⊕; a return is the keyword plus
  whichever of its three tails applies (a value with its change button, a missing value with its ⊕, or the
  `(void)` note). Nothing about what is drawn changes: the canvas builds the same nodes it always did, through
  the same factories, because the sentence layout's keyword, label and slot construction is now called by both
  it and the schema instead of living in one of them. What is new is that the overlay HUD can draw those parts
  at its own density rather than falling back to a line of source text.
- `CodeEditorService.audience()` — one derivation of who the session is drawing for, with the canvas and the
  HUD as its readers. Reader mode is a live toggle, so it is read at render time and never captured.

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

- **The Wait block is the plugin's, so Studio no longer has one.** `blocks/flow/WaitBlock`, the
  `BlockConverter` arm that recognised `try { Thread.sleep(n) } catch (InterruptedException e)`, the palette's
  `Kind.WAIT` and the `StatementFactory` branch behind it are deleted. The hand-written Wait *entry* went on
  2026-09-01 with every other palette entry naming a plugin's type; what was left was the editor still
  recognising a raw-sleep spelling and captioning it `Wait … ms` — a second, host-flavoured way to say a word
  the SDK owns, under labels Studio invented. A wait written the plugin's way (`Wait.time(…)`) draws as an
  ordinary facade call with the plugin's own editors, as it already did. **The cost, plainly:** a bot whose
  source holds a hand-written `Thread.sleep` no longer shows that statement on the canvas — the same as every
  other `try`, which this editor has never drawn. The source text is untouched, and it still runs. It also
  removes the one place in the editor that ever wrote a `printStackTrace` into a user's bot.

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
