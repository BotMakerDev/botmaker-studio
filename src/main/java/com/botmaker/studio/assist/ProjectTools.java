package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;

import java.util.Arrays;
import java.util.List;

import static com.botmaker.studio.assist.FileTools.FILE;
import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;
import static com.botmaker.studio.assist.RunTools.idle;

/** The tools over the project: its versions, plugins, migrations, parameters and settings, and a new project. */
final class ProjectTools {

    private ProjectTools() {}

    private static final String PLUGIN = "the plugin, as list_plugins names it";
    private static final String OLD_TYPE = "the old type, as list_migrations names it";
    private static final String PARAM = "the parameter: Class.name, or its name alone when only one has it";

    record Versions(@Describe(value = "how many, 20 when not given", optional = true) Integer limit) {
    }

    record Checkpoint(@Describe("what the version is: Before the battle loop") String label) {
    }

    record Revert(@Describe("the version's id, from list_versions") String version) {
    }

    record Search(@Describe(value = "the word; empty for every plugin", optional = true) String query) {
    }

    record AddPlugin(@Describe("the plugin's id, from search_plugins") String id) {
    }

    record RemovePlugin(@Describe(PLUGIN) String id,
                        @Describe(value = "also delete the files it keeps in the bot (its pictures, its flow)",
                                optional = true) Boolean deleteFiles,
                        @Describe(value = "true to remove it; false or absent to only see what would change",
                                optional = true) Boolean confirm) {
    }

    record PluginVersion(@Describe(PLUGIN) String id,
                         @Describe("the version to move to; a wrong one answers with the versions there are")
                         String version,
                         @Describe(value = "true to move it; false or absent to only see what would change",
                                 optional = true) Boolean confirm) {
    }

    record ReadMigration(@Describe(OLD_TYPE) String from,
                         @Describe(value = "the new type; empty for the one its plugin names, else the first that "
                                 + "fits", optional = true) String to) {
    }

    record SetMigration(@Describe(OLD_TYPE) String from,
                        @Describe("the new type, as read_migration names it") String to,
                        @Describe(value = "changes to read_migration's mapping, one per line: 'part: from <old part>', "
                                + "'part: built from <old part>', 'part: fixed <value>', or 'OLD: NEW' for a constant; "
                                + "a pair the parts go through prefixed by its type's simple name ('Area.w: fixed 0'); "
                                + "empty to take it as it is", optional = true) String mapping,
                        @Describe(value = "true to write it; false or absent to only see what would be written",
                                optional = true) Boolean confirm) {
    }

    record SwitchUses(@Describe(OLD_TYPE) String from,
                      @Describe(value = "the type holding the copies; empty for the one that does", optional = true)
                      String to,
                      @Describe(value = "the values to switch, comma-separated; empty for all", optional = true)
                      String names) {
    }

    record RemoveMigrated(@Describe(OLD_TYPE) String from,
                          @Describe(value = "the type holding the copies; empty for the one that does",
                                  optional = true) String to,
                          @Describe(value = "true to remove them; false or absent to only see what would go",
                                  optional = true) Boolean confirm) {
    }

    record ParamDefault(@Describe(PARAM) String param,
                        @Describe("a value of its type, as set_slot takes it") String value) {
    }

    record ResetParam(@Describe(PARAM) String param,
                      @Describe(value = "true to do it; false or absent to only see what it would do",
                              optional = true) Boolean confirm) {
    }

    record DeleteField(@Describe(value = FILE, optional = true) String file,
                       @Describe("the field's name, or Class.name when two classes of the file have one")
                       String field) {
    }

    record Setting(@Describe("the setting, from get_settings") AssistedSetting key,
                   @Describe("its new value") String value) {
    }

    record NewProject(@Describe("one word of letters and digits, starting with a letter: Firestone") String name,
                      @Describe(value = "a template's name from list_templates; empty only when the gallery cannot "
                              + "be read, for a blank project", optional = true) String template,
                      @Describe(value = "registry plugin ids to add besides the template's, comma-separated "
                              + "(search_plugins); the user is asked on screen first", optional = true)
                      String plugins) {
    }

