package io.github.lightrag.query;

import io.github.lightrag.api.QueryResult;
import io.github.lightrag.types.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class QueryReferences {
    private QueryReferences() {
    }

    static Result fromChunks(List<ScoredChunk> chunks, boolean includeReferences) {
        if (!includeReferences) {
            return new Result(
                chunks.stream()
                    .map(chunk -> new QueryResult.Context(chunk.chunkId(), chunk.chunk().text()))
                    .toList(),
                List.of()
            );
        }

        var sourceToReferenceId = assignReferenceIds(chunks);

        var contexts = new ArrayList<QueryResult.Context>(chunks.size());
        for (var chunk : chunks) {
            var source = sourceOf(chunk);
            contexts.add(new QueryResult.Context(
                chunk.chunkId(),
                chunk.chunk().text(),
                sourceToReferenceId.getOrDefault(source, ""),
                source
            ));
        }

        var references = sourceToReferenceId.entrySet().stream()
            .map(entry -> new QueryResult.Reference(entry.getValue(), entry.getKey()))
            .toList();

        return new Result(List.copyOf(contexts), references);
    }

    static Map<String, String> assignReferenceIds(List<ScoredChunk> chunks) {
        var counts = new LinkedHashMap<String, Integer>();
        var firstIndex = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < chunks.size(); i++) {
            var source = sourceOf(chunks.get(i));
            counts.merge(source, 1, Integer::sum);
            firstIndex.putIfAbsent(source, i);
        }

        var sortedSources = counts.keySet().stream()
            .sorted(Comparator
                .<String>comparingInt(source -> counts.getOrDefault(source, 0))
                .reversed()
                .thenComparingInt(source -> firstIndex.getOrDefault(source, Integer.MAX_VALUE)))
            .toList();

        var sourceToReferenceId = new LinkedHashMap<String, String>();
        for (int i = 0; i < sortedSources.size(); i++) {
            sourceToReferenceId.put(sortedSources.get(i), Integer.toString(i + 1));
        }
        return sourceToReferenceId;
    }

    static String sourceOf(ScoredChunk chunk) {
        var metadata = chunk.chunk().metadata();
        var filePath = metadata.get("file_path");
        if (filePath != null && !filePath.isBlank()) {
            return filePath;
        }
        var source = metadata.get("source");
        if (source != null && !source.isBlank()) {
            return source;
        }
        return chunk.chunk().documentId();
    }

    record Result(List<QueryResult.Context> contexts, List<QueryResult.Reference> references) {
    }
}
