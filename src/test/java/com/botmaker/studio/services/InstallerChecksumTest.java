// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerChecksumTest {

    private static final String NAME = "BotMaker-Studio-v9.9.9-windows-x64.msi";

    @TempDir
    Path dir;

    private Path installer(byte[] bytes) throws IOException {
        return Files.write(dir.resolve(NAME), bytes);
    }

    private static String checksums(String sha) {
        return "0000000000000000000000000000000000000000000000000000000000000000  other.zip\n"
                + sha.toUpperCase() + "  " + NAME + "\n";
    }

    @Test
    void aFileMatchingItsChecksumIsKept() throws IOException {
        Path file = installer(new byte[]{1, 2, 3});
        String listed = checksums(InstallerChecksum.sha256(file));

        assertDoesNotThrow(() -> InstallerChecksum.verify(file, NAME, listed));
        assertTrue(Files.exists(file));
    }

    @Test
    void aChangedByteIsRefusedAndDeleted() throws IOException {
        Path file = installer(new byte[]{1, 2, 3});
        String listed = checksums(InstallerChecksum.sha256(file));
        Files.write(file, new byte[]{1, 2, 4});

        IOException refused = assertThrows(IOException.class, () -> InstallerChecksum.verify(file, NAME, listed));
        assertTrue(refused.getMessage().contains("does not match"), refused.getMessage());
        assertFalse(Files.exists(file));
    }

    @Test
    void aReleaseWithoutChecksumsIsRefusedAndDeleted() throws IOException {
        Path file = installer(new byte[]{1, 2, 3});

        IOException refused = assertThrows(IOException.class, () -> InstallerChecksum.verify(file, NAME, null));
        assertTrue(refused.getMessage().contains("has no " + InstallerChecksum.FILE_NAME), refused.getMessage());
        assertFalse(Files.exists(file));
    }

    @Test
    void anAssetTheChecksumsDoNotListIsRefused() throws IOException {
        Path file = installer(new byte[]{1, 2, 3});
        String listed = "abc  something-else.msi\n";

        IOException refused = assertThrows(IOException.class, () -> InstallerChecksum.verify(file, NAME, listed));
        assertTrue(refused.getMessage().contains("lists no checksum"), refused.getMessage());
        assertFalse(Files.exists(file));
    }
}