    static List<AssistantTool<?>> of(StudioDriver driver, RunLog log, Runnable pluginsChanged) {
        return List.of(
                said("list_versions",
                        "List the bot's versions, newest first: each one's id, when it was made, who made it and "
                                + "its name. revert takes the id.",
                        Versions.class, call -> driver.listVersions(call.limit() == null ? 20 : call.limit())),
                said("checkpoint",
                        "Save the bot as it is now as a named version — before a change you may want to undo as a "
                                + "whole. The user sees it in the Versions tab.",
                        Checkpoint.class, call -> driver.checkpoint(call.label())),
                said("revert",
                        "Put the whole bot back as a version had it. What it is now is saved as a version first, so "
                                + "nothing is lost. The project reloads, which ends an assistant session running in "
                                + "Studio's Assistant tab: say so to the user before calling it.",
                        Revert.class, call -> idle(log, () -> driver.revert(call.version()))),
                said("list_plugins",
                        "List the plugins the bot uses, each with its version and whether it loaded, and where a "
                                + "set_plugin_version move is. A plugin brings the palette's calls and the types "
                                + "slots take, and may bring tools of its own.",
                        driver::listPlugins),
                said("plugin_guide",
                        "Read how the bot's plugins lay a bot out, each in its own words: where each kind of work "
                                + "goes and how to name it, as the bound plugins say it now: read it again after a "
                                + "plugin changes.",
                        () -> McpTools.guides(driver.pluginGuides())),
                said("search_plugins", "Search the plugin registry, by a word of a plugin's name, id, "
                                + "description or tags.",
                        Search.class, call -> driver.searchPlugins(given(call.query()))),
                said("add_plugin",
                        "Add a plugin from the registry to the bot, at the version the registry checked. The user "
                                + "is asked on screen first: a plugin runs with Studio's permissions. Its tools "
                                + "are served once it loads.",
                        AddPlugin.class, call -> changed(pluginsChanged, driver.addPlugin(call.id()))),
                said("remove_plugin",
                        "Remove a plugin from the bot. Without confirm it only says what would change: the calls "
                                + "into it that become a default value or are deleted, and its files. With "
                                + "confirm a version is saved first, then the calls are repaired and the "
                                + "functions they were in are marked for review.",
                        RemovePlugin.class, call -> {
                            boolean confirm = yes(call.confirm());
                            String done = driver.removePlugin(call.id(), yes(call.deleteFiles()), confirm);
                            return confirm ? changed(pluginsChanged, done) : done;
                        }),
                // The move ends after the answer; a failed one may have written the pom, so its end serves the
                // tools anew either way.
                said("set_plugin_version",
                        "Move a plugin the bot uses to another version: a release, or in dev mode this computer's "
                                + "own build (set_setting dev_mode on). Without confirm it only says what would "
                                + "change, or which versions there are. With confirm it answers once the user is "
                                + "asked on screen; when they agree a version of the bot is saved, the calls into it "
                                + "are repaired and marked for review, and its tools are served anew. list_plugins "
                                + "says when the move is done. The local build it is on already, with confirm, is "
                                + "loaded again: a rebuild into ~/.m2 runs without restarting Studio.",
                        PluginVersion.class, call -> driver.setPluginVersion(call.id(), call.version(),
                                yes(call.confirm()), pluginsChanged)),
                said("list_migrations",
                        "List what the bot has to migrate: the values of each type a plugin retired (marked "
                                + "@Deprecated, pointing to its replacement), how many are copied already, and the "
                                + "bot's @Migrate methods, which copy old values into the new type. Before "
                                + "set_plugin_version to a version that drops a set, migrate its values on the "
                                + "version the bot has now.",
                        driver::listMigrations),
                said("read_migration",
                        "Read how one type's values are copied into another: each part of the new type and what it "
                                + "is made of (from an old part, built from one, or a fixed value), or each old "
                                + "constant and the new one it becomes — as the bot's @Migrate method has it, else "
                                + "a first guess — with what else each can be, the pairs of part types it goes "
                                + "through and why it cannot be written yet.",
                        ReadMigration.class, call -> driver.readMigration(call.from(), given(call.to()))),
                said("set_migration",
                        "Copy one type's values into another, as the migration window's Apply: the bot keeps the "
                                + "mapping as @Migrate methods and gets each value's copy, marked @Migrated, under "
                                + "the same name beside the old one; nothing is deleted. The mapping starts as "
                                + "read_migration says it; each line of mapping changes one part or constant, in "
                                + "its words. Without confirm it only says what would be written and each value "
                                + "before and after; with confirm a version is saved first. A mapping that would "
                                + "not compile writes nothing and says why.",
                        SetMigration.class, call -> driver.setMigration(call.from(), call.to(), given(call.mapping()),
                                yes(call.confirm()))),
                said("switch_uses",
                        "Point the bot's uses of a migrated type's values at their copies (OldShots.ORE becomes "
                                + "Shots.ORE). A use the copy does not fit — held where only the old type goes — is "
                                + "left and its function marked for review; change what holds it, then switch "
                                + "again. A version of the bot is saved first.",
                        SwitchUses.class, call -> driver.switchUses(call.from(), given(call.to()),
                                given(call.names()))),
                said("remove_migrated",
                        "Remove what a migration leaves behind: the old type's values that have their copy and "
                                + "that nothing uses any more (switch_uses first), then every @Migrate method no "
                                + "value needs — and a class left with nothing else. Without confirm it only says "
                                + "what would go; with confirm a version of the bot is saved first.",
                        RemoveMigrated.class, call -> driver.removeMigrated(call.from(), given(call.to()),
                                yes(call.confirm()))),
                said("list_params",
                        "List the bot's parameters — its @Param fields, the values its user can set — with each "
                                + "one's type and value.",
                        driver::listParams),
                said("set_param_default",
                        "Set a parameter's value in the code: what the bot runs with until its user changes it.",
                        ParamDefault.class, call -> driver.setParamDefault(call.param(), call.value())),
                said("reset_param",
                        "Put a plugin's parameter back as its plugin declares it now: its first value, or — when "
                                + "the field is another type than the plugin declares, as a plugin's move can leave "
                                + "it — removed and written anew. Refused when the bot would stop compiling. Without "
                                + "confirm it only says what it would do; with confirm a version of the bot is saved "
                                + "first.",
                        ResetParam.class, call -> driver.resetParam(call.param(), yes(call.confirm()))),
                said("delete_field",
                        "Delete a field of the bot's code — one a plugin no longer declares, say. Refused, with every "
                                + "place that still uses it, while anything does; for a parameter a plugin declares "
                                + "(reset_param); and when the bot would stop compiling. A safety version is saved "
                                + "first.",
                        DeleteField.class, call -> driver.deleteField(given(call.file()), call.field())),
                said("get_settings", "List the project settings you may change, each with its value and "
                        + "what it takes.", driver::settings),
                said("set_setting", "Change a project setting, one get_settings lists.",
                        Setting.class, call -> {
                            // dev_mode reloads the plugins, whose tools may differ after it — even when the
                            // reload failed after the setting was written.
                            try {
                                return driver.setSetting(call.key(), call.value());
                            } finally {
                                if (call.key() == AssistedSetting.DEV_MODE) pluginsChanged.run();
                            }
                        }),
                said("list_templates", "List the vetted templates create_project starts a bot from, each with "
                        + "what it is.", driver::listTemplates),
                said("create_project",
                        "Create a new bot project, as New Project does, and open it in Studio in place of this one: "
                                + "from a vetted template's release (list_templates), or blank when the gallery "
                                + "cannot be read. The template's own plugins come with it. The switch ends this "
                                + "connection; the endpoint comes back on the same address once the new project is "
                                + "open, so reconnect then. Say so to the user before calling it.",
                        NewProject.class, call -> idle(log, () -> driver.createProject(call.name(),
                                given(call.template()), Arrays.stream(given(call.plugins()).split(","))
                                        .map(String::strip).filter(s -> !s.isEmpty()).toList()))));
    }

    /** {@code answer}, after telling the endpoint the plugins may have changed. */
    private static String changed(Runnable pluginsChanged, String answer) {
        pluginsChanged.run();
        return answer;
    }

    /** {@code given} as a yes or no; no when left out. */
    private static boolean yes(Boolean given) {
        return Boolean.TRUE.equals(given);
    }
}
