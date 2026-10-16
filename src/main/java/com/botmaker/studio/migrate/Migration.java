package com.botmaker.studio.migrate;

import com.botmaker.studio.project.source.BotIndex;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bot between two APIs: as it compiles now, and as it compiled with the jars it used to have — what every
 * question of the author's migration is asked of (2026-10-16, {@code docs/refactor/25-compatibility.md} §12).
 *
 * <p><b>One engine.</b> The bot's {@code @Migrate} rules ({@link Rules}) name an old method, constructor, field or
 * type by their own signature; {@link #broken()} lists what the new jars break and what each rule covers;
 * {@link #plan} rewrites every use a ready rule names. The author decides what replaces what: with no rule,
 * nothing is rewritten.
 *
 * <p>Pure: two parses in, answers out. The old classpath is the pinned version's jars on an upgrade, or the jars
 * {@link ApiCache} kept from the last load on a reload of the same {@code -SNAPSHOT}.
 */
public final class Migration {

    private final BotIndex now;
    private final BotIndex old;
    private final Path probe;
    private final List<Rule> rules;

    private Migration(BotIndex now, BotIndex old, Path probe, List<Rule> rules) {
        this.now = now;
        this.old = old;
        this.probe = probe;
        this.rules = rules;
    }

    /** {@code now}, and its sources parsed against {@code oldClasspath}, with the bot's rules resolved. */
    public static Migration of(BotIndex now, List<String> oldClasspath) {
        List<Rule> declared = Rules.declared(now);
        Path root = now.parser().sourceRoot() == null ? null : now.parser().sourceRoot().toAbsolutePath().normalize();
        Path probe = Rules.probePath(root);
        BotIndex old = now.against(oldClasspath, Rules.probe(declared, root));
        return new Migration(now, old, probe, Rules.resolved(declared, old, probe));
    }

    /** Every {@code @Migrate} rule in the bot, each resolved or saying why not. */
    public List<Rule> rules() {
        return rules;
    }

    /** Every old element the bot still uses that the new jars break or a rule names. */
    public List<Broken> broken() {
        return Broken.in(now, old, probe, rules);
    }

    /** Every use of {@code element} in the bot. */
    public List<Sites.Site> sites(OldElement element) {
        Map<String, OldElement> one = new LinkedHashMap<>();
        one.put(element.key(), element);
        return Sites.in(old, one, probe);
    }

    /**
     * Every use a ready rule names, rewritten, checked to compile; a use left as written is listed and, when
     * {@code markable}, its function marked {@code @Refactor}.
     */
    public Rewrite.Result plan(boolean markable) {
        return Rewrite.plan(now, old, probe, rules, markable);
    }
}
