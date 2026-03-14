package io.github.lightragjava.api;

import io.github.lightragjava.config.LightRagConfig;

public final class LightRag {
    private final LightRagConfig config;

    LightRag(LightRagConfig config) {
        this.config = config;
    }

    public static LightRagBuilder builder() {
        return new LightRagBuilder();
    }

    LightRagConfig config() {
        return config;
    }
}
