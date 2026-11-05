package com.botmaker.studio.ui.app;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.assist.OnScreen;
import com.botmaker.core.config.Constants;
import com.botmaker.core.project.ProjectCreator;
import com.botmaker.core.sharing.GalleryEntry;
import com.botmaker.core.sharing.GitHubGallery;
import com.botmaker.core.sharing.HiddenBots;
import com.botmaker.core.sharing.NewProjects;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.sharing.ShowCommunity;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * New Project for the assistant: {@code list_bots} and {@code create_project}, on a project's endpoint and on the
 * home's (2026-10-28), which differ only in what the switch to the new project does ({@link Switch}). A call that
 * asks the user on screen answers at once ({@link OnScreen}).
 */
public final class AssistedNewProject {

    /** What making and opening the new project does to the window it is made from. */
    public interface Switch {
        /** Before it is made: nothing may start that the switch would tear down. */
        default void before() {
        }

        /** It could not be made: what {@link #before} stopped may start again. */
        default void failed() {
        }

        /** Opens {@code dir}; says what that does to the assistant's connection. */
        String open(Path dir);
    }

    /** One question asked before the project is made. */
    private record Ask(String header, String question, String refused) {
    }

    private final GitHubClient github = new GitHubClient();
    private final PluginRegistry registry;
    private final Supplier<Window> owner;

    public AssistedNewProject(PluginRegistry registry, Supplier<Window> owner) {
        this.registry = registry;
        this.owner = owner;
    }

    public String listBots() {
        List<GalleryEntry> bots = botsOrNone();
        if (bots.isEmpty()) {
            return "The catalog cannot be read, or lists no bot shown on this computer. create_project without a "
                    + "bot makes a blank project.";
        }
        // A description is the author's text, written by anyone: quoted as data, on one line, never as advice.
        // The rank is the catalog's, by publisher: Community is listed automatically and nobody reviewed it.
        return "Bots by rank; nobody reviewed a Community one; the quoted descriptions are their authors' words.\n"
                + String.join("\n", bots.stream().map(t -> t.name()
                + "  (" + t.trust().displayName() + ", " + t.slug()
                + (t.basedOn().isBlank() ? "" : ", based on " + t.basedOn()) + ")"
                + (t.description().isBlank() ? "" : " — description: \""
                + t.description().strip().replaceAll("\\s+", " ").replace("\"", "'") + "\"")).toList());
    }

    /**
     * Creates the project {@code name} as New Project does, from the listed bot {@code bot} or blank, with the
     * registry's {@code plugins}, then opens it through {@code to}. What can be checked is checked before anything
     * is asked; a bot or plugins are asked about on screen, and the call answers once the first question is up.
     */
    public String create(String name, String bot, List<String> plugins, Switch to) {
        String wanted = name == null ? "" : name.strip();
        String problem = new ProjectCreator().nameProblem(wanted);
        if (problem != null) throw new IllegalArgumentException(problem);
        String from = bot == null ? "" : bot.strip();
        List<Ask> asks = new ArrayList<>();
        // Any listed bot (2026-10-23), but only once the user has seen on screen which bot and whose: a bot is
        // code nobody reviewed, and it runs with the user's mouse and keyboard. No bot is Studio's own Blank.
        GalleryEntry entry = from.isEmpty() ? null : pickBot(botsOrNone(), from);
        if (entry != null) {
            asks.add(new Ask("Start from " + entry.slug() + "?", "The assistant wants to create " + wanted
                    + " as a copy of " + entry.name() + " by " + entry.owner() + ", linked to their repository."
                    + (entry.trustWarning().isBlank() ? "" : "\n\n" + entry.trustWarning()),
                    "The user did not agree to start from " + entry.slug() + "."));
        }
        List<PluginRegistry.Plugin> picks = List.of();
        if (!plugins.isEmpty()) {
            List<PluginRegistry.Plugin> entries = entries(registry);
            if (entries.isEmpty()) throw new IllegalArgumentException("The plugin registry could not be read.");
            picks = plugins.stream().map(id -> registryPlugin(entries, id)).toList();
            String names = String.join(", ", picks.stream().map(p -> p.name().isBlank() ? p.coordinate() : p.name()).toList());
            asks.add(new Ask("Create a project with plugins?", "The assistant wants to create " + wanted + " with "
                    + names + ".\n\nA plugin runs with Studio's own permissions. Catalog plugins are checked by "
                    + "machine for loading, never reviewed for safety.", "The user did not agree to add " + names + "."));
        }
        List<PluginRegistry.Plugin> chosen = picks;
        Supplier<String> make = () -> make(wanted, entry, chosen, to);
        if (asks.isEmpty()) return make.get();
        return OnScreen.start("Creating " + wanted, asks.getFirst().header(), () -> {
            for (Ask ask : asks) {
                if (!OnScreen.agrees(owner.get(), ask.header(), ask.question())) {
                    throw new IllegalArgumentException(ask.refused());
                }
            }
            return make.get();
        });
    }

