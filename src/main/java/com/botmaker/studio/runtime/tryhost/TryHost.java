package com.botmaker.studio.runtime.tryhost;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The warm JVM a project's tries run in (2026-10-13): started once with the bot's dependencies on its classpath, it
 * runs each try's {@code main} in a class loader of its own over the bot's classes, so the bot's code is the one just
 * compiled while what the dependencies hold — the SDK's joined display among it — stays from one try to the next.
 *
 * <p>Studio writes this class into a jar of its own ({@code TryHostJar}) and talks to it over stdin, one command a
 * line: first {@code mark <word>}, then {@code run <main class>\t<class folder>\t…} starts a try and {@code stop}
 * interrupts it. A try's end is {@link #END}, the word and its code, written last to both stdout and stderr, so
 * Studio knows both pipes are drained; the word is Studio's, so a bot printing the rest cannot end a try. Stdin
 * closing (Studio gone) ends the JVM. Only the JDK: it runs on the bot's classpath, not Studio's.
 */
public final class TryHost {

    /** Starts a try's end, followed by the word and the code: 0 done, 1 failed, 130 stopped. */
    public static final String END = "\u0001botmaker-try-end ";

    private static volatile Thread running;
    private static volatile boolean stopAsked;
    private static String mark = "";

    private TryHost() {}

    public static void main(String[] args) throws IOException {
        PrintStream out = System.out;
        PrintStream err = System.err;
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("mark ")) mark = line.substring(5).trim();
            else if (line.startsWith("run ")) start(line.substring(4), out, err);
            else if (line.equals("stop")) stop();
        }
        // Studio is gone: nothing may outlive it, a try's own threads included.
        Runtime.getRuntime().halt(0);
    }

    private static void start(String command, PrintStream out, PrintStream err) {
        String[] parts = command.split("\t");
        URL[] folders;
        try {
            folders = urls(parts);
        } catch (MalformedURLException e) {
            err.println("Cannot load the try's classes: " + e.getMessage());
            ended(1, out, err);
            return;
        }
        stopAsked = false;
        URLClassLoader loader = new URLClassLoader("try", folders, TryHost.class.getClassLoader());
        Thread thread = new Thread(() -> {
            int code = 0;
            try {
                Class<?> main = Class.forName(parts[0], true, loader);
                main.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException e) {
                code = stopped(e.getCause()) ? 130 : 1;
                if (code == 1) e.getCause().printStackTrace();
            } catch (ReflectiveOperationException | LinkageError e) {
                e.printStackTrace();
                code = 1;
            } finally {
                // A stopped try that swallowed the interrupt and returned was still stopped.
                if (stopAsked && code == 0) code = 130;
                running = null;
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // The folders are read again by the next try's loader.
                }
                ended(code, out, err);
            }
        }, "try");
        thread.setContextClassLoader(loader);
        running = thread;
        thread.start();
    }

    /** Interrupts the running try; a try that ignores it is ended by Studio, which kills this JVM. */
    private static void stop() {
        Thread thread = running;
        if (thread != null) {
            stopAsked = true;
            thread.interrupt();
        }
    }

    /** A try that ended by the interrupt {@link #stop} sent, whatever it wrapped it in. */
    private static boolean stopped(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) return true;
        }
        return false;
    }

    static URL[] urls(String[] parts) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) urls.add(Path.of(parts[i]).toUri().toURL());
        }
        return urls.toArray(URL[]::new);
    }

    private static void ended(int code, PrintStream out, PrintStream err) {
        out.flush();
        err.flush();
        synchronized (TryHost.class) {
            out.println(END + mark + " " + code);
            out.flush();
            err.println(END + mark + " " + code);
            err.flush();
        }
    }
}
