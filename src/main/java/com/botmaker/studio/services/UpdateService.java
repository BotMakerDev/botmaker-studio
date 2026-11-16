// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.services;

import com.botmaker.shared.Executables;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.github.SemVer;
import com.botmaker.shared.platform.Os;
import com.botmaker.core.config.AppVersion;
import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/**
 * Checks GitHub Releases for a newer BotMaker Studio and, when the user opts in, downloads the installer that
 * matches the host OS ({@code .msi} on Windows, {@code .deb}/{@code .rpm} on Linux, {@code .dmg} on macOS) and hands it to the OS
 * installer. All network work is async and best-effort; failures resolve to "no update" rather than throwing.
 *
 * <p>Reuses {@link GitHubClient} for the release JSON and {@link SemVer#isGreater} for the version comparison.
 * The binary download uses a redirect-following {@link HttpClient} because release-asset URLs 302 to a CDN.
 */
public final class UpdateService {

    /**
     * A newer release plus the installer asset chosen for the current OS; {@code checksumsUrl} is the release's
     * {@link InstallerChecksum#FILE_NAME}, blank when it has none (the download is then refused).
     */
    public record AvailableUpdate(String tag, String assetName, String downloadUrl, String checksumsUrl) {}

    private final GitHubClient client;
    private final HttpClient downloader = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public UpdateService() {
        this(new GitHubClient());
    }

    public UpdateService(GitHubClient client) {
        this.client = client;
    }

    /**
     * Resolves to the newer release (with a matching installer for this OS) if one exists, else empty.
     * Empty also covers "up to date", "no installer for this OS", and any network/parse failure.
     */
    public CompletableFuture<Optional<AvailableUpdate>> checkForUpdate() {
        String url = GitHubConfig.API_BASE + "/repos/"
                + GitHubConfig.STUDIO_OWNER + "/" + GitHubConfig.STUDIO_REPO + "/releases/latest";
        return client.get(url, null).thenApply(node -> {
            if (node == null) return Optional.<AvailableUpdate>empty();
            String tag = node.path("tag_name").asText("");
            if (!SemVer.isGreater(tag, AppVersion.get())) return Optional.<AvailableUpdate>empty();
            String checksums = assetUrl(node, InstallerChecksum.FILE_NAME).orElse("");
            return pickAsset(node).map(a -> new AvailableUpdate(tag, a.name, a.url, checksums));
        }).exceptionally(e -> Optional.empty());
    }

