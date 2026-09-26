package com.botmaker.studio.project.migration;

import com.botmaker.shared.config.ProjectProperties;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioProjectSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The version marker exists to answer one question — <i>has this file already been brought forward?</i> — so
 * what these tests hold is the answer's edges: absent means 0 rather than an error, a written file carries the
 * number, a second open does nothing, and a number this build does not know is refused instead of guessed at.
 */
class ProjectSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Files.createDirectories(config.mainSourceFile().getParent());
        Files.createDirectories(config.resourcesRoot());
        Files.createDirectories(config.studioRoot());
        return config;
    }

    private static Path settings(ProjectConfig config) {
        return SchemaFile.SETTINGS.in(config.studioRoot());
    }

    private static Path properties(ProjectConfig config) {
        return SchemaFile.PROPERTIES.in(config.resourcesRoot());
    }

    // --- reading the marker ---------------------------------------------------------------------------

    @Test
    void anAbsentFileHasNoVersionAtAll(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        for (SchemaFile file : SchemaFile.values()) {
            assertEquals(OptionalInt.empty(), file.versionIn(file.dirOf(config)),
                    file.fileName() + " is not on disk, so it makes no claim about its shape");
        }
    }

    @Test
    void aFileWithoutTheKeyIsVersionZero(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{}");
        Files.writeString(properties(config), ProjectProperties.KEY_DEBUG + "=true\n");

        assertEquals(OptionalInt.of(0), SchemaFile.SETTINGS.versionIn(config.studioRoot()));
        assertEquals(OptionalInt.of(0), SchemaFile.PROPERTIES.versionIn(config.resourcesRoot()));
    }

    // --- where the settings live ---------------------------------------------------------------------

    @Test
    void theSettingsLiveOutsideTheResourcesSoNoJarCarriesThem(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        assertEquals(config.projectPath().resolve(".botmaker"), SchemaFile.SETTINGS.dirOf(config));
        assertEquals(config.resourcesRoot(), SchemaFile.PROPERTIES.dirOf(config),
                "the properties are the bot's, read off its classpath, so they stay");
    }

    @Test
    void anOldProjectsSettingsAreMovedOnceWhenItIsChecked(@TempDir Path root) throws Exception {
        ProjectConfig config = project(root);
        Path legacy = config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME);
        StudioProjectSettings.empty().withTemplate(com.botmaker.studio.project.ProjectTemplate.GAME_BOT)
                .write(config.resourcesRoot());

        ProjectSchema.check(config);

        assertFalse(Files.exists(legacy), "moved, not copied: a copy left there is still packaged");
        assertEquals(com.botmaker.studio.project.ProjectTemplate.GAME_BOT,
                StudioProjectSettings.read(config.studioRoot()).template());
    }

    @Test
    void whenBothPlacesHoldOneTheNewOneIsTheAnswerAndTheOldOneIsLeft(@TempDir Path root) throws Exception {
        ProjectConfig config = project(root);
        Path legacy = config.resourcesRoot().resolve(StudioProjectSettings.FILE_NAME);
        Files.writeString(legacy, "{\"lastRecordedActivity\":\"old\"}");
        StudioProjectSettings.empty().withLastRecordedActivity("new").write(config.studioRoot());

        assertFalse(StudioProjectSettings.moveOutOfResources(config));
        assertTrue(Files.exists(legacy), "a file this did not move is not this one's to delete");
        assertEquals("new", StudioProjectSettings.read(config.studioRoot()).lastRecordedActivity());
    }

    // --- writing it ----------------------------------------------------------------------------------

    @Test
    void writingSettingsStampsTheCurrentVersion(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        StudioProjectSettings.empty().write(config.studioRoot());

        JsonNode json = MAPPER.readTree(settings(config).toFile());
        assertEquals(SchemaFile.SETTINGS.current(), json.get(SchemaFile.JSON_FIELD).asInt());
        assertTrue(json.size() > 1, "the stamp is written beside the settings, not instead of them");
    }

    @Test
    void theStampSurvivesAReadWriteRoundTrip(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        StudioProjectSettings.empty().write(config.studioRoot());
        StudioProjectSettings.read(config.studioRoot()).write(config.studioRoot());

        // The number is not a record component, so a round trip through the model has to re-derive it rather
        // than carry it — which is exactly the case that would silently write version 0 back if write() did
        // not stamp.
        assertEquals(OptionalInt.of(SchemaFile.SETTINGS.current()),
                SchemaFile.SETTINGS.versionIn(config.studioRoot()));
    }

    @Test
    void stampingIsNotHowAFileGetsCreated(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        SchemaFile.SETTINGS.stampIfPresent(config.studioRoot());

        assertFalse(Files.exists(settings(config)),
                "nothing is created merely to hold a number — an absent file stays absent and stays at 0");
    }

    // --- migrating -----------------------------------------------------------------------------------

    @Test
    void migratingStampsEveryFileThatIsPresent(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{}");
        Files.writeString(properties(config), ProjectProperties.KEY_DEBUG + "=true\n");

        ProjectSchema.migrate(config, ignored -> { });

        assertEquals(OptionalInt.of(SchemaFile.SETTINGS.current()),
                SchemaFile.SETTINGS.versionIn(config.studioRoot()));
        assertEquals(OptionalInt.of(SchemaFile.PROPERTIES.current()),
                SchemaFile.PROPERTIES.versionIn(config.resourcesRoot()));

        Properties props = new Properties();
        try (var in = Files.newInputStream(properties(config))) {
            props.load(in);
        }
        assertEquals("true", props.getProperty(ProjectProperties.KEY_DEBUG),
                "stamping the file must not lose what was already in it");
    }

    /**
     * The files the old generator wrote are the user's now, and the project is told so once.
     *
     * <p>The two halves are equally load-bearing. Reporting is what stops a user believing BotMaker still
     * owns those files; writing <b>nothing</b> is what makes the sentence true.
     */
    @Test
    void aProjectFromTheGeneratorIsToldItsFilesAreItsOwn(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{}");
        Path pkg = config.mainSourceFile().getParent();
        Files.writeString(pkg.resolve("Activities.java"), "package com.mybot;\npublic class Activities {}\n");
        Files.writeString(pkg.resolve("Templates.java"), "package com.mybot;\npublic class Templates {}\n");
        Files.createDirectories(config.activitiesPackageDir());
        Files.writeString(config.activitiesPackageDir().resolve("Mining.java"),
                "package com.mybot.activities;\npublic class Mining {}\n");

        List<String> report = ProjectSchema.migrate(config, ignored -> { });

        assertTrue(report.stream().anyMatch(line -> line.contains("Activities.java")
                        && line.contains("Templates.java") && line.contains("1 in activities/")),
                "the user is told which files they have inherited: " + report);
        assertTrue(Files.exists(pkg.resolve("Activities.java")), "and not one of them is touched");
        assertEquals("package com.mybot;\npublic class Templates {}\n",
                Files.readString(pkg.resolve("Templates.java")));
        assertTrue(Files.exists(config.activitiesPackageDir().resolve("Mining.java")));

        assertTrue(ProjectSchema.migrate(config, ignored -> { }).isEmpty(),
                "and told once — a sentence repeated on every open stops being read");
    }

    @Test
    void aProjectThatNeverHadThoseFilesIsToldNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{}");
        Files.writeString(config.mainSourceFile(),
                "package com.mybot;\npublic class MyBot { public static void main(String[] a) {} }\n");

        List<String> report = ProjectSchema.migrate(config, ignored -> { });

        assertTrue(report.stream().noneMatch(line -> line.contains("yours now")),
                "a blank project has inherited nothing: " + report);
    }

    @Test
    void aSecondOpenRunsNothingAndReportsNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{}");
        Files.writeString(properties(config), ProjectProperties.KEY_DEBUG + "=true\n");

        ProjectSchema.migrate(config, ignored -> { });
        String settingsAfterFirst = Files.readString(settings(config));

        List<String> second = ProjectSchema.migrate(config, ignored -> { });

        assertTrue(second.isEmpty(), "an already-current project has nothing to say about being opened");
        assertEquals(settingsAfterFirst, Files.readString(settings(config)),
                "the whole point of the number: the second open does not touch the file");
    }

    @Test
    void aNewProjectIsStampedByItsOwnCreation(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        StudioProjectSettings.empty().write(config.studioRoot());

        assertTrue(ProjectSchema.migrate(config, ignored -> { }).isEmpty(),
                "a project written by this Studio is already in this Studio's shape");
    }

    // --- refusing ------------------------------------------------------------------------------------

    @Test
    void aProjectFromTheFutureIsRefusedByName(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "{\"" + SchemaFile.JSON_FIELD + "\":99}");

        ProjectSchemaTooNew refusal =
                assertThrows(ProjectSchemaTooNew.class, () -> ProjectSchema.check(config));

        assertTrue(refusal.getMessage().contains(SchemaFile.SETTINGS.fileName()),
                "the message has to name the file, since only one of the two is from the future");
        assertTrue(refusal.getMessage().contains("99"));
        assertTrue(refusal.getMessage().contains("update Studio"), "and say the way out");
    }

    @Test
    void aPropertiesFileFromTheFutureIsRefusedToo(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(properties(config), ProjectProperties.KEY_SCHEMA_VERSION + "=42\n");

        assertThrows(ProjectSchemaTooNew.class, () -> ProjectSchema.check(config));
    }

    @Test
    void anOrdinaryProjectIsNotRefused(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        StudioProjectSettings.empty().write(config.studioRoot());
        Files.writeString(properties(config), ProjectProperties.KEY_DEBUG + "=true\n");

        ProjectSchema.check(config);
    }

    @Test
    void anUnreadableFileIsNotAClaimAboutTheFuture(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Files.writeString(settings(config), "this is not json");

        // Refusing here would turn a corrupt file into "your Studio is too old", which is both wrong and
        // unactionable. It has no version, so it is left to the reader that actually parses it.
        ProjectSchema.check(config);
    }
}
