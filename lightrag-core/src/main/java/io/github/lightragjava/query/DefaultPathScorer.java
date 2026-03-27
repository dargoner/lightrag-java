package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.reasoning.PathRetrievalResult;
import io.github.lightragjava.types.reasoning.ReasoningPath;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DefaultPathScorer implements PathScorer {
    @Override
    public List<ReasoningPath> rerank(QueryRequest request, PathRetrievalResult retrievalResult) {
        Objects.requireNonNull(request, "request");
        var result = Objects.requireNonNull(retrievalResult, "retrievalResult");
        var entityScores = new LinkedHashMap<String, Double>();
        for (var entity : result.seedEntities()) {
            entityScores.put(entity.entityId(), entity.score());
        }
        var relationScores = new LinkedHashMap<String, Double>();
        var relationWeights = new LinkedHashMap<String, Double>();
        for (var relation : result.seedRelations()) {
            relationScores.put(relation.relationId(), relation.score());
            relationWeights.put(relation.relationId(), relation.relation().weight());
        }
        return result.paths().stream()
            .map(path -> withScore(path, request, entityScores, relationScores, relationWeights))
            .sorted(Comparator.comparingDouble(ReasoningPath::score).reversed()
                .thenComparing(ReasoningPath::hopCount, Comparator.reverseOrder()))
            .toList();
    }

    private static ReasoningPath withScore(
        ReasoningPath path,
        QueryRequest request,
        Map<String, Double> entityScores,
        Map<String, Double> relationScores,
        Map<String, Double> relationWeights
    ) {
        double seedScore = entityScores.getOrDefault(path.entityIds().get(0), 0.0d);
        double avgRelationScore = path.relationIds().stream()
            .mapToDouble(relationId -> relationScores.getOrDefault(relationId, 0.0d))
            .average()
            .orElse(0.0d);
        double avgRelationWeight = path.relationIds().stream()
            .mapToDouble(relationId -> relationWeights.getOrDefault(relationId, 0.0d))
            .average()
            .orElse(0.0d);
        double evidenceCoverage = Math.min(1.0d, (double) path.supportingChunkIds().size() / Math.max(1, path.hopCount()));
        double completionBonus = 0.10d * Math.min(path.hopCount(), request.maxHop());
        double hopPenalty = Math.max(0, path.hopCount() - 1) * 0.02d;
        double duplicatePenalty = duplicateEntityPenalty(path);
        double score = (seedScore * 0.30d)
            + (avgRelationScore * 0.22d)
            + (avgRelationWeight * 0.18d)
            + (evidenceCoverage * 0.20d)
            + completionBonus
            - hopPenalty
            - duplicatePenalty;
        return new ReasoningPath(
            path.entityIds(),
            path.relationIds(),
            path.supportingChunkIds(),
            path.hopCount(),
            Math.max(score, 0.0d)
        );
    }

    private static double duplicateEntityPenalty(ReasoningPath path) {
        long distinct = path.entityIds().stream().distinct().count();
        return distinct == path.entityIds().size() ? 0.0d : 0.20d;
    }
}
