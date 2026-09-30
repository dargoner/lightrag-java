package io.github.lightrag.storage;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface GraphStorageAdapter extends AutoCloseable {
    GraphStore graphStore();

    GraphSnapshot captureSnapshot();

    void apply(StagedGraphWrites writes);

    void restore(GraphSnapshot snapshot);

    /**
     * Captures a scoped pre-image of the write set: for every given id, point-reads the state that exists
     * <i>before</i> {@link #apply}.
     *
     * <p>Contract: the returned payload must distinguish "present" from "absent" — an absent id is deleted during
     * compensation. Returning {@link Optional#empty()} means the implementation does not support scoped pre-images;
     * the caller then falls back to {@link #captureSnapshot()} + {@link #restore(GraphSnapshot)} (existing
     * semantics, still paid per whole workspace).</p>
     *
     * <p>Implementations must allow repeated calls: {@link StorageCoordinator} calls again when the PostgreSQL
     * transaction is retried, passing only ids it has not captured yet (so ids partially applied by an earlier
     * attempt still get a real pre-image). <b>Each call's return value is an independent payload</b> — the
     * coordinator stores them in capture order and hands them back one by one during compensation; implementations
     * need not (and must not assume they may) merge later results into earlier payloads. Pre-images across retries
     * are expressed as "only new ids + replay each payload", and the id sets of distinct payloads are disjoint by
     * construction.</p>
     */
    default Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        return Optional.empty();
    }

    /**
     * Rolls back the given pre-image. The payload is opaque to {@link StorageCoordinator} and may only be handed
     * back to the <b>same adapter instance that produced it</b>.
     *
     * <p><b>Implementation contract</b>:</p>
     * <ul>
     *   <li>An implementation whose {@link #capturePreImage} returns {@code Optional.of(...)} <b>must</b> override
     *       this method as well — the two are a pair; overriding only one is an implementation error;</li>
     *   <li>Type-check first: throw {@code IllegalArgumentException("unexpected pre-image payload: " + ...)} when
     *       the payload is not of this implementation's type instead of letting a raw
     *       {@code ClassCastException} escape (that would mask orchestration errors);</li>
     *   <li><b>Idempotent</b>: the same pre-image may be rolled back repeatedly with the same result;</li>
     *   <li>Rollback is a per-id point operation: ids present in the pre-image are written back with their
     *       pre-image value, ids absent from the pre-image are deleted. A batch write that failed midway (some ids
     *       already persisted) must still converge to the pre-image state.</li>
     * </ul>
     */
    default void restorePreImage(PreImage preImage) {
        throw new UnsupportedOperationException(
            "scoped pre-image restore is not supported by " + getClass().getName()
        );
    }

    /** Scoped pre-image payload; each adapter defines its own (see {@code Neo4jGraphStorageAdapter.ScopedPreImage}). */
    interface PreImage {
    }

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
