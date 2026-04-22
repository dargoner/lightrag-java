package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.reasoning.ReasoningPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ReasoningContextAssembler {
    private final GraphStore graphStore;
    private final ChunkStore chunkStore;

    public ReasoningContextAssembler(GraphStore graphStore, ChunkStore chunkStore) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore");
    }

    public String assemble(QueryRequest request, List<ReasoningPath> paths) {
        Objects.requireNonNull(request, "request");
        var selectedPaths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        if (selectedPaths.isEmpty()) {
            return "";
        }
        var lines = new ArrayList<String>();
        lines.add("Question: " + request.query());
        lines.add("");
        for (int pathIndex = 0; pathIndex < selectedPaths.size(); pathIndex++) {
            var path = selectedPaths.get(pathIndex);
            lines.add("Reasoning Path " + (pathIndex + 1));
            for (int hopIndex = 0; hopIndex < path.relationEndpoints().size(); hopIndex++) {
                var relationEndpoint = path.relationEndpoints().get(hopIndex);
                var sourceEntityId = path.entityIds().get(hopIndex);
                var targetEntityId = path.entityIds().get(hopIndex + 1);
                var relation = graphStore.loadRelation(relationEndpoint.srcId(), relationEndpoint.tgtId())
                    .orElseThrow(() -> new IllegalStateException("missing relation: " + relationEndpoint));
                var source = graphStore.loadEntity(sourceEntityId)
                    .orElseThrow(() -> new IllegalStateException("missing entity: " + sourceEntityId));
                var target = graphStore.loadEntity(targetEntityId)
                    .orElseThrow(() -> new IllegalStateException("missing entity: " + targetEntityId));
                lines.add("Hop " + (hopIndex + 1) + ": " + source.name() + " --" + relation.type() + "--> " + target.name());
                if (!relation.description().isBlank()) {
                    lines.add("Relation detail: " + relation.description());
                }
                for (var chunkId : relation.sourceChunkIds()) {
                    chunkStore.load(chunkId).ifPresent(chunk -> lines.add("Evidence [" + chunkId + "]: " + chunk.text()));
                }
            }
            lines.add("");
        }
        return String.join("\n", lines).strip();
    }

    public List<ScoredChunk> supportingChunks(List<ReasoningPath> paths, List<ScoredChunk> fallbackChunks) {
        var merged = new LinkedHashMap<String, ScoredChunk>();
        for (var path : Objects.requireNonNull(paths, "paths")) {
            for (var relationEndpoint : path.relationEndpoints()) {
                var relation = graphStore.loadRelation(relationEndpoint.srcId(), relationEndpoint.tgtId())
                    .orElseThrow(() -> new IllegalStateException("missing relation: " + relationEndpoint));
                for (var chunkId : relation.sourceChunkIds()) {
                    if (addChunkIfPresent(merged, chunkId)) {
                        break;
                    }
                }
            }
        }
        for (var path : paths) {
            for (var relationEndpoint : path.relationEndpoints()) {
                var relation = graphStore.loadRelation(relationEndpoint.srcId(), relationEndpoint.tgtId())
                    .orElseThrow(() -> new IllegalStateException("missing relation: " + relationEndpoint));
                for (var chunkId : relation.sourceChunkIds()) {
                    addChunkIfPresent(merged, chunkId);
                }
            }
            for (var chunkId : new LinkedHashSet<>(path.supportingChunkIds())) {
                addChunkIfPresent(merged, chunkId);
            }
        }
        for (var chunk : Objects.requireNonNull(fallbackChunks, "fallbackChunks")) {
            merged.putIfAbsent(chunk.chunkId(), chunk);
        }
        return List.copyOf(merged.values());
    }

    private boolean addChunkIfPresent(LinkedHashMap<String, ScoredChunk> merged, String chunkId) {
        var chunk = chunkStore.load(chunkId).orElse(null);
        if (chunk == null) {
            return false;
        }
        merged.putIfAbsent(
            chunkId,
            new ScoredChunk(chunkId, new io.github.lightrag.types.Chunk(
                chunk.id(),
                chunk.documentId(),
                chunk.text(),
                chunk.tokenCount(),
                chunk.order(),
                chunk.metadata()
            ), 1.0d)
        );
        return true;
    }
}
