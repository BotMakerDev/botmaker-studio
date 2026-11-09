package com.botmaker.studio.services;

import com.botmaker.core.edit.Edit;
import com.botmaker.core.edit.EditResult;
import com.botmaker.core.edit.HistoryStep;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.core.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-back half of the review model: what a refactor wrote into the source, listed for the user, and
 * marked {@code done} as they work through it.
 *
 * <p>What is pinned here is that the <b>source is the truth</b>. Nothing caches the list, so every assertion
 * below is about what the files say — including the two that matter most: a function marked reviewed says
 * {@code done = true} in the file rather than in a list in memory, and the open buffer and the file on disk
 * never disagree about it.
 */
class ReviewServiceTest {

    private static final String IMPORT = "import com.botmaker.plugin.api.meta.Refactor;";

    @Test
    void everyFunctionWithAnOpenMarkIsARow(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;

                %s

                class Miner {
                    @Refactor({"the first thing", "the second thing"})
                    void mine() {}

                    void rest() {}

                    @Refactor("the third thing")
                    void haul() {}

                    @Refactor(value = "looked at already", done = true)
                    void carry() {}
                }
                """.formatted(IMPORT));

        List<ReviewService.Item> items = ReviewService.scan(config, null);

        assertEquals(List.of("mine", "haul"), items.stream().map(ReviewService.Item::function).toList(),
                "a reviewed mark is a record, not a row");
        assertEquals(List.of("the first thing", "the second thing"), items.getFirst().entries());
        assertTrue(items.getFirst().where().startsWith("Miner.java · mine()"), items.getFirst().where());
    }

    @Test
    void aProjectNothingHasRewrittenListsNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", "class Miner { void mine() {} }\n");

        assertTrue(ReviewService.scan(config, null).isEmpty());
    }

    /** Reviewed keeps the record: the entries and the import stay, and the row goes. */
    @Test
    void markingReviewedSetsDoneAndKeepsTheRecord(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot.activities;

                %s

                class Miner {
                    @Refactor("the only thing")
                    void mine() {}
                }
                """.formatted(IMPORT));

        assertTrue(ReviewService.markReviewed(config, null, ReviewService.scan(config, null).getFirst()));

        String source = Files.readString(config.sourceRoot().resolve("Miner.java"));
        assertTrue(source.contains("done = true") && source.contains("the only thing"), source);
        assertTrue(source.contains(IMPORT), source);
        assertTrue(ReviewService.scan(config, null).isEmpty());
    }

    /** Two functions of the same name in one file: the entries identify which mark a row is. */
    @Test
    void theEntriesIdentifyTheMarkRatherThanTheFunctionName(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "Miner.java", """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("about the one with no inputs")
                    void mine() {}

                    @Refactor("about the one with a depth")
                    void mine(int depth) {}
                }
                """.formatted(IMPORT));

        ReviewService.markReviewed(config, null, ReviewService.scan(config, null).get(1));

        List<ReviewService.Item> left = ReviewService.scan(config, null);
        assertEquals(1, left.size(), left.toString());
        assertEquals(List.of("about the one with no inputs"), left.getFirst().entries());
    }

    /**
     * The user may have reverted the change through Versions, or edited the mark away by hand, since the list
     * was drawn. That is a re-scan, not an error.
     */
    @Test
    void aMarkThatIsNoLongerThereChangesNothing(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", "class Miner { void mine() {} }\n");

        assertFalse(ReviewService.markReviewed(config, null,
                new ReviewService.Item(file, "mine", 1, List.of("something that was never written"))));
    }

    /** Same rule as every rewrite in Studio: the buffer is the truth, and the disk is kept equal to it. */
    @Test
    void theOpenBufferIsMarkedTogetherWithTheFile(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", "class Miner { void mine() {} }\n");
        ProjectState state = new ProjectState();
        ProjectFile open = new ProjectFile(file, """
                %s
                class Miner {
                    @Refactor("look at this")
                    void mine() {}
                }
                """.formatted(IMPORT));
        state.addFile(open);

        List<ReviewService.Item> items = ReviewService.scan(config, state);
        assertEquals(1, items.size(), "the buffer's mark is the one that counts, not the stale disk copy");

        ReviewService.markReviewed(config, state, items.getFirst());

        assertTrue(open.getContent().contains("done = true"), open.getContent());
        assertEquals(open.getContent(), Files.readString(file));
    }

    @Test
    void removingAMarkTakesItsImportOnlyWithTheLastOne(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        Path file = write(config, "Miner.java", """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("one")
                    void mine() {}

                    @Refactor("two")
                    void haul() {}
                }
                """.formatted(IMPORT));
        List<ReviewService.Item> items = ReviewService.scan(config, null);

        assertTrue(ReviewService.removeMark(config, null, items.getFirst()));
        String once = Files.readString(file);
        assertFalse(once.contains("@Refactor(\"one\")"), once);
        assertTrue(once.contains(IMPORT), "haul() still carries one");

        assertTrue(ReviewService.removeMark(config, null, ReviewService.scan(config, null).getFirst()));
        String twice = Files.readString(file);
        assertFalse(twice.contains("@Refactor"), twice);
    }

    /**
     * Undo is the core's (its {@code FunctionRestoreTest} pins what comes back): Studio asks it by the function's
     * address, the file named below the source root, and passes its answer on.
     */
    @Test
    void undoAsksTheCoreByTheFunctionsAddress(@TempDir Path root) throws IOException {
        ProjectConfig config = project(root);
        write(config, "com/refbot/Miner.java", """
                package com.refbot;

                %s

                class Miner {
                    @Refactor("List.of is gone.")
                    void mine() {}
                }
                """.formatted(IMPORT));
        List<String> asked = new ArrayList<>();
        EditPort core = new EditPort() {
            @Override
            public EditResult userEdit(Edit edit) {
                throw new AssertionError("no edit");
            }

            @Override
            public EditResult step(HistoryStep step) {
                throw new AssertionError("no step");
            }

            @Override
            public EditResult show(String file) {
                throw new AssertionError("no show");
            }

            @Override
            public EditResult restore(String file, String function, List<String> entries) {
                asked.add(file + " " + function + " " + entries);
                return new EditResult.Refused("no version");
            }
        };

        EditResult result = ReviewService.undo(core, config, ReviewService.scan(config, null).getFirst());

        assertEquals(List.of("com/refbot/Miner.java mine [List.of is gone.]"), asked);
        assertEquals(new EditResult.Refused("no version"), result);
    }

    private static Path write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.sourceRoot().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toAbsolutePath().normalize();
    }

    private static ProjectConfig project(Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.sourceRoot());
        return config;
    }
}
