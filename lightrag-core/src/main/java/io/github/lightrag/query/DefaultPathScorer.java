package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.reasoning.RelationEndpoint;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import io.github.lightrag.types.reasoning.ReasoningPath;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DefaultPathScorer implements PathScorer {
    @Override
    public List<ReasoningPath> rerank(QueryRequest request, PathRetrievalResult retrievalResult) {
        Objects.requireNonNull(request, "request");
        var result = Objects.requireNonNull(retrievalResult, "retrievalResult");
        var entityScores = new LinkedHashMap<String, Double>();
        var entityNames = new LinkedHashMap<String, String>();
        for (var entity : result.seedEntities()) {
            entityScores.put(entity.entityId(), entity.score());
            entityNames.put(entity.entityId(), entity.entity().name());
        }
        var relationScores = new LinkedHashMap<RelationEndpoint, Double>();
        var relationWeights = new LinkedHashMap<RelationEndpoint, Double>();
        for (var relation : result.seedRelations()) {
            var endpoint = new RelationEndpoint(
                relation.relation().srcId(),
                relation.relation().tgtId()
            );
            relationScores.put(endpoint, relation.score());
            relationWeights.put(endpoint, relation.relation().weight());
        }
        return result.paths().stream()
            .map(path -> withScore(path, request, entityScores, entityNames, relationScores, relationWeights))
            .sorted(Comparator.comparingDouble(ReasoningPath::score).reversed()
                .thenComparing(ReasoningPath::hopCount, Comparator.reverseOrder()))
            .toList();
    }

    private static ReasoningPath withScore(
        ReasoningPath path,
        QueryRequest request,
        Map<String, Double> entityScores,
        Map<String, String> entityNames,
        Map<RelationEndpoint, Double> relationScores,
        Map<RelationEndpoint, Double> relationWeights
    ) {
        double seedScore = entityScores.getOrDefault(path.entityIds().get(0), 0.0d);
        double terminalEntityScore = entityScores.getOrDefault(path.entityIds().get(path.entityIds().size() - 1), 0.0d);
        double avgRelationScore = path.relationEndpoints().stream()
            .mapToDouble(relationEndpoint -> relationScores.getOrDefault(relationEndpoint, 0.0d))
            .average()
            .orElse(0.0d);
        double avgRelationWeight = path.relationEndpoints().stream()
            .mapToDouble(relationEndpoint -> relationWeights.getOrDefault(relationEndpoint, 0.0d))
            .average()
            .orElse(0.0d);
        double evidenceCoverage = Math.min(1.0d, (double) path.supportingChunkIds().size() / Math.max(1, path.hopCount()));
        double terminalEntityMatch = lexicalMatchSignal(
            request.query(),
            entityNames.get(path.entityIds().get(path.entityIds().size() - 1))
        );
        double completionBonus = 0.10d * Math.min(path.hopCount(), request.maxHop());
        double hopPenalty = Math.max(0, path.hopCount() - 1) * 0.02d;
        double duplicatePenalty = duplicateEntityPenalty(path);
        double score = (seedScore * 0.30d)
            + (terminalEntityScore * 0.08d)
            + (avgRelationScore * 0.22d)
            + (avgRelationWeight * 0.18d)
            + (evidenceCoverage * 0.20d)
            + (terminalEntityMatch * 0.18d)
            + completionBonus
            - hopPenalty
            - duplicatePenalty;
        return new ReasoningPath(
            path.entityIds(),
            path.relationEndpoints(),
            path.supportingChunkIds(),
            path.hopCount(),
            Math.max(score, 0.0d)
        );
    }

    private static double duplicateEntityPenalty(ReasoningPath path) {
        long distinct = path.entityIds().stream().distinct().count();
        return distinct == path.entityIds().size() ? 0.0d : 0.20d;
    }

    private static double lexicalMatchSignal(String query, String entityName) {
        if (query == null || entityName == null) {
            return 0.0d;
        }
        var normalizedQuery = query.strip().toLowerCase(Locale.ROOT);
        var normalizedEntity = entityName.strip().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isEmpty() || normalizedEntity.isEmpty()) {
            return 0.0d;
        }
        if (normalizedQuery.contains(normalizedEntity)) {
            return 1.0d;
        }
        var entityTokens = normalizedEntity.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsIdeographic}]+");
        int matched = 0;
        int total = 0;
        for (var token : entityTokens) {
            var normalizedToken = token.strip();
            if (normalizedToken.isEmpty()) {
                continue;
            }
            total++;
            if (normalizedQuery.contains(normalizedToken)) {
                matched++;
            }
        }
        return total == 0 ? 0.0d : (double) matched / total;
    }
}
