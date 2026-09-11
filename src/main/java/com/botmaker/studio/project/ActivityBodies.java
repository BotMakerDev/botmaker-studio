package com.botmaker.studio.project;

import com.botmaker.studio.services.BotSources;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an activity's behaviour is written — found by looking, because nothing put it anywhere.
 *
 * <p>Until 2026-08-29 an activity was a file: {@code src/main/java/com/<pkg>/activities/Mining.java}, written
 * when the activity was created and renamed when it was renamed. Studio could therefore compute the path.
 * Nothing writes a user's sources now, so an activity's body is an
 * {@code Activities.define("Mining", ctx -> …)} call in whatever file its author chose, and the only way to
 * open it is to go and find it.
 *
 * <p><b>Finding nothing is an ordinary answer, not a failure.</b> An activity with no {@code define} takes
 * its {@code DISABLED} wire and the flow runs without it — the SDK's own rule — so a caller reports "no body
 * yet" and never offers to write one. That is the whole difference between this and the
 * <em>Recover Project Files</em> the old path could suggest.
 */
public final class ActivityBodies {

    private ActivityBodies() {}

    /**
     * The file holding {@code Activities.define("<activity>", …)}, or {@code null} when nothing declares it.
     *
     * <p>Matched on the text rather than on a syntax tree, and the tolerance is deliberate: the method name
     * alone, so a {@code static import} of {@code define} is found too, with any spacing between the call and
     * its name. What it costs is that the same call inside a comment counts — which opens the file the user
     * was looking for anyway.
     */
    public static Path find(ProjectConfig config, ProjectState state, String activity) {
        if (config == null || activity == null || activity.isBlank()) return null;
        Pattern call = Pattern.compile("\\bdefine\\s*\\(\\s*\"" + Pattern.quote(activity) + "\"");
        return BotSources.firstMatch(config, state, (file, source) -> {
            Matcher found = call.matcher(source);
            return found.find();
        });
    }

    /** Any {@code define("…")} the bot's own source declares, whatever the name inside the quotes. */
    private static final Pattern ANY_DEFINE = Pattern.compile("\\bdefine\\s*\\(\\s*\"([^\"\\\\]*)\"");

    /**
     * Every activity this bot defines, in the order the walk meets them, each name once.
     *
     * <p><b>The source is the answer, and since 2026-09-11 it is the only one Studio has.</b> The activity
     * list used to come from {@code activities.json} through {@code ActivityService} — one plugin's file,
     * parsed by the host, which is exactly what the parameter surface exists to stop. What the editor
     * actually needs the names for is authoring: which body the overlay inserts into, and which string a
     * {@code Activity.enable("…")} slot should offer. Both questions are about code the user has written, so
     * the code is where they are asked.
     *
     * <p>It answers a subset of the flow's activities and that is the honest one: an activity declared in the
     * flow with no {@code define} has no body to author into, and one written by hand but never wired still
     * has. A name is offered here exactly when there is something behind it.
     *
     * <p>Matched with {@link #find}'s tolerance and its cost: the method name alone, so a {@code static
     * import} counts, and a call inside a comment counts too. A name containing an escape is skipped rather
     * than guessed at — an activity name is an identifier-ish string in every project that exists.
     */
    public static List<String> names(ProjectConfig config, ProjectState state) {
        if (config == null) return List.of();
        Set<String> found = new LinkedHashSet<>();
        BotSources.scan(config, state, (file, source) -> {
            Matcher calls = ANY_DEFINE.matcher(source);
            while (calls.find()) {
                String name = calls.group(1);
                if (!name.isBlank()) found.add(name);
            }
        });
        return List.copyOf(found);
    }
}
