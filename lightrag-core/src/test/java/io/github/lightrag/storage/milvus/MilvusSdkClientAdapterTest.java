package io.github.lightrag.storage.milvus;

import io.milvus.v2.common.ConsistencyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusSdkClientAdapterTest {
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
