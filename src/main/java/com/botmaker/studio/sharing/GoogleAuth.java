package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.oauth.Credentials;
import com.botmaker.shared.oauth.DeviceCode;
import com.botmaker.shared.oauth.DeviceFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * "Sign in with Google" via the OAuth 2.0 <b>device flow</b> ({@link DeviceFlow}) — the user authorizes in their
 * browser and never pastes a token. The twin of {@link GitHubAuth}: both run the same flow and persist to the
 * same {@link Credentials} file, under distinct keys.
 *
 * <p><b>No backend yet.</b> This is the sign-in plumbing plus a signed-in identity (the user's email); nothing
 * is gated on it. It stays inert until {@link GoogleConfig#OAUTH_CLIENT_ID} is set, at which point
 * {@link #isConfigured()} turns true and the UI offers the button.
 */
public final class GoogleAuth {

    private static final String TOKEN_KEY = "google_token";

    private static final DeviceFlow FLOW = new DeviceFlow("Google",
            GoogleConfig.DEVICE_CODE_URL, GoogleConfig.ACCESS_TOKEN_URL,
            Map.of("client_id", GoogleConfig.OAUTH_CLIENT_ID, "scope", GoogleConfig.SCOPE),
            clientParams(),
            1800);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile String token;
    private volatile String email;

    public GoogleAuth() {
        String stored = Credentials.read(TOKEN_KEY);
        this.token = stored.isBlank() ? null : stored;
    }

    public boolean isConfigured() {
        return GoogleConfig.isConfigured();
    }

    public boolean isAuthenticated() {
        return token != null && !token.isBlank();
    }

    public String token() {
        return token;
    }

    /**
     * The signed-in user's email, resolved lazily from Google's userinfo endpoint and cached for the session.
     * Resolves to {@code ""} when not signed in or unreachable. Runs the call off the FX thread.
     */
    public CompletableFuture<String> email() {
        if (!isAuthenticated()) return CompletableFuture.completedFuture("");
        String cached = email;
        if (cached != null) return CompletableFuture.completedFuture(cached);
        HttpRequest req = HttpRequest.newBuilder(URI.create(GoogleConfig.USERINFO_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            try {
                JsonNode n = mapper.readTree(resp.body());
                String e = n.path("email").asText("");
                if (!e.isBlank()) email = e;
                return e;
            } catch (Exception ex) {
                return "";
            }
        });
    }

    public void signOut() {
        token = null;
        email = null;
        Credentials.remove(TOKEN_KEY);
    }

    /** Step 1: request a device + user code from Google. */
    public CompletableFuture<DeviceCode> requestDeviceCode() {
        return FLOW.requestCode();
    }

    /**
     * Step 2: poll Google until the user authorizes (or the code expires / is denied). On success the token is
     * stored and returned. Runs on a background thread — do not call on the FX thread.
     */
    public CompletableFuture<String> pollForToken(DeviceCode code) {
        return FLOW.pollForToken(code, () -> false).thenApply(t -> {
            Credentials.store(TOKEN_KEY, t);
            this.token = t;
            this.email = null; // re-resolve for the (possibly new) account
            return t;
        });
    }

    /** What every poll sends besides the device code: the client, and its secret when Google issued one. */
    private static Map<String, String> clientParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", GoogleConfig.OAUTH_CLIENT_ID);
        if (!GoogleConfig.OAUTH_CLIENT_SECRET.isBlank()) {
            params.put("client_secret", GoogleConfig.OAUTH_CLIENT_SECRET);
        }
        return params;
    }
}
