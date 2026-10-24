package com.botmaker.studio.sharing;

import com.botmaker.cli.catalog.BotChecks;
import com.botmaker.cli.catalog.EntryGate;
import com.botmaker.cli.catalog.EntryPath;
import com.botmaker.cli.catalog.Finding;
import com.botmaker.cli.publish.CatalogPr;
import com.botmaker.cli.publish.Checks;
import com.botmaker.cli.publish.Listener;
import com.botmaker.cli.publish.PublishTarget;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The checks Studio's Publish runs on the saved version before it is tagged (2026-10-24): the catalog's rule 6 on the
 * pom (a catalog plugin, each at a released version), and rule 8 (a class holds {@code main}) on Studio's own build in
 * {@code target/classes}. Rule 7, the compile, is Studio's own and the catalog's: Studio resolves with Maven Resolver,
 * not a {@code mvn} it could shell to, and the catalog's check compiles the tag anyway. Studio publishes bots only.
 */
final class StudioChecks implements Checks {

    private final CatalogPr.Reader catalog;
    private final Consumer<PublishPlan.Step> begin;

    StudioChecks(CatalogPr.Reader catalog, Consumer<PublishPlan.Step> begin) {
        this.catalog = catalog;
        this.begin = begin;
    }

    @Override
    public Result folder(PublishTarget target, Listener listener) {
        begin.accept(PublishPlan.Step.CHECK);
        String path = EntryPath.bot(target.repo());
        try {
            List<Finding> findings = BotChecks.checkPom(target.dir().resolve("pom.xml"), path,
                    EntryGate.pluginIds(catalog.catalog()));
            if (!Finding.refused(findings)) {
                Path classes = target.dir().resolve("target").resolve("classes");
                if (Files.isDirectory(classes)) {
                    findings = BotChecks.checkClasses(classes, path);
                } else {
                    listener.warn("Not built on this computer yet, so the class holding main was not looked for; the"
                            + " catalog's check builds it.");
                }
            }
            findings.stream().filter(f -> f.severity() != Finding.Severity.ERROR).forEach(f -> listener.warn(f.message()));
            List<String> errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR)
                    .map(Finding::message).toList();
            return errors.isEmpty() ? Result.passed(Optional.empty()) : Result.refused(String.join("\n", errors));
        } catch (IOException e) {
            return Result.refused(e.getMessage());
        }
    }

    @Override
    public Result published(String coordinate, Listener listener) {
        return Result.refused("Studio publishes bots; publish a plugin with `botmaker plugin publish` in its folder.");
    }
}
