package io.github.lightrag.spring.boot;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.FixedWindowChunker;
import io.github.lightrag.indexing.MineruApiClient;
import io.github.lightrag.indexing.MineruClient;
import io.github.lightrag.indexing.MineruDocumentAdapter;
import io.github.lightrag.indexing.MineruParsingProvider;
import io.github.lightrag.indexing.MineruSelfHostedClient;
import io.github.lightrag.indexing.PlainTextParsingProvider;
import io.github.lightrag.indexing.TikaFallbackParsingProvider;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.model.openai.OpenAiCompatibleChatModel;
import io.github.lightrag.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightrag.persistence.FileSnapshotStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import io.github.lightrag.storage.neo4j.Neo4jGraphConfig;
import io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.Locale;

@AutoConfiguration
@ConditionalOnClass(LightRag.class)
@EnableConfigurationProperties(LightRagProperties.class)
public class LightRagAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ChatModel chatModel(LightRagProperties properties) {
        var chat = properties.getChat();
        return new OpenAiCompatibleChatModel(
            requireValue(chat.getBaseUrl(), "lightrag.chat.base-url"),
            requireValue(chat.getModel(), "lightrag.chat.model"),
            requireValue(chat.getApiKey(), "lightrag.chat.api-key"),
            chat.getTimeout()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel embeddingModel(LightRagProperties properties) {
        var embedding = properties.getEmbedding();
        return new OpenAiCompatibleEmbeddingModel(
            requireValue(embedding.getBaseUrl(), "lightrag.embedding.base-url"),
            requireValue(embedding.getModel(), "lightrag.embedding.model"),
            requireValue(embedding.getApiKey(), "lightrag.embedding.api-key"),
            embedding.getTimeout()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    SnapshotStore snapshotStore() {
        return new FileSnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean
    Chunker chunker(LightRagProperties properties) {
        var chunking = properties.getIndexing().getChunking();
        return new FixedWindowChunker(chunking.getWindowSize(), chunking.getOverlap());
    }

    @Bean
    @ConditionalOnMissingBean
    MineruDocumentAdapter mineruDocumentAdapter() {
        return new MineruDocumentAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
        "'${lightrag.indexing.parsing.mineru.enabled:false}' == 'true' and "
            + "'${lightrag.indexing.parsing.mineru.mode:DISABLED}'.toUpperCase() == 'API'"
    )
    MineruApiClient.Transport mineruApiTransport(LightRagProperties properties) {
        var mineru = properties.getIndexing().getParsing().getMineru();
        return new MineruApiClient.HttpTransport(
            requireValue(mineru.getBaseUrl(), "lightrag.indexing.parsing.mineru.base-url"),
            requireValue(mineru.getApiKey(), "lightrag.indexing.parsing.mineru.api-key")
        );
    }

    @Bean
    @ConditionalOnMissingBean(MineruClient.class)
    @ConditionalOnProperty(prefix = "lightrag.indexing.parsing.mineru", name = "enabled", havingValue = "true")
    MineruClient mineruClient(
        LightRagProperties properties,
        ObjectProvider<MineruApiClient.Transport> apiTransport,
        ObjectProvider<MineruSelfHostedClient.Transport> selfHostedTransport
    ) {
        var mineru = properties.getIndexing().getParsing().getMineru();
        return switch (normalizedMineruMode(mineru)) {
            case "API" -> new MineruApiClient(requireValue(apiTransport.getIfAvailable(),
                "lightrag.indexing.parsing.mineru.mode=API requires a MineruApiClient.Transport bean"));
            case "SELF_HOSTED" -> new MineruSelfHostedClient(requireValue(selfHostedTransport.getIfAvailable(),
                "lightrag.indexing.parsing.mineru.mode=SELF_HOSTED requires a MineruSelfHostedClient.Transport bean"));
            default -> throw new IllegalStateException(
                "unsupported lightrag.indexing.parsing.mineru.mode: " + mineru.getMode()
            );
        };
    }

    @Bean
    @ConditionalOnMissingBean
    MineruParsingProvider mineruParsingProvider(
        ObjectProvider<MineruClient> mineruClient,
        MineruDocumentAdapter mineruDocumentAdapter
    ) {
        var client = mineruClient.getIfAvailable();
        if (client == null) {
            return null;
        }
        return new MineruParsingProvider(client, mineruDocumentAdapter);
    }

    @Bean
    @ConditionalOnMissingBean
    DocumentParsingOrchestrator documentParsingOrchestrator(
        LightRagProperties properties,
        ObjectProvider<MineruParsingProvider> mineruParsingProvider
    ) {
        var parsing = properties.getIndexing().getParsing();
        return new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            mineruParsingProvider.getIfAvailable(),
            parsing.isTikaFallbackEnabled() ? new TikaFallbackParsingProvider() : null
        );
    }

    @Bean
    @ConditionalOnMissingBean(StorageProvider.class)
    StorageProvider storageProvider(
        LightRagProperties properties,
        SnapshotStore snapshotStore,
        ObjectProvider<DataSource> dataSourceProvider
    ) {
        var dataSource = dataSourceProvider.getIfAvailable();
        return switch (properties.getStorage().getType()) {
            case IN_MEMORY -> InMemoryStorageProvider.create(snapshotStore);
            case POSTGRES -> dataSource != null
                ? new PostgresStorageProvider(dataSource, postgresConfig(properties), snapshotStore)
                : new PostgresStorageProvider(postgresConfig(properties), snapshotStore);
            case POSTGRES_NEO4J -> dataSource != null
                ? new PostgresNeo4jStorageProvider(
                    dataSource,
                    postgresConfig(properties),
                    neo4jConfig(properties),
                    snapshotStore
                )
                : new PostgresNeo4jStorageProvider(
                    postgresConfig(properties),
                    neo4jConfig(properties),
                    snapshotStore
                );
        };
    }

    @Bean
    @ConditionalOnMissingBean
    WorkspaceStorageProvider workspaceStorageProvider(
        ObjectProvider<StorageProvider> storageProvider,
        ObjectProvider<DataSource> dataSource,
        SnapshotStore snapshotStore,
        LightRagProperties properties
    ) {
        return new SpringWorkspaceStorageProvider(storageProvider, dataSource, snapshotStore, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    LightRag lightRag(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        WorkspaceStorageProvider workspaceStorageProvider,
        ObjectProvider<Chunker> chunker,
        ObjectProvider<DocumentParsingOrchestrator> documentParsingOrchestrator,
        ObjectProvider<RerankModel> rerankModel,
        LightRagProperties properties
    ) {
        var query = properties.getQuery();
        var builder = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .workspaceStorage(workspaceStorageProvider)
            .automaticQueryKeywordExtraction(query.isAutomaticKeywordExtraction())
            .rerankCandidateMultiplier(query.getRerankCandidateMultiplier());
        if (properties.getIndexing().getEmbeddingBatchSize() > 0) {
            builder.embeddingBatchSize(properties.getIndexing().getEmbeddingBatchSize());
        }
        builder.maxParallelInsert(properties.getIndexing().getMaxParallelInsert());
        builder.entityExtractMaxGleaning(properties.getIndexing().getEntityExtractMaxGleaning());
        builder.maxExtractInputTokens(properties.getIndexing().getMaxExtractInputTokens());
        builder.entityExtractionLanguage(properties.getIndexing().getLanguage());
        builder.entityTypes(properties.getIndexing().getEntityTypes());
        var configuredChunker = chunker.getIfAvailable();
        if (configuredChunker != null) {
            builder.chunker(configuredChunker);
        }
        var configuredDocumentParsingOrchestrator = documentParsingOrchestrator.getIfAvailable();
        if (configuredDocumentParsingOrchestrator != null) {
            builder.documentParsingOrchestrator(configuredDocumentParsingOrchestrator);
        }
        var configuredRerankModel = rerankModel.getIfAvailable();
        if (configuredRerankModel != null) {
            builder.rerankModel(configuredRerankModel);
        }
        return builder.build();
    }

    private static PostgresStorageConfig postgresConfig(LightRagProperties properties) {
        var postgres = properties.getStorage().getPostgres();
        if (postgres.getVectorDimensions() == null) {
            throw new IllegalStateException("lightrag.storage.postgres.vector-dimensions is required");
        }
        return new PostgresStorageConfig(
            requireValue(postgres.getJdbcUrl(), "lightrag.storage.postgres.jdbc-url"),
            requireValue(postgres.getUsername(), "lightrag.storage.postgres.username"),
            requireValue(postgres.getPassword(), "lightrag.storage.postgres.password"),
            requireValue(postgres.getSchema(), "lightrag.storage.postgres.schema"),
            postgres.getVectorDimensions(),
            requireValue(postgres.getTablePrefix(), "lightrag.storage.postgres.table-prefix")
        );
    }

    private static Neo4jGraphConfig neo4jConfig(LightRagProperties properties) {
        var neo4j = properties.getStorage().getNeo4j();
        return new Neo4jGraphConfig(
            requireValue(neo4j.getUri(), "lightrag.storage.neo4j.uri"),
            requireValue(neo4j.getUsername(), "lightrag.storage.neo4j.username"),
            requireValue(neo4j.getPassword(), "lightrag.storage.neo4j.password"),
            requireValue(neo4j.getDatabase(), "lightrag.storage.neo4j.database")
        );
    }

    private static String requireValue(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }

    private static <T> T requireValue(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static String normalizedMineruMode(LightRagProperties.MineruProperties mineruProperties) {
        return requireValue(mineruProperties.getMode(), "lightrag.indexing.parsing.mineru.mode")
            .strip()
            .toUpperCase(Locale.ROOT);
    }
}
