package io.github.lightrag.storage.memgraph;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStore;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

import java.util.List;
import java.util.Objects;

/**
 * Workspace-scoped graph store on a Memgraph instance, reusing the Bolt-family statement set of
 * {@link WorkspaceScopedNeo4jGraphStore} and swapping in the Memgraph bootstrap DDL.
 *
 * <p>Memgraph differs from Neo4j only in bootstrap: it has no relationship uniqueness constraints
 * (relations are kept unique by the scoped-id MERGE writes instead), does not derive indexes from
 * constraints, and its {@code DROP CONSTRAINT} only accepts the {@code ON ... ASSERT} definition
 * form, so the Neo4j legacy-constraint drops are omitted and the constraint plus the
 * {@code scopedId} / {@code workspaceId} indexes are created idempotently.</p>
 */
public final class WorkspaceScopedMemgraphGraphStore extends WorkspaceScopedNeo4jGraphStore {

    private static final String ENTITY_LABEL = "Entity";

    public WorkspaceScopedMemgraphGraphStore(MemgraphGraphConfig config, WorkspaceScope scope) {
        super(
            MemgraphSupport.createDriver(Objects.requireNonNull(config, "config")),
            SessionConfig.forDatabase(config.database()),
            Objects.requireNonNull(scope, "scope"),
            true
        );
    }

    public WorkspaceScopedMemgraphGraphStore(Driver driver, String database, WorkspaceScope scope) {
        super(driver, database, scope);
    }

    @Override
    protected List<String> bootstrapStatements() {
        return List.of(
            """
            CREATE CONSTRAINT memgraph_entity_scoped_id IF NOT EXISTS
            FOR (entity:%s) REQUIRE entity.scopedId IS UNIQUE
            """.formatted(ENTITY_LABEL),
            "CREATE INDEX ON :Entity(scopedId)",
            "CREATE INDEX ON :Entity(workspaceId)"
        );
    }
}
