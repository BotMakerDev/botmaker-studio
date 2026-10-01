package com.botmaker.studio.sharing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The gallery's copy of a bot's releases: the address it is built at, and which tags it holds. */
class GalleryMirrorTest {

    @Test
    void theAddressIsBuiltFromTheNameAlone() {
        assertEquals("BotMakerDev__botmaker-gamebot__v0.2.0.zip",
                GalleryMirror.assetName("BotMakerDev", "botmaker-gamebot", "v0.2.0"));
        assertEquals("https://github.com/BotMakerDev/botmaker-gallery/releases/download/mirror/"
                        + "alice__koala__1.0.0.zip",
                GalleryMirror.downloadUrl("alice", "koala", "1.0.0"));
    }

    @Test
    void tagsAreThisBotsOnlyNewestFirstAndMatchedWithoutCase() throws Exception {
        var release = new ObjectMapper().readTree("""
                {"assets": [
                  {"name": "alice__koala__v1.9.0.zip"},
                  {"name": "Alice__Koala__v1.10.0.zip"},
                  {"name": "alice__koala-two__v9.0.0.zip"},
                  {"name": "bob__koala__v5.0.0.zip"},
                  {"name": "alice__koala__v1.10.0-rc1.zip"},
                  {"name": "alice__koala__notes.txt"}
                ]}""");
        assertEquals(List.of("v1.10.0", "v1.10.0-rc1", "v1.9.0"), GalleryMirror.tagsIn(release, "alice", "koala"));
        assertEquals(List.of(), GalleryMirror.tagsIn(null, "alice", "koala"), "no mirror release yet");
    }
}
