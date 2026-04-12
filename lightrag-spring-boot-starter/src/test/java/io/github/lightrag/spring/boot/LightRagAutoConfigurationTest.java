package io.github.lightrag.spring.boot;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.FixedWindowChunker;
import io.github.lightrag.indexing.MineruApiClient;
import io.github.lightrag.indexing.MineruClient;
import io.github.lightrag.indexing.MineruParsingProvider;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.openai.OpenAiCompatibleChatModel;
import io.github.lightrag.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.RawDocumentSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

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
            assertThat(context).hasSingleBean(WorkspaceStorageProvider.class);
            assertThat(context).hasSingleBean(Chunker.class);
            assertThat(context).doesNotHaveBean("workspaceLightRagFactory");
        });
    }

    @Test
    void usesDedicatedCapabilityChatModelBeansWhenPresent() {
        contextRunner
            .withUserConfiguration(DedicatedCapabilityModelConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");

                assertThat(config.queryModel()).isSameAs(context.getBean("queryModel", ChatModel.class));
                assertThat(config.extractionModel()).isSameAs(context.getBean("extractionModel", ChatModel.class));
                assertThat(config.summaryModel()).isSameAs(context.getBean("summaryModel", ChatModel.class));
            });
    }

    @Test
    void fallsBackDedicatedCapabilityBeansToDefaultChatModelBean() {
        contextRunner
            .withUserConfiguration(TestModelConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
                var defaultChatModel = context.getBean(ChatModel.class);

                assertThat(config.queryModel()).isSameAs(defaultChatModel);
                assertThat(config.extractionModel()).isSameAs(defaultChatModel);
                assertThat(config.summaryModel()).isSameAs(defaultChatModel);
            });
    }

    @Test
    void stillAutoConfiguresDefaultChatModelWhenOnlyDedicatedCapabilityBeansAreProvided() {
        contextRunner
            .withUserConfiguration(DedicatedCapabilityOnlyConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
                var defaultChatModel = context.getBean("chatModel", ChatModel.class);

                assertThat(defaultChatModel).isInstanceOf(OpenAiCompatibleChatModel.class);
                assertThat(config.defaultChatModel()).isSameAs(defaultChatModel);
                assertThat(config.queryModel()).isSameAs(context.getBean("queryModel", ChatModel.class));
                assertThat(config.extractionModel()).isSameAs(context.getBean("extractionModel", ChatModel.class));
                assertThat(config.summaryModel()).isSameAs(context.getBean("summaryModel", ChatModel.class));
            });
    }

    @Test
    void usesSingleCustomDefaultChatModelBeanEvenWhenBeanNameDiffers() {
        contextRunner
            .withUserConfiguration(CustomNamedDefaultChatModelConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
                var defaultChatModel = context.getBean("customDefaultChatModel", ChatModel.class);

                assertThat(context.getBeansOfType(ChatModel.class)).hasSize(1);
                assertThat(config.defaultChatModel()).isSameAs(defaultChatModel);
                assertThat(config.queryModel()).isSameAs(defaultChatModel);
                assertThat(config.extractionModel()).isSameAs(defaultChatModel);
                assertThat(config.summaryModel()).isSameAs(defaultChatModel);
            });
    }

    @Test
    void honorsPrimaryCustomDefaultChatModelWhenDedicatedCapabilityBeansAlsoExist() {
        contextRunner
            .withUserConfiguration(PrimaryDefaultAndDedicatedCapabilityConfiguration.class)
            .run(context -> {
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
                var defaultChatModel = context.getBean("customDefaultChatModel", ChatModel.class);

                assertThat(config.defaultChatModel()).isSameAs(defaultChatModel);
                assertThat(config.queryModel()).isSameAs(context.getBean("queryModel", ChatModel.class));
                assertThat(config.extractionModel()).isSameAs(context.getBean("extractionModel", ChatModel.class));
                assertThat(config.summaryModel()).isSameAs(context.getBean("summaryModel", ChatModel.class));
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
            assertThat(properties.getStorage().getPostgres().getSchema()).isNull();
            assertThat(properties.getStorage().getPostgres().getTablePrefix()).isEqualTo("km_rag_");
        });
    }

    @Test
    void bindsMySqlMilvusNeo4jStorageProperties() {
        contextRunner
            .withUserConfiguration(CustomStorageProviderConfig.class)
            .withPropertyValues(
                "lightrag.storage.type=mysql_milvus_neo4j",
                "lightrag.storage.mysql.jdbc-url=jdbc:mysql://localhost:3306/lightrag",
                "lightrag.storage.mysql.username=root",
                "lightrag.storage.mysql.password=secret",
                "lightrag.storage.mysql.table-prefix=cfg_",
                "lightrag.storage.milvus.uri=http://localhost:19530",
                "lightrag.storage.milvus.token=root:Milvus",
                "lightrag.storage.milvus.database=rag",
                "lightrag.storage.milvus.collection-prefix=vec_",
                "lightrag.storage.milvus.vector-dimensions=768",
                "lightrag.storage.milvus.analyzer-type=english",
                "lightrag.storage.milvus.hybrid-ranker=weighted",
                "lightrag.storage.milvus.hybrid-rrf-k=99",
                "lightrag.storage.milvus.schema-drift-strategy=ignore",
                "lightrag.storage.neo4j.uri=bolt://localhost:7687",
                "lightrag.storage.neo4j.username=neo4j",
                "lightrag.storage.neo4j.password=password",
                "lightrag.storage.neo4j.database=neo4j"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);

                assertThat(properties.getStorage().getType()).isEqualTo(LightRagProperties.Type.MYSQL_MILVUS_NEO4J);
                assertThat(properties.getStorage().getMysql().getJdbcUrl()).isEqualTo("jdbc:mysql://localhost:3306/lightrag");
                assertThat(properties.getStorage().getMysql().getUsername()).isEqualTo("root");
                assertThat(properties.getStorage().getMysql().getPassword()).isEqualTo("secret");
                assertThat(properties.getStorage().getMysql().getTablePrefix()).isEqualTo("cfg_");
                assertThat(properties.getStorage().getMilvus().getUri()).isEqualTo("http://localhost:19530");
                assertThat(properties.getStorage().getMilvus().getToken()).isEqualTo("root:Milvus");
                assertThat(properties.getStorage().getMilvus().getDatabase()).isEqualTo("rag");
                assertThat(properties.getStorage().getMilvus().getCollectionPrefix()).isEqualTo("vec_");
                assertThat(properties.getStorage().getMilvus().getVectorDimensions()).isEqualTo(768);
                assertThat(properties.getStorage().getMilvus().getAnalyzerType()).isEqualTo("english");
                assertThat(properties.getStorage().getMilvus().getHybridRanker()).isEqualTo("weighted");
                assertThat(properties.getStorage().getMilvus().getHybridRrfK()).isEqualTo(99);
                assertThat(properties.getStorage().getMilvus().getSchemaDriftStrategy())
                    .isEqualTo(MilvusVectorConfig.SchemaDriftStrategy.IGNORE);
                assertThat(properties.getStorage().getNeo4j().getUri()).isEqualTo("bolt://localhost:7687");
                assertThat(properties.getStorage().getNeo4j().getUsername()).isEqualTo("neo4j");
                assertThat(properties.getStorage().getNeo4j().getPassword()).isEqualTo("password");
                assertThat(properties.getStorage().getNeo4j().getDatabase()).isEqualTo("neo4j");
            });
    }

    @Test
    void bindsPostgresMilvusNeo4jStorageProperties() {
        contextRunner
            .withUserConfiguration(CustomStorageProviderConfig.class)
            .withPropertyValues(
                "lightrag.storage.type=postgres_milvus_neo4j",
                "lightrag.storage.postgres.jdbc-url=jdbc:postgresql://localhost:5432/lightrag",
                "lightrag.storage.postgres.username=postgres",
                "lightrag.storage.postgres.password=secret",
                "lightrag.storage.postgres.schema=cfg",
                "lightrag.storage.postgres.vector-dimensions=768",
                "lightrag.storage.postgres.table-prefix=cfg_",
                "lightrag.storage.milvus.uri=http://localhost:19530",
                "lightrag.storage.milvus.token=root:Milvus",
                "lightrag.storage.milvus.database=rag",
                "lightrag.storage.milvus.collection-prefix=vec_",
                "lightrag.storage.milvus.vector-dimensions=768",
                "lightrag.storage.neo4j.uri=bolt://localhost:7687",
                "lightrag.storage.neo4j.username=neo4j",
                "lightrag.storage.neo4j.password=password",
                "lightrag.storage.neo4j.database=neo4j"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);

                assertThat(properties.getStorage().getType()).isEqualTo(LightRagProperties.Type.POSTGRES_MILVUS_NEO4J);
                assertThat(properties.getStorage().getPostgres().getJdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/lightrag");
                assertThat(properties.getStorage().getPostgres().getUsername()).isEqualTo("postgres");
                assertThat(properties.getStorage().getPostgres().getPassword()).isEqualTo("secret");
                assertThat(properties.getStorage().getPostgres().getSchema()).isEqualTo("cfg");
                assertThat(properties.getStorage().getPostgres().getTablePrefix()).isEqualTo("cfg_");
                assertThat(properties.getStorage().getMilvus().getUri()).isEqualTo("http://localhost:19530");
                assertThat(properties.getStorage().getMilvus().getToken()).isEqualTo("root:Milvus");
                assertThat(properties.getStorage().getMilvus().getCollectionPrefix()).isEqualTo("vec_");
                assertThat(properties.getStorage().getNeo4j().getUri()).isEqualTo("bolt://localhost:7687");
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
                assertThat(properties.getStorage().getMilvus().getAnalyzerType()).isEqualTo("chinese");
                assertThat(properties.getStorage().getMilvus().getHybridRanker()).isEqualTo("rrf");
                assertThat(properties.getStorage().getMilvus().getHybridRrfK()).isEqualTo(60);
                assertThat(properties.getStorage().getMilvus().getSchemaDriftStrategy())
                    .isEqualTo(MilvusVectorConfig.SchemaDriftStrategy.STRICT_FAIL);
            });
    }

    @Test
    void autoConfiguresFixedWindowChunkerFromProperties() {
        contextRunner
            .withUserConfiguration(TestModelConfiguration.class)
            .run(context -> {
                var chunker = context.getBean(Chunker.class);
                var lightRag = context.getBean(LightRag.class);
                var workspaceStorageProvider = context.getBean(WorkspaceStorageProvider.class);
                var storageProvider = workspaceStorageProvider.forWorkspace(new WorkspaceScope("default"));

                assertThat(chunker).isInstanceOf(FixedWindowChunker.class);
                assertThat(chunker.chunk(new Document("doc-1", "Title", "abcdefghi", Map.of())))
                    .extracting(Chunk::text)
                    .containsExactly("abcd", "defg", "ghi");

                lightRag.ingest("default", List.of(new Document("doc-1", "Title", "abcdefghi", Map.of())));

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
                var workspaceStorageProvider = context.getBean(WorkspaceStorageProvider.class);
                var storageProvider = workspaceStorageProvider.forWorkspace(new WorkspaceScope("default"));

                assertThat(context.getBean(Chunker.class)).isInstanceOf(StaticChunker.class);
                assertThat(context.getBean(Chunker.class)).isNotInstanceOf(FixedWindowChunker.class);

                lightRag.ingest("default", List.of(new Document("doc-1", "Title", "ignored", Map.of("source", "custom"))));

                assertThat(storageProvider.chunkStore().listByDocument("doc-1"))
                    .extracting(record -> record.id(), record -> record.text())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("doc-1:custom", "custom"));
            });
    }

    @Test
    void bindsWorkspaceOverridesAndCachesWorkspaceProviders() {
        contextRunner
            .withPropertyValues(
                "lightrag.workspace.header-name=X-Tenant-Id",
                "lightrag.workspace.default-id=main"
            )
            .run(context -> {
                var properties = context.getBean(LightRagProperties.class);
                var workspaceStorageProvider = context.getBean(WorkspaceStorageProvider.class);
                var lightRag = context.getBean(LightRag.class);
                var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");

                assertThat(properties.getWorkspace().getHeaderName()).isEqualTo("X-Tenant-Id");
                assertThat(properties.getWorkspace().getDefaultId()).isEqualTo("main");
                assertThat(workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")))
                    .isSameAs(workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")));
                assertThat(workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")))
                    .isNotSameAs(workspaceStorageProvider.forWorkspace(new WorkspaceScope("beta")));
                assertThat(config.workspaceStorageProvider()).isSameAs(workspaceStorageProvider);
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
                var workspaceStorageProvider = context.getBean(WorkspaceStorageProvider.class);

                assertThat(workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")))
                    .isSameAs(workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")));
                assertThatThrownBy(() -> workspaceStorageProvider.forWorkspace(new WorkspaceScope("beta")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("workspace cache limit exceeded");
            });
    }

    @Test
    void derivesDistinctSnapshotPathsPerWorkspaceFromSharedBasePath() throws Exception {
        var basePath = Files.createTempDirectory("starter-workspace-snapshots").resolve("snapshot.json");
        var properties = new LightRagProperties();
        properties.getWorkspace().setDefaultId("main");
        properties.setSnapshotPath(basePath.toString());
        var baseSnapshotStore = new io.github.lightrag.persistence.FileSnapshotStore();
        var workspaceStorageProvider = new SpringWorkspaceStorageProvider(
            properties,
            null,
            null,
            baseSnapshotStore,
            (scope, applicationDataSource, lock, snapshotStore) -> InMemoryStorageProvider.create(snapshotStore)
        );
        var snapshot = new SnapshotStore.Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());

        try {
            workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")).snapshotStore().save(basePath, snapshot);
            workspaceStorageProvider.forWorkspace(new WorkspaceScope("beta")).snapshotStore().save(basePath, snapshot);

            assertThat(baseSnapshotStore.list())
                .containsExactlyInAnyOrder(
                    basePath.resolveSibling("snapshot-alpha-0589b15e.json").toAbsolutePath().normalize(),
                    basePath.resolveSibling("snapshot-beta-002e15f0.json").toAbsolutePath().normalize()
                );
        } finally {
            workspaceStorageProvider.close();
        }
    }

    @Test
    void reusesApplicationDataSourceForPostgresStorageWhenAvailable() {
        var properties = new LightRagProperties();
        properties.getStorage().setType(LightRagProperties.Type.POSTGRES);
        properties.getStorage().getPostgres().setJdbcUrl("jdbc:postgresql://unused");
        properties.getStorage().getPostgres().setUsername("unused");
        properties.getStorage().getPostgres().setPassword("unused");
        properties.getStorage().getPostgres().setSchema("lightrag");
        properties.getStorage().getPostgres().setVectorDimensions(3);
        properties.getStorage().getPostgres().setTablePrefix("rag_");
        var dataSource = new StubDataSource();
        var seenDataSource = new AtomicReference<DataSource>();

        new SpringWorkspaceStorageProvider(
            properties,
            null,
            dataSource,
            new NoopSnapshotStore(),
            (scope, applicationDataSource, lock, snapshotStore) -> {
                seenDataSource.set(applicationDataSource);
                return InMemoryStorageProvider.create(snapshotStore);
            }
        ).forWorkspace(new WorkspaceScope("alpha"));

        assertThat(seenDataSource.get()).isSameAs(dataSource);
    }

    @Test
    void reusesApplicationDataSourceForWorkspaceScopedPostgresProviders() {
        var properties = new LightRagProperties();
        properties.getStorage().setType(LightRagProperties.Type.POSTGRES);
        properties.getStorage().getPostgres().setJdbcUrl("jdbc:postgresql://unused");
        properties.getStorage().getPostgres().setUsername("unused");
        properties.getStorage().getPostgres().setPassword("unused");
        properties.getStorage().getPostgres().setSchema("lightrag");
        properties.getStorage().getPostgres().setVectorDimensions(3);
        properties.getStorage().getPostgres().setTablePrefix("rag_");
        var dataSource = new StubDataSource();
        var seenDataSources = new java.util.ArrayList<DataSource>();

        var provider = new SpringWorkspaceStorageProvider(
            properties,
            null,
            dataSource,
            new NoopSnapshotStore(),
            (scope, applicationDataSource, lock, snapshotStore) -> {
                seenDataSources.add(applicationDataSource);
                return InMemoryStorageProvider.create(snapshotStore);
            }
        );

        provider.forWorkspace(new WorkspaceScope("alpha"));
        provider.forWorkspace(new WorkspaceScope("beta"));

        assertThat(seenDataSources).containsExactly(dataSource, dataSource);
    }

    @Test
    void reusesApplicationDataSourceForWorkspaceScopedMySqlMilvusNeo4jProviders() {
        var properties = new LightRagProperties();
        properties.getStorage().setType(LightRagProperties.Type.MYSQL_MILVUS_NEO4J);
        properties.getStorage().getMysql().setJdbcUrl("jdbc:mysql://unused");
        properties.getStorage().getMysql().setUsername("unused");
        properties.getStorage().getMysql().setPassword("unused");
        properties.getStorage().getMysql().setTablePrefix("rag_");
        properties.getStorage().getMilvus().setUri("http://localhost:19530");
        properties.getStorage().getMilvus().setToken("root:Milvus");
        properties.getStorage().getMilvus().setDatabase("default");
        properties.getStorage().getMilvus().setCollectionPrefix("rag_");
        properties.getStorage().getMilvus().setVectorDimensions(3);
        properties.getStorage().getNeo4j().setUri("bolt://localhost:7687");
        properties.getStorage().getNeo4j().setUsername("neo4j");
        properties.getStorage().getNeo4j().setPassword("password");
        properties.getStorage().getNeo4j().setDatabase("neo4j");
        var dataSource = new StubDataSource();
        var seenDataSources = new java.util.ArrayList<DataSource>();

        var provider = new SpringWorkspaceStorageProvider(
            properties,
            null,
            dataSource,
            new NoopSnapshotStore(),
            (scope, applicationDataSource, lock, snapshotStore) -> {
                seenDataSources.add(applicationDataSource);
                return InMemoryStorageProvider.create(snapshotStore);
            }
        );

        provider.forWorkspace(new WorkspaceScope("alpha"));
        provider.forWorkspace(new WorkspaceScope("beta"));

        assertThat(seenDataSources).containsExactly(dataSource, dataSource);
    }

    @Test
    void reusesApplicationDataSourceForWorkspaceScopedPostgresMilvusNeo4jProviders() {
        var properties = new LightRagProperties();
        properties.getStorage().setType(LightRagProperties.Type.POSTGRES_MILVUS_NEO4J);
        properties.getStorage().getPostgres().setJdbcUrl("jdbc:postgresql://unused");
        properties.getStorage().getPostgres().setUsername("unused");
        properties.getStorage().getPostgres().setPassword("unused");
        properties.getStorage().getPostgres().setSchema("lightrag");
        properties.getStorage().getPostgres().setVectorDimensions(3);
        properties.getStorage().getPostgres().setTablePrefix("rag_");
        properties.getStorage().getMilvus().setUri("http://localhost:19530");
        properties.getStorage().getMilvus().setToken("root:Milvus");
        properties.getStorage().getMilvus().setDatabase("default");
        properties.getStorage().getMilvus().setCollectionPrefix("rag_");
        properties.getStorage().getMilvus().setVectorDimensions(3);
        properties.getStorage().getNeo4j().setUri("bolt://localhost:7687");
        properties.getStorage().getNeo4j().setUsername("neo4j");
        properties.getStorage().getNeo4j().setPassword("password");
        properties.getStorage().getNeo4j().setDatabase("neo4j");
        var dataSource = new StubDataSource();
        var seenDataSources = new java.util.ArrayList<DataSource>();

        var provider = new SpringWorkspaceStorageProvider(
            properties,
            null,
            dataSource,
            new NoopSnapshotStore(),
            (scope, applicationDataSource, lock, snapshotStore) -> {
                seenDataSources.add(applicationDataSource);
                return InMemoryStorageProvider.create(snapshotStore);
            }
        );

        provider.forWorkspace(new WorkspaceScope("alpha"));
        provider.forWorkspace(new WorkspaceScope("beta"));

        assertThat(seenDataSources).containsExactly(dataSource, dataSource);
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
                var workspaceStorageProvider = context.getBean(WorkspaceStorageProvider.class);

                assertThatThrownBy(() -> workspaceStorageProvider.forWorkspace(new WorkspaceScope("alpha")))
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

    @Configuration(proxyBeanMethods = false)
    static class DedicatedCapabilityModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return request -> "default";
        }

        @Bean("queryModel")
        ChatModel queryModel() {
            return request -> "query";
        }

        @Bean("extractionModel")
        ChatModel extractionModel() {
            return request -> "{\"entities\":[],\"relations\":[]}";
        }

        @Bean("summaryModel")
        ChatModel summaryModel() {
            return request -> "summary";
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return texts -> texts.stream()
                .map(text -> List.of((double) text.length()))
                .toList();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DedicatedCapabilityOnlyConfiguration {
        @Bean("queryModel")
        ChatModel queryModel() {
            return request -> "query";
        }

        @Bean("extractionModel")
        ChatModel extractionModel() {
            return request -> "{\"entities\":[],\"relations\":[]}";
        }

        @Bean("summaryModel")
        ChatModel summaryModel() {
            return request -> "summary";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomNamedDefaultChatModelConfiguration {
        @Bean("customDefaultChatModel")
        ChatModel customDefaultChatModel() {
            return request -> "custom-default";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PrimaryDefaultAndDedicatedCapabilityConfiguration {
        @Bean("customDefaultChatModel")
        @Primary
        ChatModel customDefaultChatModel() {
            return request -> "custom-default";
        }

        @Bean("queryModel")
        ChatModel queryModel() {
            return request -> "query";
        }

        @Bean("extractionModel")
        ChatModel extractionModel() {
            return request -> "{\"entities\":[],\"relations\":[]}";
        }

        @Bean("summaryModel")
        ChatModel summaryModel() {
            return request -> "summary";
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
        public io.github.lightrag.storage.DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public io.github.lightrag.storage.ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public io.github.lightrag.storage.GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public io.github.lightrag.storage.VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public io.github.lightrag.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        @Override
        public io.github.lightrag.storage.DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return delegate.documentGraphSnapshotStore();
        }

        @Override
        public io.github.lightrag.storage.DocumentGraphJournalStore documentGraphJournalStore() {
            return delegate.documentGraphJournalStore();
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

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Duration extractTimeout(Object model) throws Exception {
        Field httpClientField = model.getClass().getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        Object httpClient = httpClientField.get(model);
        int callTimeoutMillis = (int) httpClient.getClass().getMethod("callTimeoutMillis").invoke(httpClient);
        return Duration.ofMillis(callTimeoutMillis);
    }

    private static final class StubDataSource implements DataSource {
        @Override
        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }

    private static final class NoopSnapshotStore implements SnapshotStore {
        @Override
        public void save(java.nio.file.Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(java.nio.file.Path path) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<java.nio.file.Path> list() {
            return List.of();
        }
    }
}
