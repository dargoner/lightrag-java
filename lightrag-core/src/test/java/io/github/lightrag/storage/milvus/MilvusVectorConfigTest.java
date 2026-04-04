package io.github.lightrag.storage.milvus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class MilvusVectorConfigTest {
    @Test
    void allowsAnonymousMilvusConnections() {
        assertThatCode(() -> new MilvusVectorConfig(
            "http://localhost:19530",
            null,
            null,
            null,
            "default",
            "rag_",
            3
        )).doesNotThrowAnyException();
    }
}
