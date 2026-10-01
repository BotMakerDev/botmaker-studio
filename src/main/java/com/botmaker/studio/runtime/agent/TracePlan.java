package com.botmaker.studio.runtime.agent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the trace agent rewrites, read from the file Studio names on the agent's command line: where the bot's own
 * classes are, which plugin classes a call into is traced, and which classes and methods the user hid.
 *
 * <p>A file rather than the agent argument itself: a project's offered classes run to dozens of names, and a
 * command line is shown in process lists and has a length limit. The format is a {@link Properties} file
 * ({@code classes}, {@code traced}, {@code hidden}), so a path with any character in it is escaped for us.
 *
 * @param botClasses the bot's class directories (or jars); only classes loaded from one of them are rewritten
 * @param traced     binary names of the plugin classes a call into is traced
 * @param hidden     keys the Trace tab hides: a class's binary name, or {@code class#method}
 */
public record TracePlan(Set<Path> botClasses, Set<String> traced, Set<String> hidden) {

    private static final String CLASSES = "classes";
    private static final String TRACED = "traced";
    private static final String HIDDEN = "hidden";
    private static final String SEPARATOR = ",";

    public TracePlan {
        botClasses = botClasses.stream().map(p -> p.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        traced = Set.copyOf(traced);
        hidden = Set.copyOf(hidden);
    }

    /** Whether a call to {@code method} of {@code className} is traced: the class is, and neither it nor the method is hidden. */
    public boolean traces(String className, String method) {
        return traced.contains(className) && !hidden.contains(className)
                && !hidden.contains(className + "#" + method);
    }

    /** Whether a class from {@code location} is the bot's own, so its calls are rewritten. */
    public boolean isBotClass(URL location) {
        if (location == null) return false;
        try {
            return botClasses.contains(Path.of(location.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
            return false;
        }
    }

    public void write(Path file) throws IOException {
        Properties properties = new Properties();
        properties.setProperty(CLASSES, botClasses.stream().map(Path::toString)
                .collect(Collectors.joining(java.io.File.pathSeparator)));
        properties.setProperty(TRACED, String.join(SEPARATOR, traced));
        properties.setProperty(HIDDEN, String.join(SEPARATOR, hidden));
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(out, "BotMaker trace agent: the calls Studio traces in this run");
        }
    }

    public static TracePlan read(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        Set<Path> classes = new LinkedHashSet<>();
        for (String path : split(properties.getProperty(CLASSES, ""), java.io.File.pathSeparator)) {
            classes.add(Path.of(path));
        }
        return new TracePlan(classes, split(properties.getProperty(TRACED, ""), SEPARATOR),
                split(properties.getProperty(HIDDEN, ""), SEPARATOR));
    }

    private static Set<String> split(String joined, String separator) {
        return Arrays.stream(joined.split(java.util.regex.Pattern.quote(separator)))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A plan over {@code botClasses} and {@code traced}, nothing hidden. */
    public static TracePlan of(Collection<Path> botClasses, Collection<String> traced) {
        return new TracePlan(Set.copyOf(botClasses), Set.copyOf(traced), Set.of());
    }
}