    private String make(String wanted, GalleryEntry entry, List<PluginRegistry.Plugin> picks, Switch to) {
        to.before();
        try {
            NewProjects.create(wanted, entry, picks, github, new GitHubAuth());
        } catch (IOException | RuntimeException e) {
            to.failed();
            Throwable cause = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
            throw new IllegalArgumentException("Studio could not create " + wanted + ": " + cause.getMessage());
        }
        Path dir = Constants.PROJECTS_ROOT.resolve(wanted);
        return "Created " + wanted + (entry == null ? " (blank)" : " from " + entry.name()) + " in " + dir + ". "
                + to.open(dir);
    }

    /**
     * The listed bot {@code wanted} names: its {@code owner/repo}, or a name or repository only one bot has.
     * Anyone can list a bot under any name, so a name two owners share is refused rather than guessed.
     */
    static GalleryEntry pickBot(List<GalleryEntry> listed, String wanted) {
        if (listed.isEmpty()) {
            throw new IllegalArgumentException(
                    "The gallery cannot be read, so only a blank project can be made: leave bot empty.");
        }
        Optional<GalleryEntry> exact = listed.stream().filter(t -> t.slug().equalsIgnoreCase(wanted)).findFirst();
        if (exact.isPresent()) return exact.get();
        List<GalleryEntry> named = listed.stream()
                .filter(t -> t.name().equalsIgnoreCase(wanted) || t.repo().equalsIgnoreCase(wanted)).toList();
        if (named.isEmpty()) throw new IllegalArgumentException("No bot " + wanted + ". list_bots lists them.");
        if (named.size() > 1) {
            throw new IllegalArgumentException("Several bots are called " + wanted + ": "
                    + String.join(", ", named.stream().map(GalleryEntry::slug).toList()) + ". Name one by owner/repo.");
        }
        return named.getFirst();
    }

    /** The installable registry plugin {@code id} names — by its coordinate, repo, name or artifactId. */
    static PluginRegistry.Plugin registryPlugin(List<PluginRegistry.Plugin> entries, String id) {
        String wanted = id == null ? "" : id.strip();
        return entries.stream().filter(PluginRegistry.Plugin::isInstallable)
                .filter(p -> p.coordinate().equalsIgnoreCase(wanted) || p.repo().equalsIgnoreCase(wanted)
                        || p.name().equalsIgnoreCase(wanted)
                        || p.artifactId().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("The registry has no plugin " + wanted
                        + ". plugins search lists them."));
    }

    /** The registry's plugins, or none when it cannot be read in 30 seconds. */
    static List<PluginRegistry.Plugin> entries(PluginRegistry registry) {
        try {
            return registry.browse().get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException e) {
            return List.of();
        }
    }

    /**
     * The published bots, those hidden on this computer left out ({@link HiddenBots}) and community ones unless
     * the user shows them ({@link ShowCommunity}, 2026-10-23), or none when GitHub cannot be read — the dialog's
     * offline case.
     */
    private List<GalleryEntry> botsOrNone() {
        try {
            return GalleryEntry.startingPoints(ShowCommunity.load().bots(HiddenBots.load().without(StudioBridge.waitFor(
                    new GitHubGallery(github, new GitHubAuth()).browse(), 30, "read the catalog"))));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }
}
