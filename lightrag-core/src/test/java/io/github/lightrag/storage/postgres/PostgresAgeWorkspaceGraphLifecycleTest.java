package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresAgeWorkspaceGraphLifecycleTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newAgeContainer();

    @Test
    void resolvesWorkspaceGraphIdsInSanitizedForm() {
        try (var dataSource = newDataSource()) {
            var lifecycle = new PostgresAgeWorkspaceGraphLifecycle(dataSource);

            assertThat(lifecycle.workspaceGraphId("Mnt.family-kb-1"))
                .isEqualTo("Mnt_family_kb_1_chunk_entity_relation")
                .isEqualTo(PostgresAgeSupport.graphName("Mnt.family-kb-1"));
        }
    }

    @Test
    void listsOnlyTheRequestedWorkspaceFamilyInSanitizedForm() {
        var familyPrefix = "Mnt." + suffix();
        var firstWorkspace = familyPrefix + "-kb-1";
        var secondWorkspace = familyPrefix + "-kb-2";
        var outsider = "Outsider." + suffix() + "-kb-1";
        try (var dataSource = newDataSource()) {
            var lifecycle = new PostgresAgeWorkspaceGraphLifecycle(dataSource);
            bootstrap(dataSource, firstWorkspace, secondWorkspace, outsider);

            assertThat(lifecycle.listWorkspaceGraphIds(familyPrefix + "-kb-"))
                .containsExactlyInAnyOrder(
                    lifecycle.workspaceGraphId(firstWorkspace),
                    lifecycle.workspaceGraphId(secondWorkspace)
                );
        }
    }

    @Test
    void dropsTheGraphSchemaAndIsIdempotent() {
        var workspace = "MntDrop." + suffix();
        try (var dataSource = newDataSource()) {
            var lifecycle = new PostgresAgeWorkspaceGraphLifecycle(dataSource);
            bootstrap(dataSource, workspace);
            var graphName = lifecycle.workspaceGraphId(workspace);
            new PostgresAgeGraphStore(dataSource, workspace).saveEntity(
                new EntityRecord("entity-1", "Alice", "person", "d", List.of(), List.of("chunk-1"))
            );

            assertThat(queryStrings(
                dataSource,
                "SELECT nspname::text FROM pg_namespace WHERE nspname = ?::name",
                graphName
            )).hasSize(1);

            assertThat(lifecycle.dropWorkspaceGraph(graphName)).isTrue();

            assertThat(queryStrings(
                dataSource,
                "SELECT nspname::text FROM pg_namespace WHERE nspname = ?::name",
                graphName
            )).isEmpty();
            assertThat(queryStrings(
                dataSource,
                "SELECT name::text FROM ag_catalog.ag_graph WHERE name = left(?::text, 63)::name",
                graphName
            )).isEmpty();

            assertThat(lifecycle.dropWorkspaceGraph(graphName)).isFalse();
            assertThat(lifecycle.dropWorkspaceGraph("mnt_absent_" + suffix() + "_chunk_entity_relation")).isFalse();
        }
    }

    @Test
    void droppingOneWorkspaceGraphLeavesSiblingsIntact() {
        var familyPrefix = "Fam." + suffix();
        var droppedWorkspace = familyPrefix + "-kb-1";
        var keptWorkspace = familyPrefix + "-kb-2";
        try (var dataSource = newDataSource()) {
            var lifecycle = new PostgresAgeWorkspaceGraphLifecycle(dataSource);
            bootstrap(dataSource, droppedWorkspace, keptWorkspace);
            var keptStore = new PostgresAgeGraphStore(dataSource, keptWorkspace);
            keptStore.saveEntity(
                new EntityRecord("entity-1", "Alice", "person", "d", List.of(), List.of("chunk-1"))
            );

            assertThat(lifecycle.dropWorkspaceGraph(lifecycle.workspaceGraphId(droppedWorkspace))).isTrue();

            assertThat(keptStore.loadEntity("entity-1")).isPresent();
            assertThat(queryStrings(
                dataSource,
                "SELECT nspname::text FROM pg_namespace WHERE nspname = ?::name",
                lifecycle.workspaceGraphId(keptWorkspace)
            )).hasSize(1);
        }
    }

    @Test
    void rejectsBlankArguments() {
        try (var dataSource = newDataSource()) {
            var lifecycle = new PostgresAgeWorkspaceGraphLifecycle(dataSource);

            assertThatThrownBy(() -> lifecycle.listWorkspaceGraphIds("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceIdPrefix");
            assertThatThrownBy(() -> lifecycle.dropWorkspaceGraph(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graphName");
        }
    }

    private static void bootstrap(HikariDataSource dataSource, String... workspaceIds) {
        for (var workspaceId : workspaceIds) {
            new PostgresAgeBootstrap(dataSource, workspaceId).bootstrap();
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static PostgreSQLContainer<?> newAgeContainer() {
        var image = DockerImageName.parse(
            System.getenv().getOrDefault("LIGHTRAG_AGE_IMAGE", "apache/age:release_PG16_1.6.0")
        ).asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static HikariDataSource newDataSource() {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(POSTGRES.getJdbcUrl());
        hikariConfig.setUsername(POSTGRES.getUsername());
        hikariConfig.setPassword(POSTGRES.getPassword());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static List<String> queryStrings(HikariDataSource dataSource, String sql, String... parameters) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (var index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (var resultSet = statement.executeQuery()) {
                var values = new ArrayList<String>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return values;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
