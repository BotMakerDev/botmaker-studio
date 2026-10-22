package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedOpenSet;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.source.BotIndex;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A rename the author asked for of a member of a plugin's open set — a screen's constant, a picture, an activity —
 * planned as the plugin's own window would ask the host for it (the user's call, 2026-10-22).
 *
 * <p>A plugin's file is locked whole ({@code LockResolver}), and its rename used to be refused with it, so a
 * {@code Screens.java} constant kept a name the screen no longer had. A rename by binding is the one edit the host
 * already does there for the plugin ({@code PluginValues.renameGroup}, {@code rename}), so the same plan is the
 * author's to ask for, from Studio's rename or the assistant's: a class of a set of classes renamed with its
 * constant, any other member alone. Every other edit of the file stays the plugin's.
 *
 * <p>Pure: it reads the index and plans, the caller applies.
 */
public final class ManagedRenames {

    private ManagedRenames() {}

    /**
     * The rename of what the name at {@code start} in {@code file} binds to, a declaration or any use of it, to
     * {@code newName}, when that is a member of one of {@code managed}'s open sets; empty when it is none, for the
     * caller's ordinary rename.
     */
    public static Optional<Refactor.Outcome> plan(BotIndex index, Path file, int start, String newName,
                                                  List<ManagedValue<?>> managed, ValueGrammar grammar) {
        Optional<String> key = Refactor.keyAt(index, file, start);
        if (key.isEmpty()) return Optional.empty();
        for (ManagedValue<?> value : managed) {
            if (!(value instanceof ManagedOpenSet<?> set)) continue;
            if (set instanceof ManagedClasses<?>) {
                for (NestedClasses.Nested nested : NestedClasses.all(index, set.id(), grammar, null)) {
                    if (binds(index, nested.file(), nested.constantStart(), key.get())) {
                        return Optional.of(NestedClasses.rename(index, nested, newName));
                    }
                    if (binds(index, nested.file(), nested.classStart(), key.get())) {
                        return Optional.of(Refactor.rename(index, nested.file(), nested.classStart(), newName));
                    }
                    Optional<Refactor.Outcome> field = member(index, nested.fields(), key.get(), newName);
                    if (field.isPresent()) return field;
                }
                continue;
            }
            Optional<Refactor.Outcome> constant = member(index, ManagedSets.members(index, set.id(), grammar),
                    key.get(), newName);
            if (constant.isPresent()) return constant;
            for (ManagedSets.Group group : ManagedSets.groups(index, set.id())) {
                if (binds(index, group.file(), group.start(), key.get())) {
                    return Optional.of(Refactor.rename(index, group.file(), group.start(), newName));
                }
            }
        }
        return Optional.empty();
    }

    /** The rename of whichever of {@code members} is {@code key}; empty when none is. */
    private static Optional<Refactor.Outcome> member(BotIndex index, List<ManagedSets.Member> members, String key,
                                                     String newName) {
        for (ManagedSets.Member member : members) {
            if (binds(index, member.file(), member.start(), key)) {
                return Optional.of(Refactor.rename(index, member.file(), member.start(), newName));
            }
        }
        return Optional.empty();
    }

    private static boolean binds(BotIndex index, Path file, int start, String key) {
        return start >= 0 && Refactor.keyAt(index, file, start).filter(key::equals).isPresent();
    }
}
