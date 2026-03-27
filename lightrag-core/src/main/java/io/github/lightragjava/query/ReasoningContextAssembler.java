package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.types.ScoredChunk;
import io.github.lightragjava.types.reasoning.ReasoningPath;

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
            for (int hopIndex = 0; hopIndex < path.relationIds().size(); hopIndex++) {
                var relationId = path.relationIds().get(hopIndex);
                var sourceEntityId = path.entityIds().get(hopIndex);
                var targetEntityId = path.entityIds().get(hopIndex + 1);
                var relation = graphStore.loadRelation(relationId)
                    .orElseThrow(() -> new IllegalStateException("missing relation: " + relationId));
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
            for (var chunkId : new LinkedHashSet<>(path.supportingChunkIds())) {
                chunkStore.load(chunkId).ifPresent(chunk -> merged.put(
                    chunkId,
                    new ScoredChunk(chunkId, new io.github.lightragjava.types.Chunk(
                        chunk.id(),
                        chunk.documentId(),
                        chunk.text(),
                        chunk.tokenCount(),
                        chunk.order(),
                        chunk.metadata()
                    ), 1.0d)
                ));
            }
        }
        for (var chunk : Objects.requireNonNull(fallbackChunks, "fallbackChunks")) {
            merged.putIfAbsent(chunk.chunkId(), chunk);
        }
        return List.copyOf(merged.values());
    }
}
