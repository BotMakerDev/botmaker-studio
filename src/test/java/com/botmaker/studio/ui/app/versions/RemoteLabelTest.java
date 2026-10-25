package com.botmaker.studio.ui.app.versions;

import com.botmaker.studio.project.vcs.ProjectVcs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Dev's remote list in words (2026-10-25: it showed a record's text, {@code RemoteInfo[name=original, …]}). */
class RemoteLabelTest {

    @Test
    void aGitHubRemoteNamesItsRepositoryAndHowTheBranchStands() {
        assertEquals("upstream — LiQiyeDev/Firestone · 4 versions to pull", VersionsPane.remoteLabel(
                new ProjectVcs.RemoteInfo("upstream", "https://github.com/LiQiyeDev/Firestone.git", 0, 4)));
        assertEquals("origin — me/Firestone · up to date", VersionsPane.remoteLabel(
                new ProjectVcs.RemoteInfo("origin", "https://github.com/me/Firestone.git", 0, 0)));
    }

    @Test
    void anotherAddressIsShownAsItIs() {
        assertEquals("backup — /mnt/usb/bot.git · not read yet",
                VersionsPane.remoteLabel(new ProjectVcs.RemoteInfo("backup", "/mnt/usb/bot.git", -1, -1)));
    }

    @Test
    void standingInWords() {
        assertEquals("1 version to push", VersionsPane.standing(1, 0));
        assertEquals("2 versions to push, 3 to pull", VersionsPane.standing(2, 3));
        assertEquals("1 version to pull", VersionsPane.standing(0, 1));
    }
}
