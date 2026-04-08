package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface GraphStorageAdapter extends AutoCloseable {
    GraphStore graphStore();

    GraphSnapshot captureSnapshot();

    void apply(StagedGraphWrites writes);

    void restore(GraphSnapshot snapshot);

    record GraphSnapshot(
        List<GraphStore.EntityRecord> entities,
        List<GraphStore.RelationRecord> relations
    ) {
        public GraphSnapshot {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        }

        public static GraphSnapshot empty() {
            return new GraphSnapshot(List.of(), List.of());
        }
    }

    record StagedGraphWrites(
        List<GraphStore.EntityRecord> entities,
        List<GraphStore.RelationRecord> relations
    ) {
        public StagedGraphWrites {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        }

        public static StagedGraphWrites empty() {
            return new StagedGraphWrites(List.of(), List.of());
        }

        public boolean isEmpty() {
            return entities.isEmpty() && relations.isEmpty();
        }
    }

    static GraphStorageAdapter noop() {
        return new GraphStorageAdapter() {
            private final GraphStore graphStore = new GraphStore() {
                @Override
                public void saveEntity(EntityRecord entity) {
                }

                @Override
                public void saveRelation(RelationRecord relation) {
                }

                @Override
                public Optional<EntityRecord> loadEntity(String entityId) {
                    return Optional.empty();
                }

                @Override
                public Optional<RelationRecord> loadRelation(String relationId) {
                    return Optional.empty();
                }

                @Override
                public List<EntityRecord> allEntities() {
                    return List.of();
                }

                @Override
                public List<RelationRecord> allRelations() {
                    return List.of();
                }

                @Override
                public List<RelationRecord> findRelations(String entityId) {
                    return List.of();
                }
            };

            @Override
            public GraphStore graphStore() {
                return graphStore;
            }

            @Override
            public GraphSnapshot captureSnapshot() {
                return GraphSnapshot.empty();
            }

            @Override
            public void apply(StagedGraphWrites writes) {
            }

            @Override
            public void restore(GraphSnapshot snapshot) {
            }
        };
    }

    @Override
    default void close() {
    }
}
