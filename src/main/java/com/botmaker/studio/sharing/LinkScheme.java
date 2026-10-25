package com.botmaker.studio.sharing;

import com.botmaker.shared.platform.Os;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The {@code botmaker://} scheme on Windows (2026-10-25): written to the user's own classes
 * ({@code HKCU\Software\Classes\botmaker}) by the installed Studio as it starts, so a clicked link runs
 * {@code "<launcher>" "%1"} ({@link BotLink}). The msi takes no registry key of ours without a fork of jpackage's
 * whole WiX project, so the first start does it instead, and each start keeps it pointing at this install. The deb,
 * rpm and dmg register the scheme themselves (the menu entry's {@code MimeType}, the bundle's
 * {@code CFBundleURLTypes}).
 */
public final class LinkScheme {

    /** The registry key under {@code HKCU}. */
    static final String KEY = "Software\\Classes\\" + BotLink.SCHEME;

    private LinkScheme() {}

    /** The command a clicked link runs: the launcher, with the link as its one argument. */
    static String command(String launcher) {
        return "\"" + launcher + "\" \"%1\"";
    }

    /**
     * The PowerShell that writes the key. The values cross as environment variables ({@link #values}), never spliced
     * into the script, as {@link ProgramEntries} does for a shortcut.
     */
    static String script() {
        return "$k = 'HKCU:\\" + KEY + "'; "
                + "New-Item -Path ($k + '\\shell\\open\\command') -Force | Out-Null; "
                + "New-Item -Path ($k + '\\DefaultIcon') -Force | Out-Null; "
                + "Set-Item -Path $k -Value 'URL:BotMaker bot'; "
                + "New-ItemProperty -Path $k -Name 'URL Protocol' -Value '' -Force | Out-Null; "
                + "Set-Item -Path ($k + '\\DefaultIcon') -Value $env:BMK_ICON; "
                + "Set-Item -Path ($k + '\\shell\\open\\command') -Value $env:BMK_COMMAND";
    }

    /**
     * {@link #script} as PowerShell's {@code -EncodedCommand} takes it, base64 of its UTF-16LE: no quote in it can be
     * lost to Windows' command-line parsing.
     */
    static String encoded() {
        return Base64.getEncoder().encodeToString(script().getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * Windows PowerShell by its full path under {@code %SystemRoot%}: a bare {@code powershell} is looked up in the
     * launcher's own folder and the working directory first, where a planted one would run instead. Empty when
     * {@code SystemRoot} is not set.
     */
    static Optional<String> powershell(Map<String, String> env) {
        return Optional.ofNullable(env.get("SystemRoot")).filter(s -> !s.isBlank())
                .map(s -> Path.of(s, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString());
    }

    /** The script's environment for {@code launcher}. */
    static Map<String, String> values(String launcher) {
        return Map.of("BMK_COMMAND", command(launcher), "BMK_ICON", launcher + ",0");
    }

    /**
     * Writes the key for the Studio installed at {@code jpackage.app-path}, off the caller's thread; nothing off
     * Windows or on a development run, which has no launcher to name.
     */
    public static void registerOnWindows() {
        String launcher = System.getProperty("jpackage.app-path");
        if (!Os.current().isWindows() || launcher == null || launcher.isBlank()) return;
        Optional<String> exe = powershell(System.getenv());
        if (exe.isEmpty()) return;
        Thread write = new Thread(() -> {
            try {
                ProcessBuilder powershell = new ProcessBuilder(exe.get(), "-NoProfile", "-NonInteractive",
                        "-EncodedCommand", encoded());
                powershell.environment().putAll(values(launcher));
                Process p = powershell.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly();
                else if (p.exitValue() != 0) System.err.println("Could not register botmaker:// links.");
            } catch (IOException e) {
                System.err.println("Could not register botmaker:// links: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "link-scheme");
        write.setDaemon(true);
        write.start();
    }
}
