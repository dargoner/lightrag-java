package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.indexing.FixedWindowChunker;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

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
            "lightrag.indexing.chunking.window-size=4",
            "lightrag.indexing.chunking.overlap=1",
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
            assertThat(context).hasSingleBean(Chunker.class);
        });
    }

    @Test
    void bindsQueryDemoAndChunkingDefaults() {
        contextRunner.run(context -> {
            var properties = context.getBean(LightRagProperties.class);

            assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(4);
            assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(1);
            assertThat(properties.getQuery().getDefaultMode()).isEqualTo("GLOBAL");
            assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(12);
            assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(18);
            assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Bullet Points");
            assertThat(properties.getDemo().isAsyncIngestEnabled()).isFalse();
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

                assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(1000);
                assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(100);
                assertThat(properties.getQuery().getDefaultMode()).isEqualTo("MIX");
                assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Multiple Paragraphs");
                assertThat(properties.getDemo().isAsyncIngestEnabled()).isTrue();
            });
    }

    @Test
    void autoConfiguresFixedWindowChunkerFromProperties() {
        contextRunner
            .withUserConfiguration(TestModelConfiguration.class)
            .run(context -> {
            var chunker = context.getBean(Chunker.class);
            var lightRag = context.getBean(LightRag.class);
            var storageProvider = context.getBean(StorageProvider.class);

            assertThat(chunker).isInstanceOf(FixedWindowChunker.class);
            assertThat(chunker.chunk(new Document("doc-1", "Title", "abcdefghi", Map.of())))
                .extracting(Chunk::text)
                .containsExactly("abcd", "defg", "ghi");

            lightRag.ingest(List.of(new Document("doc-1", "Title", "abcdefghi", Map.of())));

            assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
                .extracting(record -> record.text())
                .containsExactly("abcd", "defg", "ghi");
            });
    }

    @Test
    void backsOffWhenApplicationProvidesCustomChunker() {
        contextRunner
            .withUserConfiguration(TestModelConfiguration.class, CustomChunkerConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var storageProvider = context.getBean(StorageProvider.class);

                assertThat(context.getBean(Chunker.class)).isInstanceOf(StaticChunker.class);
                assertThat(context.getBean(Chunker.class)).isNotInstanceOf(FixedWindowChunker.class);

                lightRag.ingest(List.of(new Document("doc-1", "Title", "ignored", Map.of("source", "custom"))));

                assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
                    .extracting(record -> record.id(), record -> record.text())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("doc-1:custom", "custom"));
            });
    }

    @Test
    void failsFastWhenChunkingSettingsAreInvalid() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
            .withPropertyValues(
                "lightrag.chat.base-url=http://localhost:11434/v1/",
                "lightrag.chat.model=qwen2.5:7b",
                "lightrag.chat.api-key=dummy",
                "lightrag.embedding.base-url=http://localhost:11434/v1/",
                "lightrag.embedding.model=nomic-embed-text",
                "lightrag.embedding.api-key=dummy",
                "lightrag.storage.type=in-memory",
                "lightrag.indexing.chunking.window-size=4",
                "lightrag.indexing.chunking.overlap=4"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("overlap must be smaller than windowSize");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomChunkerConfiguration {
        @Bean
        Chunker customChunker() {
            return new StaticChunker();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return request -> "{\"entities\":[],\"relations\":[]}";
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return texts -> texts.stream()
                .map(text -> List.of((double) text.length()))
                .toList();
        }
    }

    private static final class StaticChunker implements Chunker {
        @Override
        public List<Chunk> chunk(Document document) {
            return List.of(new Chunk(document.id() + ":custom", document.id(), "custom", 6, 0, document.metadata()));
        }
    }
}
