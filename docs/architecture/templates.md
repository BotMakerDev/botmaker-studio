# Templates — a starting point is a published bot (2026-08-30)

**New Project lists the gallery.** An entry whose `tags` carry `GalleryEntry.TEMPLATE_TAG` (`"template"`) is
a starting template rather than a bot to install: `ProjectSelectionScreen` lists exactly those (Vetted ones,
and Community ones behind *Show community templates* since 2026-09-16), and
`GalleryDialog` (Browse Bots) filters exactly those out. Nothing else about the gallery changes — same
`index.json`, same `bots/<owner>-<repo>.json`, same release zip, same `BotInstaller`.

That reuse is the whole point, and it is what a Maven archetype or a bundled resource would have cost:
**a new starting point needs no Studio release**, and the people who write bots are the people who write the
templates. What Studio composes itself is one blank project (`StarterSources`), which is what makes New
Project work with no network — and is the only reason it composes any at all.

- **A template arrives as its author shipped it, except for its package.** `project/TemplateProject` takes
  the package of the class holding `main` (`com.botmaker.gamebot`), replaces that prefix in every text file
  and moves the directories. The one-key `botmaker-template.properties` that declared it is read by nothing
  since 2026-09-27; a copy of an older release has it deleted. **The entry class keeps the author's name.** Renaming
  it was built and then dropped: a copy that quietly renames somebody's types is a copy whose stack traces
  and README stop matching, and the package is the one name that genuinely must not be shared.
- **So nothing may assume the entry class is named after the project.** `ProjectConfig.entrySourceFile()` /
  `entryClassName()` *find* it — the derived path when it exists, else the one class in the package
  declaring a `main` — and `CodeExecutionService`, `DebuggingService`, `CodeEditorService.openInitialFile`,
  `ProjectRepair.looksLikeGameBot` and `BotSettings.migrate` all go through them. This also fixes a case
  that predates templates: a user who renamed their own entry class could not Run.
- **`BotInstaller.unpackTemplate` writes no `botmaker-source.json`, and that absence is load-bearing.**
  Provenance is what makes a project an *installed bot*: `checkForUpdate` reads it, and an update
  re-downloads and replaces the project in place, overwriting local edits. A project made from a template is
  the user's from the second it lands, so there must be nothing for an update to find.
- **The template's pom is kept whole, versions and all**, which is why New Project hides the SDK row when a
  template is selected. What a template ships is what built for its author; changing the pin afterwards is
  Manage Libraries, like any other version change. `ProjectCreator.createFromTemplate` writes only
  `settings.json` and the capture resolution, and deletes the directory if anything fails — the unpack is a
  whole tree `Authoring` never sees, so the atomic pass on the blank path cannot cover it.
- **New Project picks plugins for any starting point (2026-10-01).** `ui/app/NewProjectPlugins` lists
  `sharing/PluginCatalog`'s rows (Browse's list); the template's own, by its entry's `requires` ids, are ticked
  and locked. `ProjectCreator.installPlugins` declares the rest before the first commit — skipping a coordinate
  the template's pom already declares — then `MavenService.dropShadowedPlugins`; a failure deletes the directory.
- **The publish-time check is `TemplateProject.matches`**, run when *This is a starting template* is ticked.
  It is the only template mistake whose result still **compiles**: a declared package with no sources in it
  unpacks into somebody's New Project, renames nothing, and hands them a working project sitting in the
  author's package.

