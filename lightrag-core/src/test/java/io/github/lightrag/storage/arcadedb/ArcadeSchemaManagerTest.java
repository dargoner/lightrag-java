package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DDL shape of the ArcadeDB schema bootstrap: the {@code attributes} STRING property must be
 * declared for both graph types and replaying the bootstrap must stay idempotent.
 */
class ArcadeSchemaManagerTest {

    @Test
    void bootstrapDeclaresTheAttributesPropertyOnEntityAndRelation() {
        var client = new RecordingArcadeClient();

        new ArcadeSchemaManager(client, ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build()).bootstrap();

        assertThat(client.commands)
            .contains("CREATE PROPERTY Entity.attributes STRING")
            .contains("CREATE PROPERTY Relation.attributes STRING");
    }

    @Test
    void replayingBootstrapToleratesAnExistingAttributesColumn() {
        var client = new RecordingArcadeClient();
        // Second run after the column exists: ArcadeDB rejects the redundant declaration.
        client.alreadyExists.add("CREATE PROPERTY Entity.attributes STRING");
        client.alreadyExists.add("CREATE PROPERTY Relation.attributes STRING");
        var schema = new ArcadeSchemaManager(
            client,
            ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build()
        );

        assertThatCode(schema::bootstrap).doesNotThrowAnyException();
        assertThat(client.commands)
            .contains("CREATE PROPERTY Entity.attributes STRING")
            .contains("CREATE PROPERTY Relation.attributes STRING");
    }

    private static final class RecordingArcadeClient extends ArcadeDbClient {
        private final List<String> commands = new ArrayList<>();
        private final Set<String> alreadyExists = new HashSet<>();

        private RecordingArcadeClient() {
            super(ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build());
        }

        @Override
        public List<Map<String, Object>> command(String language, String command, Object... parameters) {
            commands.add(command);
            if (alreadyExists.contains(command)) {
                throw new StorageException("ArcadeDB command failed: Property already exists: " + command);
            }
            return List.of();
        }
    }
}
