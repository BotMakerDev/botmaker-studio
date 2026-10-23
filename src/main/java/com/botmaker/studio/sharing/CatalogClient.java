package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubClient;
import com.botmaker.shared.github.GitHubConfig;
import com.botmaker.shared.tools.UserDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Reads {@code catalog.json} (2026-10-23): one unauthenticated request to the raw CDN — no API rate limit, no
 * account — answered once per session.
 *
 * <p>The last catalog read is kept on disk and answers when GitHub does not, so Studio offline still knows
 * which plugins are listed and how they rank. A request that fails is not remembered: the next reader asks
 * again. Nothing here throws; no catalog at all is {@link CatalogIndex#EMPTY}.
 */
public final class CatalogClient {

    private static final CatalogClient SESSION = new CatalogClient(new GitHubClient(), GitHubConfig.catalogRawUrls(),
            UserDirs.cache().resolve("catalog.json"));

    private final GitHubClient client;
    private final List<String> urls;
    private final Path cacheFile;
    /** The answer every reader of this session shares; null until one is asked, and again after one failed. */
    private CompletableFuture<CatalogIndex> asked;

    CatalogClient(GitHubClient client, List<String> urls, Path cacheFile) {
        this.client = client;
        this.urls = List.copyOf(urls);
        this.cacheFile = cacheFile;
    }

    /** The client every Studio reader shares, so a session sends one request. */
    public static CatalogClient session() {
        return SESSION;
    }

    /** The catalog: GitHub's copy, else the one kept from the last read, else {@link CatalogIndex#EMPTY}. */
    public synchronized CompletableFuture<CatalogIndex> fetch() {
        if (asked != null) return asked;
        CompletableFuture<CatalogIndex> answer = new CompletableFuture<>();
        asked = answer;
        client.getFirstString(urls).exceptionally(failure -> null).thenAccept(body -> {
            // Whatever happens below, the answer completes: every reader of the session waits on it.
            try {
                Optional<CatalogIndex> index = CatalogIndex.read(body);
                if (index.isPresent()) {
                    keep(body);
                    answer.complete(index.get());
                } else {
                    forget(answer);
                    answer.complete(kept());
                }
            } catch (RuntimeException e) {
                forget(answer);
                answer.complete(CatalogIndex.EMPTY);
            }
        });
        return answer;
    }

    private synchronized void forget(CompletableFuture<CatalogIndex> failed) {
        if (asked == failed) asked = null;
    }

    /**
     * The catalog kept on disk from the last read, {@link CatalogIndex#EMPTY} when there is none. Blocking, and
     * quick: what New Project shows before GitHub answers.
     */
    public CatalogIndex kept() {
        try {
            return Files.isRegularFile(cacheFile) ? CatalogIndex.parse(Files.readString(cacheFile)) : CatalogIndex.EMPTY;
        } catch (IOException | RuntimeException e) {
            return CatalogIndex.EMPTY;
        }
    }

    /** Best effort: a catalog that cannot be kept is read from GitHub again next session. */
    private void keep(String body) {
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            Path partial = cacheFile.resolveSibling(cacheFile.getFileName() + ".part");
            Files.writeString(partial, body);
            Files.move(partial, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("Could not keep catalog.json: " + e.getMessage());
        }
    }
}
