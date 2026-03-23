package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.indexing.DocumentParsingOrchestrator;
import io.github.lightragjava.indexing.FixedWindowChunker;
import io.github.lightragjava.indexing.MineruApiClient;
import io.github.lightragjava.indexing.MineruClient;
import io.github.lightragjava.indexing.MineruParsingProvider;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleChatModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
        .withPropertyValues(
            "lightrag.chat.base-url=http://localhost:11434/v1/",
            "lightrag.chat.model=qwen2.5:7b",
            "lightrag.chat.api-key=dummy",
            "lightrag.chat.timeout=PT45S",
            "lightrag.embedding.base-url=http://localhost:11434/v1/",
            "lightrag.embedding.model=nomic-embed-text",
            "lightrag.embedding.api-key=dummy",
            "lightrag.embedding.timeout=PT12S",
            "lightrag.storage.type=in-memory",
            "lightrag.indexing.chunking.window-size=4",
            "lightrag.indexing.chunking.overlap=1",
            "lightrag.indexing.embedding-batch-size=2",
            "lightrag.indexing.max-parallel-insert=3",
            "lightrag.indexing.entity-extract-max-gleaning=2",
            "lightrag.indexing.max-extract-input-tokens=4096",
            "lightrag.indexing.language=Chinese",
            "lightrag.indexing.entity-types=Person,Organization",
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
            assertThat(context).hasSingleBean(StorageProvider.class);
            assertThat(context).hasSingleBean(Chunker.class);
            assertThat(context).hasSingleBean(WorkspaceLightRagFactory.class);
        });
    }

    @Test
    void bindsPipelineWorkspaceAndDemoDefaults() {
        contextRunner.run(context -> {
            var properties = context.getBean(LightRagProperties.class);

            assertThat(properties.getChat().getTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.getEmbedding().getTimeout()).isEqualTo(Duration.ofSeconds(12));
            assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(4);
            assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(1);
            assertThat(properties.getIndexing().getEmbeddingBatchSize()).isEqualTo(2);
            assertThat(properties.getIndexing().getMaxParallelInsert()).isEqualTo(3);
            assertThat(properties.getIndexing().getEntityExtractMaxGleaning()).isEqualTo(2);
            assertThat(properties.getIndexing().getMaxExtractInputTokens()).isEqualTo(4_096);
            assertThat(properties.getIndexing().getLanguage()).isEqualTo("Chinese");
            assertThat(properties.getIndexing().getEntityTypes()).containsExactly("Person", "Organization");
            assertThat(properties.getQuery().getDefaultMode()).isEqualTo("GLOBAL");
            assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(12);
            assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(18);
            assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Bullet Points");
            assertThat(properties.getQuery().isAutomaticKeywordExtraction()).isFalse();
            assertThat(properties.getQuery().getRerankCandidateMultiplier()).isEqualTo(4);
            assertThat(properties.getDemo().isAsyncIngestEnabled()).isFalse();
            assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Workspace-Id");
            assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("default");
            assertThat(properties.getWorkspace().getMaxActiveWorkspaces()).isEqualTo(32);
        });
    }

    @Test
    void bindsMineruAndChunkingDefaultsFromSpringProperties() {
        contextRunner
            .withUserConfiguration(MineruApiTransportConfiguration.class)
            .withPropertyValues(
                "lightrag.indexing.ingest.preset=LAW",
                "lightrag.indexing.ingest.parent-child-window-size=256",
                "lightrag.indexing.ingest.parent-child-overlap=32",
                "lightrag.indexing.parsing.tika-fallback-enabled=true",
                "lightrag.indexing.parsing.mineru.enabled=true",
                "lightrag.indexing.parsing.mineru.mode=API",
                "lightrag.indexing.parsing.mineru.base-url=http://mineru.local",
                "lightrag.indexing.parsing.mineru.api-key=test-key"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);

                assertThat(properties.getIndexing().getIngest().getPreset()).isEqualTo(IngestPreset.LAW);
                assertThat(properties.getIndexing().getIngest().getParentChildWindowSize()).isEqualTo(256);
                assertThat(properties.getIndexing().getIngest().getParentChildOverlap()).isEqualTo(32);
                assertThat(properties.getIndexing().getParsing().isTikaFallbackEnabled()).isTrue();
                assertThat(properties.getIndexing().getParsing().getMineru().isEnabled()).isTrue();
                assertThat(properties.getIndexing().getParsing().getMineru().getMode()).isEqualTo("API");
                assertThat(properties.getIndexing().getParsing().getMineru().getBaseUrl()).isEqualTo("http://mineru.local");
                assertThat(properties.getIndexing().getParsing().getMineru().getApiKey()).isEqualTo("test-key");
            });
    }

    @Test
    void bindsLegacyIngestPropertiesForBackwardCompatibility() {
        contextRunner
            .withPropertyValues(
                "lightrag.indexing.ingest.document-type=LAW",
                "lightrag.indexing.ingest.chunk-granularity=COARSE",
                "lightrag.indexing.ingest.parent-child-enabled=true"
            )
            .run(context -> {
                var ingest = context.getBean(LightRagProperties.class).getIndexing().getIngest();

                assertThat(ingest.getDocumentType()).isEqualTo("LAW");
                assertThat(ingest.getChunkGranularity()).isEqualTo("COARSE");
                assertThat(ingest.isParentChildEnabled()).isTrue();
                assertThat(ingest.getPreset()).isEqualTo(IngestPreset.GENERAL);
            });
    }

    @Test
    void autoConfiguresMineruParserWhenApiTransportBeanIsPresent() {
        contextRunner
            .withUserConfiguration(MineruApiTransportConfiguration.class)
            .withPropertyValues(
                "lightrag.indexing.parsing.mineru.enabled=true",
                "lightrag.indexing.parsing.mineru.mode=API",
                "lightrag.indexing.parsing.mineru.base-url=http://mineru.local",
                "lightrag.indexing.parsing.mineru.api-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(MineruClient.class);
                assertThat(context).hasSingleBean(MineruParsingProvider.class);

                var orchestrator = context.getBean(DocumentParsingOrchestrator.class);
                var parsed = orchestrator.parse(RawDocumentSource.bytes(
                    "scan.png",
                    new byte[] {1, 2, 3},
                    "image/png",
                    Map.of("source", "test")
                ));

                assertThat(parsed.plainText()).isEqualTo("OCR text from MinerU");
                assertThat(parsed.metadata())
                    .containsEntry("parse_mode", "mineru")
                    .containsEntry("parse_backend", "mineru_api")
                    .containsEntry("source", "test");
            });
    }

    @Test
    void autoConfiguresDefaultMineruApiTransportFromProperties() {
        contextRunner
            .withPropertyValues(
                "lightrag.indexing.parsing.mineru.enabled=true",
                "lightrag.indexing.parsing.mineru.mode=API",
                "lightrag.indexing.parsing.mineru.base-url=https://mineru.net/api/v4/extract/task",
                "lightrag.indexing.parsing.mineru.api-key=test-key"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(MineruApiClient.Transport.class);
                assertThat(context).hasSingleBean(MineruClient.class);
                assertThat(context).hasSingleBean(MineruParsingProvider.class);
            });
    }

    @Test
    void wiresPipelineSettingsIntoLightRag() {
        contextRunner.run(context -> {
            var lightRag = context.getBean(LightRag.class);

            assertThat(extractField(lightRag, "chunker")).isInstanceOf(Chunker.class);
            assertThat(extractField(lightRag, "automaticQueryKeywordExtraction")).isEqualTo(false);
            assertThat(extractField(lightRag, "rerankCandidateMultiplier")).isEqualTo(4);
            assertThat(extractField(lightRag, "embeddingBatchSize")).isEqualTo(2);
            assertThat(extractField(lightRag, "maxParallelInsert")).isEqualTo(3);
            assertThat(extractField(lightRag, "entityExtractMaxGleaning")).isEqualTo(2);
            assertThat(extractField(lightRag, "maxExtractInputTokens")).isEqualTo(4_096);
            assertThat(extractField(lightRag, "entityExtractionLanguage")).isEqualTo("Chinese");
            assertThat(extractField(lightRag, "entityTypes")).isEqualTo(List.of("Person", "Organization"));
        });
    }

    @Test
    void wiresConfiguredTimeoutsIntoDefaultOpenAiModels() {
        contextRunner.run(context -> {
            var chatModel = (OpenAiCompatibleChatModel) context.getBean(ChatModel.class);
            var embeddingModel = (OpenAiCompatibleEmbeddingModel) context.getBean(EmbeddingModel.class);

            assertThat(extractTimeout(chatModel)).isEqualTo(Duration.ofSeconds(45));
            assertThat(extractTimeout(embeddingModel)).isEqualTo(Duration.ofSeconds(12));
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

                assertThat(properties.getChat().getTimeout()).isEqualTo(Duration.ofSeconds(30));
                assertThat(properties.getEmbedding().getTimeout()).isEqualTo(Duration.ofSeconds(30));
                assertThat(properties.getIndexing().getChunking().getWindowSize()).isEqualTo(1_000);
                assertThat(properties.getIndexing().getChunking().getOverlap()).isEqualTo(100);
                assertThat(properties.getIndexing().getEmbeddingBatchSize()).isZero();
                assertThat(properties.getIndexing().getMaxParallelInsert()).isEqualTo(1);
                assertThat(properties.getIndexing().getEntityExtractMaxGleaning()).isEqualTo(1);
                assertThat(properties.getIndexing().getMaxExtractInputTokens()).isEqualTo(20_480);
                assertThat(properties.getIndexing().getLanguage()).isEqualTo("English");
                assertThat(properties.getIndexing().getEntityTypes()).containsExactly(
                    "Person", "Creature", "Organization", "Location", "Event",
                    "Concept", "Method", "Content", "Data", "Artifact", "NaturalObject", "Other"
                );
                assertThat(properties.getIndexing().getIngest().getPreset()).isEqualTo(IngestPreset.GENERAL);
                assertThat(properties.getQuery().getDefaultMode()).isEqualTo("MIX");
                assertThat(properties.getQuery().getDefaultTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultChunkTopK()).isEqualTo(10);
                assertThat(properties.getQuery().getDefaultResponseType()).isEqualTo("Multiple Paragraphs");
                assertThat(properties.getQuery().isAutomaticKeywordExtraction()).isTrue();
                assertThat(properties.getQuery().getRerankCandidateMultiplier()).isEqualTo(2);
                assertThat(properties.getDemo().isAsyncIngestEnabled()).isTrue();
                assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Workspace-Id");
                assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("default");
                assertThat(properties.getWorkspace().getMaxActiveWorkspaces()).isEqualTo(32);
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

    @Test
    void limitsOnDemandWorkspaceCache() {
        contextRunner
            .withPropertyValues(
                "lightrag.workspace.default-id=main",
                "lightrag.workspace.max-active-workspaces=2"
            )
            .run(context -> {
                var factory = context.getBean(WorkspaceLightRagFactory.class);

                assertThat(factory.get("alpha")).isSameAs(factory.get("alpha"));
                assertThatThrownBy(() -> factory.get("beta"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("workspace cache limit exceeded");
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

    @Test
    void failsFastWhenMaxParallelInsertIsInvalid() {
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
                "lightrag.indexing.max-parallel-insert=0"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("maxParallelInsert must be positive");
            });
    }

    @Test
    void failsFastWhenMaxExtractInputTokensIsInvalid() {
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
                "lightrag.indexing.max-extract-input-tokens=0"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("maxExtractInputTokens must be positive");
            });
    }

    @Test
    void rejectsNonDefaultWorkspaceWhenCustomStorageProviderIsProvided() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
            .withUserConfiguration(CustomStorageProviderConfig.class)
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
                var factory = context.getBean(WorkspaceLightRagFactory.class);

                assertThatThrownBy(() -> factory.get("alpha"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("non-default workspaces require starter-managed storage providers");
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

    @TestConfiguration
    static class MineruApiTransportConfiguration {
        @Bean
        MineruApiClient.Transport mineruApiTransport() {
            return source -> new MineruClient.ParseResult(
                List.of(new MineruClient.Block(
                    "block-1",
                    "paragraph",
                    "OCR text from MinerU",
                    "Page 1",
                    List.of("Page 1"),
                    1,
                    null,
                    1,
                    Map.of("origin", "api")
                )),
                new String(source.bytes(), StandardCharsets.UTF_8)
            );
        }
    }

    private static final class StaticChunker implements Chunker {
        @Override
        public List<Chunk> chunk(Document document) {
            return List.of(new Chunk(document.id() + ":custom", document.id(), "custom", 6, 0, document.metadata()));
        }
    }

    @TestConfiguration
    static class CustomStorageProviderConfig {
        @Bean
        StorageProvider customStorageProvider() {
            return new DelegatingStorageProvider(InMemoryStorageProvider.create());
        }
    }

    static final class DelegatingStorageProvider implements AtomicStorageProvider {
        private final InMemoryStorageProvider delegate;

        DelegatingStorageProvider(InMemoryStorageProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public io.github.lightragjava.storage.DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public io.github.lightragjava.storage.ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public io.github.lightragjava.storage.GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public io.github.lightragjava.storage.VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public io.github.lightragjava.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            return delegate.writeAtomically(operation);
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            delegate.restore(snapshot);
        }
    }

    private static Object extractField(LightRag lightRag, String fieldName) throws Exception {
        Field field = LightRag.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(lightRag);
    }

    private static Duration extractTimeout(Object model) throws Exception {
        Field httpClientField = model.getClass().getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        Object httpClient = httpClientField.get(model);
        int callTimeoutMillis = (int) httpClient.getClass().getMethod("callTimeoutMillis").invoke(httpClient);
        return Duration.ofMillis(callTimeoutMillis);
    }
}
