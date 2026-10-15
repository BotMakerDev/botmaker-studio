package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.managed.TestValue;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** params reset and delete_field: a plugin's leftover parameter put back, a field nothing needs deleted. */
class LeftoverFieldsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();

    static final ManagedValue<Boolean> SETUP = ManagedValue.param(TestValue.Id.DO_SETUP).in("Sdk")
            .startsAs(LeftoverFieldsTest::setup).shown("Run", "Setup", null).because("New.");
    static final ManagedValue<List<Integer>> NUMBERS = ManagedValue.param(TestValue.Id.NAMES).in("Sdk")
            .startsAs(LeftoverFieldsTest::noNumbers).shown("Game", "Numbers", null).because("New.");
    private static final List<ManagedValue<?>> LIVE = List.of(SETUP, NUMBERS);

    public static boolean setup() {
        return true;
    }

    public static List<Integer> noNumbers() {
        return List.of();
    }

    private static final String SDK = """
            package %s.plugins.test;

            import %s;
            import java.util.List;

            public final class Sdk {
                @TestValue(TestValue.Id.DO_SETUP)
                public static boolean doSetup = false;
                @TestValue(TestValue.Id.NAMES)
                public static List<%s> numbers = List.of();
            }
            """;

    @Test
    void aParameterOfItsTypeTakesItsFirstValueAgain(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path sdk = holder(config);
        BotIndex index = index(config, Map.of(sdk, sdk(config, "Integer")));

        Refactor.Outcome reset = LeftoverFields.reset(index, owned(sdk, "doSetup", SETUP), LIVE, GRAMMAR);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, reset, reset.toString());
        assertTrue(plan.rewrites().get(sdk).contains("doSetup = true"), plan.rewrites().get(sdk));
    }

    /** The upgrade's own test: {@code List<String>} where the plugin declares {@code List<Integer>} is gone. */
    @Test
    void aParameterOfAnotherTypeIsRemovedAndWrittenAnew(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path sdk = holder(config);
        BotIndex index = index(config, Map.of(sdk, sdk(config, "String")));

        Refactor.Outcome reset = LeftoverFields.reset(index, owned(sdk, "numbers", NUMBERS), LIVE, GRAMMAR);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, reset, reset.toString());
        assertFalse(plan.rewrites().get(sdk).contains("List<String>"), plan.rewrites().get(sdk));
        assertTrue(plan.summary().startsWith("Removed Sdk.numbers"), plan.summary());
    }

    @Test
    void theBotsOwnParameterIsNotReset(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path sdk = holder(config);
        BotIndex index = index(config, Map.of(sdk, sdk(config, "Integer")));
        JavaParameter own = owned(sdk, "doSetup", SETUP).owned("", "");

        Refactor.Outcome reset = LeftoverFields.reset(index, own, LIVE, GRAMMAR);

        assertTrue(((Refactor.Refused) reset).reason().contains("params set_default"), reset.toString());
    }

    private static final String HELPERS = """
            package %s;

            public final class Helpers {
                static int unused = 3;
                static int used = 4;

                static int twice() {
                    return used * 2;
                }
            }
            """;

    @Test
    void aFieldNothingUsesIsDeletedAndOneInUseIsNot(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path helpers = config.mainPackageDir().resolve("Helpers.java").toAbsolutePath().normalize();
        BotIndex index = index(config, Map.of(helpers, HELPERS.formatted(config.mainPackage())));

        Refactor.Outcome deleted = LeftoverFields.delete(index, helpers, "unused", LIVE);
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, deleted, deleted.toString());
        assertFalse(plan.rewrites().get(helpers).contains("unused"), plan.rewrites().get(helpers));

        Refactor.Refused kept = assertInstanceOf(Refactor.Refused.class,
                LeftoverFields.delete(index, helpers, "Helpers.used", LIVE));
        assertEquals(1, kept.uses().size(), kept.reason());
    }

    @Test
    void aParameterItsPluginDeclaresIsNotDeleted(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path sdk = holder(config);
        BotIndex index = index(config, Map.of(sdk, sdk(config, "Integer")));

        Refactor.Outcome refused = LeftoverFields.delete(index, sdk, "doSetup", LIVE);

        assertTrue(((Refactor.Refused) refused).reason().contains("params reset"), refused.toString());
    }

    private static JavaParameter owned(Path file, String name, ManagedValue<?> value) {
        return new JavaParameter(file, "Sdk", ParameterRow.named(name, "boolean").build(), ValueTypes.NONE, "",
                true, "", "New.", value.id());
    }

    private static String sdk(ProjectConfig config, String element) {
        return SDK.formatted(config.mainPackage(), TestValue.class.getCanonicalName(), element);
    }

    private static Path holder(ProjectConfig config) {
        return config.mainPackageDir().resolve("plugins").resolve("test").resolve("Sdk.java")
                .toAbsolutePath().normalize();
    }

    private static BotIndex index(ProjectConfig config, Map<Path, String> sources) {
        return BotIndex.over(sources, List.of(locationOf(com.botmaker.plugin.api.meta.Refactor.class),
                locationOf(TestValue.class)), config.sourceRoot());
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
