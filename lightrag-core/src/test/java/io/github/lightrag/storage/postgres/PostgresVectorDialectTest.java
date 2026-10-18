package io.github.lightrag.storage.postgres;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresVectorDialectTest {
    @Test
    void legacyConstructorKeepsAutoDialect() {
        var config = new PostgresStorageConfig("jdbc:postgresql://localhost/db", "user", "password", "public", 3, "rag_");

        assertThat(config.vectorDialect()).isEqualTo(PostgresVectorDialect.AUTO);
        assertThat(config.vectorDialect().resolved()).isEqualTo(PostgresVectorDialect.PGVECTOR);
    }

    @Test
    void rendersPgvectorTypesAndOperatorClass() {
        assertThat(PostgresVectorDialect.PGVECTOR.columnType(1024)).isEqualTo("vector(1024)");
        assertThat(PostgresVectorDialect.PGVECTOR.innerProductOperatorClass()).isEqualTo("vector_ip_ops");
        assertThat(PostgresVectorDialect.PGVECTOR.requiresVectorExtension()).isTrue();
    }

    @Test
    void rendersVastbaseTypesAndOperatorClass() {
        assertThat(PostgresVectorDialect.VASTBASE.columnType(1024)).isEqualTo("floatvector(1024)");
        assertThat(PostgresVectorDialect.VASTBASE.innerProductOperatorClass()).isEqualTo("floatvector_ip_ops");
        assertThat(PostgresVectorDialect.VASTBASE.requiresVectorExtension()).isFalse();
    }

    @Test
    void keepsExplicitDialectWhenCopyingConfig() {
        var config = new PostgresStorageConfig(
            "jdbc:postgresql://localhost/db",
            "user",
            "password",
            "fusion",
            1024,
            "rag_",
            PostgresVectorDialect.VASTBASE
        ).withVectorDialect(PostgresVectorDialect.VASTBASE);

        assertThat(config.vectorDialect()).isEqualTo(PostgresVectorDialect.VASTBASE);
    }
}
