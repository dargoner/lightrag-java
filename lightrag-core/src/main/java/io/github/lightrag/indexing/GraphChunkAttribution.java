package io.github.lightrag.indexing;

import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.GraphStore;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Entity/relation -> chunk attribution over the per-chunk snapshots, minus one document's chunks.
 *
 * <p>The ids come from {@link GraphAssembler#assemble} on each snapshot — the same call the write path
 * makes ({@code GraphMaterializationPipeline.assembleChunkGraph}, used for journal keys and chunk
 * statuses) — so entity ids (the normalized name) and relation ids (the canonical endpoints) cannot
 * drift from the stored records.</p>
 *
 * <p>That is not sufficient on its own for alias-merged records: a snapshot whose chunk spells an
 * endpoint by an alias while the entity row for the primary name lives in a different snapshot
 * assembles to the alias spelling. Both sides are therefore re-anchored through the stored records'
 * merge keys (normalized name + aliases to stored entity id, the same view
 * {@code DeletionPipeline.resolveEntityIds} uses) before the relation id is computed, and entities
 * keep the normalized name/alias view as a fallback for ids no stored record matches.</p>
 */
public final class GraphChunkAttribution {
    private final Set<String> entityKeys;
    private final Set<String> relationIds;

    private GraphChunkAttribution(Set<String> entityKeys, Set<String> relationIds) {
        this.entityKeys = entityKeys;
        this.relationIds = relationIds;
    }

    public static GraphChunkAttribution from(
        List<GraphStore.EntityRecord> storedEntities,
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> snapshots,
        String excludingDocumentId
    ) {
        var storedEntityKeyToId = new LinkedHashMap<String, String>();
        for (var entity : Objects.requireNonNull(storedEntities, "storedEntities")) {
            storedEntityKeyToId.putIfAbsent(normalize(entity.name()), entity.id());
            for (var alias : entity.aliases()) {
                storedEntityKeyToId.putIfAbsent(normalize(alias), entity.id());
            }
        }

        var assembler = new GraphAssembler();
        var entityKeys = new LinkedHashSet<String>();
        var relationIds = new LinkedHashSet<String>();
        for (var snapshot : Objects.requireNonNull(snapshots, "snapshots")) {
            if (snapshot.documentId().equals(excludingDocumentId)) {
                continue;
            }
            var graph = assembler.assemble(GraphMaterializationPipeline.toChunkExtractions(List.of(snapshot)));
            for (var entity : graph.entities()) {
                var keys = new LinkedHashSet<String>();
                keys.add(entity.id());
                keys.add(normalize(entity.name()));
                for (var alias : entity.aliases()) {
                    keys.add(normalize(alias));
                }
                for (var key : keys) {
                    var storedId = storedEntityKeyToId.get(key);
                    if (storedId != null) {
                        keys.add(storedId);
                    }
                }
                entityKeys.addAll(keys);
            }
            for (var relation : graph.relations()) {
                relationIds.add(RelationCanonicalizer.relationId(
                    resolve(relation.srcId(), storedEntityKeyToId),
                    resolve(relation.tgtId(), storedEntityKeyToId)
                ));
            }
        }
        return new GraphChunkAttribution(Set.copyOf(entityKeys), Set.copyOf(relationIds));
    }

    /** The stored record survives when any of its id/name/alias spellings is still attributed. */
    public boolean entityHasAttribution(GraphStore.EntityRecord entity) {
        if (entityKeys.contains(entity.id()) || entityKeys.contains(normalize(entity.name()))) {
            return true;
        }
        return entity.aliases().stream().anyMatch(alias -> entityKeys.contains(normalize(alias)));
    }

    /**
     * The stored relation survives when any surviving chunk assembles to its canonical id. Snapshot
     * endpoints are resolved through the stored records' merge keys first, so an alias-spelled
     * relation still canonicalizes to the stored record's id.
     */
    public boolean relationHasAttribution(GraphStore.RelationRecord relation) {
        return relationIds.contains(relation.id());
    }

    private static String resolve(String assembledEndpointId, Map<String, String> storedEntityKeyToId) {
        return storedEntityKeyToId.getOrDefault(assembledEndpointId, assembledEndpointId);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }
}
