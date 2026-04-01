package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import io.github.lightrag.types.reasoning.ReasoningPath;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultPathRetriever implements PathRetriever {
    private final GraphStore graphStore;
    private final int perHopExpansionLimit;

    public DefaultPathRetriever(GraphStore graphStore, int perHopExpansionLimit) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        if (perHopExpansionLimit <= 0) {
            throw new IllegalArgumentException("perHopExpansionLimit must be positive");
        }
        this.perHopExpansionLimit = perHopExpansionLimit;
    }

    @Override
    public PathRetrievalResult retrieve(QueryRequest request, QueryContext seedContext) {
        Objects.requireNonNull(request, "request");
        var context = Objects.requireNonNull(seedContext, "seedContext");
        var paths = new LinkedHashMap<String, ReasoningPath>();
        int seedLimit = Math.max(1, request.pathTopK());
        for (int index = 0; index < Math.min(seedLimit, context.matchedEntities().size()); index++) {
            var seed = context.matchedEntities().get(index);
            expand(
                seed.entityId(),
                request.maxHop(),
                List.of(seed.entityId()),
                List.of(),
                new LinkedHashSet<>(),
                paths
            );
        }
        return new PathRetrievalResult(context.matchedEntities(), context.matchedRelations(), List.copyOf(paths.values()));
    }

    private void expand(
        String currentEntityId,
        int remainingHop,
        List<String> entityIds,
        List<String> relationIds,
        LinkedHashSet<String> chunkIds,
        Map<String, ReasoningPath> paths
    ) {
        if (remainingHop <= 0) {
            return;
        }
        var relations = graphStore.findRelations(currentEntityId);
        int expansions = 0;
        for (var relation : relations) {
            if (expansions >= perHopExpansionLimit) {
                break;
            }
            String nextEntityId = currentEntityId.equals(relation.sourceEntityId())
                ? relation.targetEntityId()
                : relation.sourceEntityId();
            if (entityIds.contains(nextEntityId) || relationIds.contains(relation.id())) {
                continue;
            }
            var nextEntityIds = new ArrayList<>(entityIds);
            nextEntityIds.add(nextEntityId);
            var nextRelationIds = new ArrayList<>(relationIds);
            nextRelationIds.add(relation.id());
            var nextChunkIds = new LinkedHashSet<>(chunkIds);
            nextChunkIds.addAll(relation.sourceChunkIds());
            var path = new ReasoningPath(
                List.copyOf(nextEntityIds),
                List.copyOf(nextRelationIds),
                List.copyOf(nextChunkIds),
                nextRelationIds.size(),
                0.0d
            );
            paths.putIfAbsent(pathKey(path), path);
            expansions++;
            expand(nextEntityId, remainingHop - 1, nextEntityIds, nextRelationIds, nextChunkIds, paths);
        }
    }

    private static String pathKey(ReasoningPath path) {
        return String.join("->", path.relationIds());
    }
}
