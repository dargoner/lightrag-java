package io.github.lightrag.storage.milvus;

import io.milvus.v2.common.ConsistencyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusSdkClientAdapterTest {
    @Test
    void sharedCollectionSchemaContainsWorkspaceTypeAndRelationMetadataFields() {
        var schema = MilvusSdkClientAdapter.collectionSchema(
            new MilvusClientAdapter.CollectionDefinition("rag_shared", "relations", 3, "chinese")
        );

        assertThat(schema.getField("pk_id").getMaxLength())
            .isEqualTo(MilvusSdkClientAdapter.MILVUS_RELATION_ID_MAX_LENGTH);
        assertThat(schema.getField("vector_id").getMaxLength())
            .isEqualTo(65_535);
        assertThat(schema.getField("workspace_id").getMaxLength())
            .isEqualTo(65_535);
        assertThat(schema.getField("record_type").getMaxLength())
            .isEqualTo(32);
        assertThat(schema.getField("src_id").getMaxLength())
            .isEqualTo(MilvusSdkClientAdapter.MILVUS_RELATION_ENDPOINT_MAX_LENGTH);
        assertThat(schema.getField("tgt_id").getMaxLength())
            .isEqualTo(MilvusSdkClientAdapter.MILVUS_RELATION_ENDPOINT_MAX_LENGTH);
        assertThat(schema.getField("file_path").getMaxLength())
            .isEqualTo(MilvusSdkClientAdapter.MILVUS_RELATION_FILE_PATH_MAX_LENGTH);
    }

    @Test
    void resolvesSharedCollectionNameFromPrefix() {
        assertThat(testConfig().sharedCollectionName())
            .isEqualTo("rag");
    }

    @Test
    void resolvesBoundedConsistencyByDefault() {
        assertThat(MilvusSdkClientAdapter.resolveQueryConsistency(testConfig()))
            .isEqualTo(ConsistencyLevel.BOUNDED);
    }

    @Test
    void appliesFiniteConnectionAndRpcTimeouts() {
        var connectConfig = MilvusSdkClientAdapter.connectConfig(testConfig());

        assertThat(connectConfig.getConnectTimeoutMs()).isPositive();
        assertThat(connectConfig.getRpcDeadlineMs()).isPositive();
        assertThat(connectConfig.getIdleTimeoutMs()).isPositive();
    }

    @Test
    void resolvesConfiguredStrongConsistency() {
        var config = new MilvusVectorConfig(
            "http://localhost:19530",
            "root:Milvus",
            null,
            null,
            "default",
            "rag_",
            3,
            "chinese",
            "rrf",
            60,
            MilvusVectorConfig.SchemaDriftStrategy.STRICT_FAIL,
            MilvusVectorConfig.QueryConsistency.STRONG,
            true
        );

        assertThat(MilvusSdkClientAdapter.resolveQueryConsistency(config))
            .isEqualTo(ConsistencyLevel.STRONG);
    }

    private static MilvusVectorConfig testConfig() {
        return new MilvusVectorConfig(
            "http://localhost:19530",
            "root:Milvus",
            null,
            null,
            "default",
            "rag_",
            3
        );
    }
}
