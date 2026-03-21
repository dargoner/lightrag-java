package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.spring.boot.LightRagProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("lightrag")
class LightRagHealthIndicator implements HealthIndicator {
    private final LightRag lightRag;
    private final LightRagProperties properties;

    LightRagHealthIndicator(LightRag lightRag, LightRagProperties properties) {
        this.lightRag = lightRag;
        this.properties = properties;
    }

    @Override
    public Health health() {
        return Health.up()
            .withDetail("storageType", properties.getStorage().getType().name())
            .withDetail("asyncIngestEnabled", properties.getDemo().isAsyncIngestEnabled())
            .withDetail("chatModelConfigured", isConfigured(properties.getChat().getBaseUrl(), properties.getChat().getModel()))
            .withDetail("embeddingModelConfigured", isConfigured(properties.getEmbedding().getBaseUrl(), properties.getEmbedding().getModel()))
            .build();
    }

    private static boolean isConfigured(String baseUrl, String model) {
        return baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
    }
}
