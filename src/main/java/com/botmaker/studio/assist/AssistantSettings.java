package com.botmaker.studio.assist;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Which model the assistant pane talks to. Saved in {@code ProjectPreferences} — Studio-wide, not per
 * project, since a person chooses a model once. Holds no key: see {@link Provider#keyVariable()}.
 *
 * @param provider a {@link Provider#id()}; kept as text so a file naming an unknown provider still reads
 * @param baseUrl  the server, for a provider that {@link Provider#takesBaseUrl() takes one}; blank otherwise
 */
public record AssistantSettings(String provider, String model, String baseUrl) {

    public AssistantSettings {
        provider = provider == null ? Provider.OLLAMA.id() : provider;
        model = model == null ? "" : model.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    /** Ollama on this machine: free, offline, and the one a first run can work with. */
    public static AssistantSettings defaults() {
        return forProvider(Provider.OLLAMA);
    }

    /** {@code provider} at its own server, with no model chosen yet: the pane fills it from the provider's list. */
    public static AssistantSettings forProvider(Provider provider) {
        return new AssistantSettings(provider.id(), "", provider.defaultBaseUrl());
    }

    @JsonIgnore
    public Provider providerKind() {
        return Provider.fromId(provider);
    }
}
