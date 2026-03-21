package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
        .withPropertyValues(
            "lightrag.chat.base-url=http://localhost:11434/v1/",
            "lightrag.chat.model=qwen2.5:7b",
            "lightrag.chat.api-key=dummy",
            "lightrag.embedding.base-url=http://localhost:11434/v1/",
            "lightrag.embedding.model=nomic-embed-text",
            "lightrag.embedding.api-key=dummy",
            "lightrag.storage.type=in-memory",
            "lightrag.query.default-mode=GLOBAL",
            "lightrag.query.default-top-k=12",
            "lightrag.query.default-chunk-top-k=18",
            "lightrag.query.default-response-type=Bullet Points",
            "lightrag.demo.async-ingest-enabled=false"
        );

    @Test
    void autoConfiguresLightRagForInMemoryProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LightRag.class);
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(StorageProvider.class);
            assertThat(context).hasSingleBean(WorkspaceLightRagFactory.class);
        });
    }

    @Test
    void bindsQueryAndDemoDefaults() {
        contextRunner.run(context -> {
            var properties = context.getBean(LightRagProperties.class);

            assertThat(properties.getQuery().getDefaultMode()).isEqualTo("GLOBAL");
            assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(12);
            assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(18);
            assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Bullet Points");
            assertThat(properties.getDemo().isAsyncIngestEnabled()).isFalse();
            assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Workspace-Id");
            assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("default");
        });
    }

    @Test
    void providesP0DefaultsWhenNotConfigured() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
            .withPropertyValues(
                "lightrag.chat.base-url=http://localhost:11434/v1/",
                "lightrag.chat.model=qwen2.5:7b",
                "lightrag.chat.api-key=dummy",
                "lightrag.embedding.base-url=http://localhost:11434/v1/",
                "lightrag.embedding.model=nomic-embed-text",
                "lightrag.embedding.api-key=dummy",
                "lightrag.storage.type=in-memory"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);

                assertThat(properties.getQuery().getDefaultMode()).isEqualTo("MIX");
                assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Multiple Paragraphs");
                assertThat(properties.getDemo().isAsyncIngestEnabled()).isTrue();
                assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Workspace-Id");
                assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("default");
            });
    }

    @Test
    void bindsWorkspaceOverridesAndCachesWorkspaceInstances() {
        contextRunner
            .withPropertyValues(
                "lightrag.workspace.header-name=X-Tenant-Id",
                "lightrag.workspace.default-id=main"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);
                var factory = context.getBean(WorkspaceLightRagFactory.class);

                assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Tenant-Id");
                assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("main");
                assertThat(factory.get("alpha")).isSameAs(factory.get("alpha"));
                assertThat(factory.get("alpha")).isNotSameAs(factory.get("beta"));
                assertThat(context.getBean(LightRag.class)).isSameAs(factory.get("main"));
            });
    }
}
