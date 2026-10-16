package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;

import java.util.Arrays;
import java.util.List;

import static com.botmaker.studio.assist.FileTools.FILE;
import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;
import static com.botmaker.studio.assist.McpTools.saidOneOf;
import static com.botmaker.studio.assist.RunTools.idle;

/**
 * The tools over the project: its versions, plugins, migrations, parameters and settings, and a new project. Each
 * family of verbs is one tool of several actions ({@link AssistantTool.Taking#takesOneOf}): a sealed interface here,
 * one record per action.
 */
final class ProjectTools {

    private ProjectTools() {}

    private static final String CONFIRM = "true to do it; left out, it only says what it would do";
    private static final String OLD_TYPE = "the old type, as list names it";
    private static final String ELEMENT = "the element, as list names it: GameScreen#marker(ImageTemplate)";
    private static final String PARAM = "the parameter: Class.name, or its name alone when only one has it";

    /** {@code versions}: the bot's saved versions. */
    sealed interface Versions {
        @Describe("the bot's versions, newest first: each one's id, when, who and its name")
        record List(@Describe(value = "how many, 20 when not given", optional = true) Integer limit)
                implements Versions {
        }

        @Describe("save the bot as it is now as a named version, before a change you may want to undo whole; the "
                + "user sees it in the Versions tab")
        record Checkpoint(@Describe("its name: Before the battle loop") String label) implements Versions {
        }

        @Describe("put the whole bot back as a version had it, what it is now saved first; the project reloads, "
                + "which ends a session in Studio's Assistant tab: tell the user before")
        record Revert(@Describe("the version's id, from list") String version) implements Versions {
        }
    }

    /** {@code plugins}: what the bot is built from. */
    sealed interface Plugins {
        @Describe("the bot's plugins, each with its version and whether it loaded, and where a set_version move is")
        record List() implements Plugins {
        }

        @Describe("search the plugin registry by a word of a plugin's name, id, description or tags")
        record Search(@Describe(value = "the word; empty for every plugin", optional = true) String query)
                implements Plugins {
        }

        @Describe("add a registry plugin at the version the registry checked, once the user agrees on screen: a "
                + "plugin runs with Studio's permissions; its tools are served once it loads")
        record Add(@Describe("the plugin's id: from search to add, else as list names it") String id)
                implements Plugins {
        }

        @Describe("move a plugin to another version: a release, or in dev mode this computer's build. Confirmed, "
                + "it answers once the user is asked on screen; when they agree a version is saved, the calls "
                + "into it are repaired and marked for review and its tools served anew; list says when it is "
                + "done. The local build it is on, confirmed, is loaded again")
        record SetVersion(String id,
                          @Describe("the version; a wrong one answers with those there are") String version,
                          @Describe(value = "true to move only the pom and leave the code to the bot's @Migrate "
                                  + "rules (migration list)", optional = true) Boolean rulesFirst,
                          @Describe(value = CONFIRM, optional = true) Boolean confirm) implements Plugins {
        }

        @Describe("remove a plugin: confirmed, a version is saved, the calls into it become a default or are "
                + "deleted, and their functions are marked for review")
        record Remove(String id,
                      @Describe(value = "also delete the files it keeps in the bot (its pictures, its flow)",
                              optional = true) Boolean deleteFiles,
                      @Describe(value = "", optional = true) Boolean confirm) implements Plugins {
        }
    }

    /** {@code migration}: the bot carried over a plugin change by its own {@code @Migrate} rules. */
    sealed interface Migration {
        @Describe("each old element (method, constructor, field, type) the bot uses that the last plugin change "
                + "broke, with its uses and its rule; the rules nothing needs now; values of retired types")
        record List() implements Migration {
        }

        @Describe("write an element's rule into Migrations: its old signature and a default body marked @Refactor; "
                + "then write")
        record Seed(@Describe(ELEMENT) String element) implements Migration {
        }

        @Describe("set a rule's body: what each use becomes, its parameters standing for the use's parts, self for "
                + "the receiver; one value, or one statement for a void rule. Checked to compile; the seed's mark goes")
        record Write(@Describe(ELEMENT) String element,
                     @Describe("the body: self.markers(List.of(marker))") String body,
                     @Describe(value = "qualified types it names that its file lacks, comma-separated: java.util.List",
                             optional = true) String imports) implements Migration {
        }

        @Describe("rewrite every use a written rule covers, each checked to compile; a use left is said. Without "
                + "confirm, each use before and after; with it, one version after a safety one")
        record Apply(@Describe(value = CONFIRM, optional = true) Boolean confirm) implements Migration {
        }

        @Describe("copy a retired type's values into a new type by the bot's @Migrate method for the pair (else "
                + "a first guess), each copy marked @Migrated beside the old one; nothing is deleted")
        record CopyValues(@Describe(OLD_TYPE) String from, @Describe("the new type") String to,
                          @Describe(value = CONFIRM, optional = true) Boolean confirm) implements Migration {
        }

        @Describe("point the bot's uses of the old values at their copies (OldShots.ORE becomes Shots.ORE), a "
                + "version saved first; a use the copy does not fit is left and its function marked for review")
        record SwitchUses(String from, @Describe(value = "", optional = true) String to,
                          @Describe(value = "the values to switch, comma-separated; empty for all", optional = true)
                          String names) implements Migration {
        }

        @Describe("remove the old values that have their copy and that nothing uses (migration switch_uses first), then the "
                + "@Migrate methods no value needs, and a class left empty")
        record RemoveMigrated(String from, @Describe(value = "", optional = true) String to,
                              @Describe(value = "", optional = true) Boolean confirm) implements Migration {
        }

