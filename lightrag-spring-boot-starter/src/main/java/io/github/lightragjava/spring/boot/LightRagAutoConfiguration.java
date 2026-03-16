package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleChatModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightragjava.persistence.FileSnapshotStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.neo4j.Neo4jGraphConfig;
import io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

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
            requireValue(chat.getApiKey(), "lightrag.chat.api-key")
        );
    }

    @Bean
    @ConditionalOnMissingBean
    EmbeddingModel embeddingModel(LightRagProperties properties) {
        var embedding = properties.getEmbedding();
        return new OpenAiCompatibleEmbeddingModel(
            requireValue(embedding.getBaseUrl(), "lightrag.embedding.base-url"),
            requireValue(embedding.getModel(), "lightrag.embedding.model"),
            requireValue(embedding.getApiKey(), "lightrag.embedding.api-key")
        );
    }

    @Bean
    @ConditionalOnMissingBean
    SnapshotStore snapshotStore() {
        return new FileSnapshotStore();
    }

    @Bean
    @ConditionalOnMissingBean(StorageProvider.class)
    StorageProvider storageProvider(LightRagProperties properties, SnapshotStore snapshotStore) {
        return switch (properties.getStorage().getType()) {
            case IN_MEMORY -> InMemoryStorageProvider.create(snapshotStore);
            case POSTGRES -> new PostgresStorageProvider(postgresConfig(properties), snapshotStore);
            case POSTGRES_NEO4J -> new PostgresNeo4jStorageProvider(
                postgresConfig(properties),
                neo4jConfig(properties),
                snapshotStore
            );
        };
    }

    @Bean
    @ConditionalOnMissingBean
    LightRag lightRag(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        StorageProvider storageProvider,
        ObjectProvider<RerankModel> rerankModel,
        LightRagProperties properties
    ) {
        var builder = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .storage(storageProvider);
        var rerank = rerankModel.getIfAvailable();
        if (rerank != null) {
            builder.rerankModel(rerank);
        }
        if (properties.getSnapshotPath() != null && !properties.getSnapshotPath().isBlank()) {
            builder.loadFromSnapshot(Path.of(properties.getSnapshotPath()));
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
}
