// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks a downloaded installer against its release's {@code checksums_sha256.txt} (2026-11-17): lines of
 * {@code <sha256 hex>  <asset name>}, as the release writes them. Nothing is installed unchecked: a file whose
 * sha256 differs, or that the release lists no checksum for, is deleted and refused with why.
 */
public final class InstallerChecksum {

    /** The release asset that lists every other asset's sha256. */
    public static final String FILE_NAME = "checksums_sha256.txt";

    private InstallerChecksum() {}

    /** The sha256 {@code checksums} lists for {@code assetName}, lowercase, or empty when it lists none. */
    static Optional<String> expected(String checksums, String assetName) {
        if (checksums == null) return Optional.empty();
        return checksums.lines()
                .map(String::strip)
                .map(line -> line.split("\\s+\\*?", 2))
                .filter(parts -> parts.length == 2 && parts[1].strip().equals(assetName)
                        && SHA256_HEX.matcher(parts[0]).matches())
                .map(parts -> parts[0].toLowerCase(Locale.ROOT))
                .findFirst();
    }

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    /**
     * Passes when {@code file}'s sha256 is the one {@code checksums} lists for {@code assetName}; otherwise deletes
     * {@code file} and throws why. {@code checksums} is null when the release has none.
     */
    static void verify(Path file, String assetName, String checksums) throws IOException {
        Optional<String> want = expected(checksums, assetName);
        String refusal;
        try {
            if (want.isEmpty()) {
                refusal = checksums == null
                        ? "The release has no " + FILE_NAME + ", so " + assetName + " cannot be checked."
                        : "The release's " + FILE_NAME + " lists no checksum for " + assetName + ".";
            } else if (!want.get().equals(sha256(file))) {
                refusal = assetName + " does not match the release's checksum: the download is corrupt.";
            } else {
                return;
            }
        } catch (IOException e) {
            refusal = assetName + " could not be read to check it: " + e.getMessage() + ".";
        }
        Files.deleteIfExists(file);
        throw new IOException(refusal + " It was deleted and nothing was installed.");
    }

    /** {@code file}'s sha256, lowercase hex. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every JDK has SHA-256
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
