package com.botmaker.studio.sharing;

/**
 * The plugins a published bot brings.
 *
 * <p>Since 2026-10-23 the catalog says them ({@link GalleryEntry#plugins}): its CI reads the bot's pom at the
 * listed tag, as data, once for every reader. Studio read that pom itself from 2026-10-12, one request per row
 * shown, after a {@code requires} list written at publish time had said a bot needed SDK 1.2.3 while the release
 * New Project copied pinned 1.4.4.
 */
public final class PublishedBotPlugins {

    private PublishedBotPlugins() {
    }

    /** A plugin a bot's pom declares: its catalog id and the version the pom pins, which may be blank. */
    public record Pin(String id, String version) {
        public Pin {
            id = id == null ? "" : id.trim();
            version = version == null ? "" : version.trim();
        }
    }
}
