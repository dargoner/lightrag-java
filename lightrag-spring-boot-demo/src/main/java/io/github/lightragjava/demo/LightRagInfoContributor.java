package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.LightRagProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class LightRagInfoContributor implements InfoContributor {
    private final LightRagProperties properties;

    LightRagInfoContributor(LightRagProperties properties) {
        this.properties = properties;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("lightrag", Map.of(
            "storage", Map.of(
                "type", properties.getStorage().getType().name()
            ),
            "demo", Map.of(
                "asyncIngestEnabled", properties.getDemo().isAsyncIngestEnabled()
            ),
            "query", Map.of(
                "defaultMode", properties.getQuery().getDefaultMode()
            )
        ));
    }
}