    /**
     * Downloads the installer to a temp file and returns its path, reporting fractional progress in
     * {@code [0.0, 1.0]} to {@code progress} as bytes arrive (or {@code -1.0} when the server sends no
     * {@code Content-Length}, i.e. progress is indeterminate). {@code progress} is invoked on the
     * HTTP client's executor thread — the caller is responsible for marshalling to the UI thread.
     * The file is then checked against the release's checksums ({@link InstallerChecksum}): one that differs, or
     * that the release lists no checksum for, is deleted and fails the future. Fails it on any download error too.
     */
    public CompletableFuture<Path> downloadInstaller(AvailableUpdate update, DoubleConsumer progress) {
        if (update.checksumsUrl() == null || update.checksumsUrl().isBlank()) {
            // Refused before a byte is fetched: nothing could check it.
            return CompletableFuture.failedFuture(new IOException("Release " + update.tag() + " has no "
                    + InstallerChecksum.FILE_NAME + ", so its installer cannot be checked. Nothing was downloaded."));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(update.downloadUrl()))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/octet-stream")
                .GET().build();
        CompletableFuture<Path> downloaded = downloader.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Download failed (HTTP " + resp.statusCode() + ")");
                    }
                    long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
                    Path dir = null;
                    try {
                        dir = Files.createTempDirectory("botmaker-update");
                        Path file = dir.resolve(update.assetName());
                        streamToFile(resp.body(), file, total, progress);
                        return file;
                    } catch (IOException e) {
                        deleteDownload(dir, update.assetName());
                        throw new RuntimeException("Could not save installer: " + e.getMessage(), e);
                    }
                });
        return downloaded.thenCombine(fetchChecksums(update.checksumsUrl()), (file, checksums) -> {
            try {
                if (checksums.isEmpty()) {
                    throw new IOException("Could not fetch the release's " + InstallerChecksum.FILE_NAME
                            + " to check the installer; try again later.");
                }
                InstallerChecksum.verify(file, update.assetName(), checksums.get());
                return file;
            } catch (IOException e) {
                deleteDownload(file.getParent(), update.assetName());
                throw new RuntimeException(e.getMessage(), e);
            }
        });
    }

    /** Deletes a refused or partial download and its temp folder, best-effort. */
    private static void deleteDownload(Path dir, String assetName) {
        if (dir == null) return;
        try {
            Files.deleteIfExists(dir.resolve(assetName));
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // left in the temp folder, never installed
        }
    }

    /** The release's checksums text, empty when it could not be fetched (the download is then refused). */
    private CompletableFuture<Optional<String>> fetchChecksums(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(1))
                .header("Accept", "application/octet-stream")
                .GET().build();
        return downloader.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() == 200 ? Optional.of(resp.body()) : Optional.<String>empty())
                .exceptionally(e -> Optional.empty());
    }

    /** Copies {@code in} to {@code file}, reporting {@code read/total} (or {@code -1.0} when {@code total<=0}). */
    private static void streamToFile(InputStream in, Path file, long total, DoubleConsumer progress)
            throws IOException {
        try (InputStream src = in;
             OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            progress.accept(total > 0 ? 0.0 : -1.0);
            while ((n = src.read(buf)) != -1) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0) progress.accept((double) read / total);
            }
            if (total > 0) progress.accept(1.0);
        }
    }

    /**
     * Installs (not merely opens) {@code installer}, best-effort across platforms:
     * <ul>
     *   <li><b>Windows</b> — launches the {@code .msi} via {@code msiexec}.</li>
     *   <li><b>Linux AppImage</b> — when the app is itself running as an AppImage ({@code $APPIMAGE} set),
     *       the freshly downloaded {@code .AppImage} replaces the running file in place (no root, no
     *       package DB, no stale desktop entry). The user restarts to run the new version.</li>
     *   <li><b>Linux .rpm/.deb</b> — installs through the native package manager under a single {@code pkexec}
     *       (polkit) prompt, so the user no longer has to finish the install manually in the software store.</li>
     *   <li>Otherwise falls back to handing the file to the desktop's default handler — on macOS that mounts
     *       the {@code .dmg} in Finder.</li>
     * </ul>
     */
    public void launchInstaller(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        if (isWindows()) {
            new ProcessBuilder("msiexec", "/i", file).start();
            return;
        }
        String lower = file.toLowerCase(Locale.ROOT);
        String runningAppImage = System.getenv("APPIMAGE");
        if (lower.endsWith(".appimage") && runningAppImage != null && !runningAppImage.isBlank()) {
            applyAppImageUpdate(installer, Path.of(runningAppImage));
            return;
        }
        if ((lower.endsWith(".rpm") || lower.endsWith(".deb")) && Executables.onPath("pkexec")) {
            installWithPackageManager(installer);
            return;
        }
        // Fallback: let the desktop's default handler (software installer / archive tool) take it, with an
        // xdg-open fallback for headless AWT.
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(installer.toFile());
        } else {
            new ProcessBuilder(Os.current().openCommand(file)).start();
        }
    }

    /** What the user does after {@link #launchInstaller} to be on the new version. */
    public static String finishInstructions() {
        return Os.current().isMac()
                ? "Drag BotMaker Studio from the opened disk image onto Applications, replacing the old one, "
                        + "then quit and reopen BotMaker Studio."
                : "Please quit BotMaker Studio and reopen it to finish updating.";
    }

    /** Replaces the running AppImage file in place; the OS keeps the already-mapped copy running until restart. */
    private static void applyAppImageUpdate(Path downloaded, Path current) throws IOException {
        Files.copy(downloaded, current, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        current.toFile().setExecutable(true, false);
    }

    /**
     * Installs a {@code .rpm}/{@code .deb} through the native package manager under one polkit prompt, falling
     * back to the low-level tool. This runs the actual system install (previously the user had to complete it
     * by hand in the software store). Blocks until the install finishes.
     */
    private static void installWithPackageManager(Path installer) throws IOException {
        String file = installer.toAbsolutePath().toString();
        boolean rpm = file.toLowerCase(Locale.ROOT).endsWith(".rpm");
        String cmd = rpm
                ? "dnf install -y '" + file + "' || rpm -U --replacepkgs '" + file + "'"
                : "apt-get install -y '" + file + "' || dpkg -i '" + file + "'";
        try {
            Process p = new ProcessBuilder("pkexec", "sh", "-c", cmd).inheritIO().start();
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("Package install exited with code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Install was interrupted", e);
        }
    }

    private record Asset(String name, String url) {}

    /** The download address of the asset named exactly {@code name}, if the release has one. */
    private static Optional<String> assetUrl(JsonNode releaseNode, String name) {
        JsonNode assets = releaseNode.get("assets");
        if (assets == null || !assets.isArray()) return Optional.empty();
        for (JsonNode a : assets) {
            if (a.path("name").asText("").equals(name)) {
                return Optional.of(a.path("browser_download_url").asText(""));
            }
        }
        return Optional.empty();
    }

    /** Picks the release asset whose extension matches this OS, preferring the native package manager on Linux. */
    private Optional<Asset> pickAsset(JsonNode releaseNode) {
        JsonNode assets = releaseNode.get("assets");
        if (assets == null || !assets.isArray()) return Optional.empty();

        String[] preferred = preferredExtensions();
        for (String ext : preferred) {
            for (JsonNode a : assets) {
                String name = a.path("name").asText("");
                if (name.toLowerCase(Locale.ROOT).endsWith(ext)) {
                    return Optional.of(new Asset(name, a.path("browser_download_url").asText("")));
                }
            }
        }
        return Optional.empty();
    }

    /** Installer extensions acceptable for this OS, in order of preference (lower-case; matched case-insensitively). */
    private static String[] preferredExtensions() {
        if (isWindows()) return new String[]{".msi", ".exe"};
        // The .dmg opens in Finder and the user drags the app over the old one; checked before the Linux
        // probes because Homebrew can put rpm or dpkg on a Mac's PATH. Only an Apple Silicon build is
        // released, and Rosetta does not run arm64 on Intel, so an Intel Mac is offered nothing.
        if (Os.current().isMac()) {
            return "aarch64".equals(System.getProperty("os.arch")) ? new String[]{"-macos-arm64.dmg"} : new String[0];
        }
        // Running as an AppImage → self-update by swapping the AppImage file (no root, no package DB).
        String appImage = System.getenv("APPIMAGE");
        if (appImage != null && !appImage.isBlank()) return new String[]{".appimage"};
        // Otherwise prefer the format whose tooling is installed; fall back to the other, then AppImage.
        if (Executables.onPath("dpkg")) return new String[]{".deb", ".rpm", ".appimage"};
        if (Executables.onPath("rpm")) return new String[]{".rpm", ".deb", ".appimage"};
        return new String[]{".appimage", ".deb", ".rpm"};
    }

    private static boolean isWindows() {
        return Os.current().isWindows();
    }
}
