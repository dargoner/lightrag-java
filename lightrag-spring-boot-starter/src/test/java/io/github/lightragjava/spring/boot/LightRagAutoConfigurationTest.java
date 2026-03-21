package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.StorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;

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
            "lightrag.indexing.chunking.window-size=256",
            "lightrag.indexing.chunking.overlap=32",
            "lightrag.query.default-mode=GLOBAL",
            "lightrag.query.default-top-k=12",
            "lightrag.query.default-chunk-top-k=18",
            "lightrag.query.default-response-type=Bullet Points",
            "lightrag.query.automatic-keyword-extraction=false",
            "lightrag.query.rerank-candidate-multiplier=4",
            "lightrag.demo.async-ingest-enabled=false"
        );

    @Test
    void autoConfiguresLightRagForInMemoryProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LightRag.class);
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context).hasSingleBean(Chunker.class);
            assertThat(context).hasSingleBean(StorageProvider.class);
        });
    }

    @Test
    void bindsPipelineQueryAndDemoDefaults() {
        contextRunner.run(context -> {
            var properties = context.getBean(LightRagProperties.class);

            assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(256);
            assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(32);
            assertThat(properties.getQuery().getDefaultMode()).isEqualTo("GLOBAL");
            assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(12);
            assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(18);
            assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Bullet Points");
            assertThat(properties.getQuery().isAutomaticKeywordExtraction()).isFalse();
            assertThat(properties.getQuery().getRerankCandidateMultiplier()).isEqualTo(4);
            assertThat(properties.getDemo().isAsyncIngestEnabled()).isFalse();
        });
    }

    @Test
    void wiresPipelineSettingsIntoLightRag() {
        contextRunner.run(context -> {
            var lightRag = context.getBean(LightRag.class);

            assertThat(extractField(lightRag, "chunker")).isInstanceOf(Chunker.class);
            assertThat(extractField(lightRag, "automaticQueryKeywordExtraction")).isEqualTo(false);
            assertThat(extractField(lightRag, "rerankCandidateMultiplier")).isEqualTo(4);
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

                assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(1_000);
                assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(100);
                assertThat(properties.getQuery().getDefaultMode()).isEqualTo("MIX");
                assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Multiple Paragraphs");
                assertThat(properties.getQuery().isAutomaticKeywordExtraction()).isTrue();
                assertThat(properties.getQuery().getRerankCandidateMultiplier()).isEqualTo(2);
                assertThat(properties.getDemo().isAsyncIngestEnabled()).isTrue();
            });
    }

    private static Object extractField(LightRag lightRag, String fieldName) throws Exception {
        Field field = LightRag.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(lightRag);
    }
}
