package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.milvus.MilvusClientAdapter;
import io.github.lightrag.storage.milvus.MilvusSdkClientAdapter;
import io.github.lightrag.storage.milvus.MilvusVectorStorageAdapter;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import io.github.lightrag.storage.milvus.MilvusVectorStore;
import io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProvider;
import io.github.lightrag.storage.neo4j.Neo4jGraphStorageAdapter;
import io.github.lightrag.storage.mysql.MySqlStorageConfig;
import io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStore;
import io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProvider;
import io.github.lightrag.storage.postgres.PostgresRelationalStorageAdapter;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class StorageInjectionApiTest {
    @Test
    void exposesClientInjectionApisForSdkUsers() throws Exception {
        assertThat(Modifier.isPublic(MilvusClientAdapter.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(MilvusSdkClientAdapter.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(WorkspaceScopedNeo4jGraphStore.class.getModifiers())).isTrue();

        assertThat(MilvusVectorStore.class.getConstructor(
            MilvusClientAdapter.class,
            MilvusVectorConfig.class,
            String.class
        )).isNotNull();
        assertThat(MilvusSdkClientAdapter.class.getConstructor(
            MilvusVectorConfig.class,
            MilvusClientV2.class
        )).isNotNull();
        assertThat(WorkspaceScopedNeo4jGraphStore.class.getConstructor(
            Driver.class,
            String.class,
            WorkspaceScope.class
        )).isNotNull();
        assertThat(PostgresNeo4jStorageProvider.class.getConstructor(
            PostgresStorageProvider.class,
            WorkspaceScopedNeo4jGraphStore.class
        )).isNotNull();
        assertThat(PostgresMilvusNeo4jStorageProvider.class.getConstructor(
            DataSource.class,
            PostgresStorageConfig.class,
            SnapshotStore.class,
            WorkspaceScope.class,
            PostgresMilvusNeo4jStorageProvider.GraphProjection.class,
            PostgresMilvusNeo4jStorageProvider.VectorProjection.class
        )).isNotNull();
        assertThat(PostgresMilvusNeo4jStorageProvider.class.getConstructor(
            DataSource.class,
            PostgresStorageConfig.class,
            SnapshotStore.class,
            WorkspaceScope.class,
            GraphStorageAdapter.class,
            VectorStorageAdapter.class
        )).isNotNull();
        assertThat(MySqlMilvusNeo4jStorageProvider.class.getConstructor(
            DataSource.class,
            MySqlStorageConfig.class,
            SnapshotStore.class,
            WorkspaceScope.class,
            MySqlMilvusNeo4jStorageProvider.GraphProjection.class,
            MySqlMilvusNeo4jStorageProvider.VectorProjection.class
        )).isNotNull();
        assertThat(Modifier.isPublic(PostgresRelationalStorageAdapter.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(Neo4jGraphStorageAdapter.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(MilvusVectorStorageAdapter.class.getModifiers())).isTrue();
    }
}
