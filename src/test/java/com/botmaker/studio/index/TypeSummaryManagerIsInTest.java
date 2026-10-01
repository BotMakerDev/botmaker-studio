package com.botmaker.studio.index;

import io.github.classgraph.ClassInfo;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An index loaded from its JSON cache holds classes ClassGraph knows no classpath element for, and asking one
 * for its file throws. Every call block's class dropdown asked, so a project opened a second time drew its
 * calls as nothing (2026-10-01).
 */
class TypeSummaryManagerIsInTest {

    @Test
    void aCachedClassIsFoundInItsJarWithoutAskingClassGraphForTheFile() throws Exception {
        String jar = Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        TypeSummaryManager.buildOrLoad(List.of(jar));                       // scans, and writes the cache
        TypeSummaryManager cached = TypeSummaryManager.buildOrLoad(List.of(jar));  // reads it back

        ClassInfo test = cached.getTypesForJar(jar).stream()
                .filter(c -> c.getName().equals(Test.class.getName())).findFirst().orElseThrow();

        assertTrue(cached.isIn(test, Set.of(Path.of(jar))));
        assertFalse(cached.isIn(test, Set.of(Path.of("/tmp/some-plugin.jar"))));
    }
}