        @Describe("remove the rules whose old element the bot uses nowhere now, and a file left empty")
        record RemoveFinished(@Describe(value = "", optional = true) Boolean confirm) implements Migration {
        }
    }

    /** {@code params}: the values the bot's user sets, its {@code @Param} fields. */
    sealed interface Params {
        @Describe("the bot's parameters, its @Param fields, with each one's type and value")
        record List() implements Params {
        }

        @Describe("set a parameter's value in the code: what the bot runs with until its user changes it")
        record SetDefault(@Describe(PARAM) String param,
                          @Describe("a value of its type, as a slot takes it") String value) implements Params {
        }

        @Describe("put a plugin's parameter back as the plugin declares it now: its first value, or written anew "
                + "when its type differs; refused when the bot would stop compiling; a version is saved first")
        record Reset(String param, @Describe(value = CONFIRM, optional = true) Boolean confirm) implements Params {
        }
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
                      @Describe(value = "registry plugin ids to add besides the template's, comma-separated; the "
                              + "user is asked on screen first", optional = true) String plugins) {
    }

    static List<AssistantTool<?>> of(StudioDriver driver, RunLog log, Runnable pluginsChanged) {
        return List.of(
                saidOneOf("versions", "The bot's saved versions: list them, save one, or put the bot back to one.",
                        Versions.class, action -> switch (action) {
                            case Versions.List list -> driver.listVersions(list.limit() == null ? 20 : list.limit());
                            case Versions.Checkpoint checkpoint -> driver.checkpoint(checkpoint.label());
                            case Versions.Revert revert -> idle(log, () -> driver.revert(revert.version()));
                        }),
                // A move ends after its answer; a failed one may have written the pom, so its end serves the tools
                // anew either way.
                saidOneOf("plugins",
                        "The plugins the bot is built from, which bring the palette's calls, the slots' types and "
                                + "tools of their own: list, search the registry, add, set_version, remove.",
                        Plugins.class, action -> switch (action) {
                            case Plugins.List list -> driver.listPlugins();
                            case Plugins.Search search -> driver.searchPlugins(given(search.query()));
                            case Plugins.Add add -> changed(pluginsChanged, driver.addPlugin(add.id()));
                            case Plugins.SetVersion move -> driver.setPluginVersion(move.id(), move.version(),
                                    yes(move.rulesFirst()), yes(move.confirm()), pluginsChanged);
                            case Plugins.Remove remove -> {
                                boolean confirm = yes(remove.confirm());
                                String done = driver.removePlugin(remove.id(), yes(remove.deleteFiles()), confirm);
                                yield confirm ? changed(pluginsChanged, done) : done;
                            }
                        }),
                said("plugin_guide",
                        "Read how the bot's plugins lay a bot out, each in its own words: where each kind of work "
                                + "goes and how to name it. Read it again after a plugin changes.",
                        () -> McpTools.guides(driver.pluginGuides())),
                saidOneOf("migration",
                        "Carry the bot over a plugin change by its @Migrate rules: list what broke, seed a rule, "
                                + "write its body, apply; copy_values, switch_uses, remove_migrated for retired "
                                + "types' values; remove_finished.",
                        Migration.class, action -> switch (action) {
                            case Migration.List list -> driver.listMigrations();
                            case Migration.Seed seed -> driver.seedRule(seed.element());
                            case Migration.Write write -> driver.writeRule(write.element(), write.body(),
                                    given(write.imports()));
                            case Migration.Apply apply -> driver.applyRules(yes(apply.confirm()));
                            case Migration.CopyValues copy -> driver.copyValues(copy.from(), copy.to(),
                                    yes(copy.confirm()));
                            case Migration.SwitchUses uses -> driver.switchUses(uses.from(), given(uses.to()),
                                    given(uses.names()));
                            case Migration.RemoveMigrated left -> driver.removeMigrated(left.from(), given(left.to()),
                                    yes(left.confirm()));
                            case Migration.RemoveFinished done -> driver.removeFinished(yes(done.confirm()));
                        }),
                saidOneOf("params", "The bot's parameters, the values its user sets: list, set_default, reset.",
                        Params.class, action -> switch (action) {
                            case Params.List list -> driver.listParams();
                            case Params.SetDefault set -> driver.setParamDefault(set.param(), set.value());
                            case Params.Reset reset -> driver.resetParam(reset.param(), yes(reset.confirm()));
                        }),
                said("delete_field",
                        "Delete a field of the bot's code nothing uses any more, a safety version saved first. "
                                + "Refused while anything uses it, saying where, and for a parameter its plugin "
                                + "declares (params reset).",
                        DeleteField.class, call -> driver.deleteField(given(call.file()), call.field())),
                said("get_settings", "List the project settings you may change, each with its value and what it "
                        + "takes.", driver::settings),
                said("set_setting", "Change a project setting.",
                        Setting.class, call -> {
                            // dev_mode reloads the plugins, whose tools may differ after it — even when the
                            // reload failed after the setting was written.
                            try {
                                return driver.setSetting(call.key(), call.value());
                            } finally {
                                if (call.key() == AssistedSetting.DEV_MODE) pluginsChanged.run();
                            }
                        }),
                said("list_templates", "List the vetted templates create_project starts a bot from.",
                        driver::listTemplates),
                said("create_project",
                        "Create a bot project from a template's release, or blank when the gallery cannot be read, "
                                + "and open it in place of this one. The switch ends this connection: reconnect "
                                + "once the new project is open, and tell the user before.",
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
