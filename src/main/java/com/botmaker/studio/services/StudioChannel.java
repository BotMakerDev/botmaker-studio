package com.botmaker.studio.services;

import com.botmaker.shared.tools.UserDirs;
import com.botmaker.studio.config.AppPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * How a second launch hands a {@code botmaker://} link to the Studio already running (2026-10-25): a local socket
 * in the cache (the sandbox's own folder under {@code BOTMAKER_HOME}), one line of text per connection. A link
 * clicked in a browser so opens in the window the user has, instead of a second Studio. Only the link goes this way;
 * a second launch with a {@code .bmk} or a project is still a Studio of its own.
 *
 * <p>Where the socket cannot be made (a folder path too long for one), there is no channel and each launch is its
 * own Studio, as before.
 */
public final class StudioChannel implements AutoCloseable {

    /** The longest line read; a link is far shorter. */
    private static final int MAX_LINE = 2048;

    private final ServerSocketChannel server;
    private final Path socket;

    private StudioChannel(ServerSocketChannel server, Path socket) {
        this.server = server;
        this.socket = socket;
    }

    /** This Studio's socket: in the cache, or in the sandbox's folder. */
    public static Path socket() {
        return AppPaths.sandbox().orElseGet(UserDirs::cache).resolve("studio.sock");
    }

    /** Hands {@code text} to the Studio listening at {@code socket}; false when none listens there. */
    public static boolean handOver(Path socket, String text) {
        if (!Files.exists(socket)) return false;
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(ByteBuffer.wrap((text.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Listens at {@code socket}, each line received given to {@code onText} on the channel's own thread. Empty when
     * another Studio listens there already, or the socket cannot be made; a file left by a Studio that ended is
     * replaced.
     */
    public static Optional<StudioChannel> listen(Path socket, Consumer<String> onText) {
        if (handOver(socket, "")) return Optional.empty();
        try {
            Files.createDirectories(socket.getParent());
            Files.deleteIfExists(socket);
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            StudioChannel channel = new StudioChannel(server, socket);
            Thread accept = new Thread(() -> channel.serve(onText), "studio-channel");
            accept.setDaemon(true);
            accept.start();
            return Optional.of(channel);
        } catch (IOException | RuntimeException e) {
            System.err.println("A link clicked while Studio runs will start a second Studio: " + e.getMessage());
            return Optional.empty();
        }
    }

    private void serve(Consumer<String> onText) {
        while (server.isOpen()) {
            SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!server.isOpen()) return;
                try {
                    Thread.sleep(1000);   // an error that persists is not retried at full speed
                } catch (InterruptedException stop) {
                    return;
                }
                continue;
            }
            // Each on its own thread: one that connects and never writes holds only itself.
            Thread.ofVirtual().name("studio-channel-client").start(() -> read(client, onText));
        }
    }

    private static void read(SocketChannel client, Consumer<String> onText) {
        try (client; BufferedReader in = new BufferedReader(new InputStreamReader(Channels.newInputStream(client),
                StandardCharsets.UTF_8))) {
            String line = readLine(in);
            if (!line.isBlank()) onText.accept(line.strip());
        } catch (IOException e) {
            // the launch that connected went away
        } catch (RuntimeException e) {
            System.err.println("A handed-over link failed: " + e.getMessage());
        }
    }

    /** The first line, at most {@link #MAX_LINE} characters. */
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n' && line.length() < MAX_LINE) line.append((char) c);
        return line.toString();
    }

    /** Stops listening and removes the socket. */
    @Override
    public void close() {
        try {
            server.close();
            Files.deleteIfExists(socket);
        } catch (IOException e) {
            // the next Studio replaces the file
        }
    }
}
