package com.botmaker.studio.runtime;

import java.util.List;

/**
 * The options every JVM Studio starts a bot in takes, in one place because there are two such JVMs — the
 * run ({@link CodeExecutionService}) and the debug ({@code services.DebuggingService}) — and a bot that
 * warns under one and not the other is a difference nobody intended.
 *
 * <p>These are not Studio's own options: Studio's are in {@code pom.xml} (the {@code javafx:run} plugin and
 * jpackage) and in its jar manifest. A bot runs in a JVM of its own, launched from Java, so its command
 * line is built here.
 */
public final class BotJvm {

    /**
     * <b>{@code --enable-native-access=ALL-UNNAMED}</b>: the SDK loads OpenCV's natives through
     * {@code System.loadLibrary} from the classpath (an unnamed module), which JDK 24 warns about by name
     * and a later release will refuse outright. The bot did nothing wrong and the warning is the first
     * thing its author sees in the output pane, so it is granted rather than explained.
     */
    public static final List<String> OPTIONS = List.of("--enable-native-access=ALL-UNNAMED");

    private BotJvm() {
    }
}
