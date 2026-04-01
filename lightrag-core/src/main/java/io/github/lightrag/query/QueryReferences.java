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

        var counts = new LinkedHashMap<String, Integer>();
        var firstIndex = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < chunks.size(); i++) {
            var source = resolveSource(chunks.get(i));
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

        var contexts = new ArrayList<QueryResult.Context>(chunks.size());
        for (var chunk : chunks) {
            var source = resolveSource(chunk);
            contexts.add(new QueryResult.Context(
                chunk.chunkId(),
                chunk.chunk().text(),
                sourceToReferenceId.getOrDefault(source, ""),
                source
            ));
        }

        var references = sortedSources.stream()
            .map(source -> new QueryResult.Reference(sourceToReferenceId.get(source), source))
            .toList();

        return new Result(List.copyOf(contexts), references);
    }

    private static String resolveSource(ScoredChunk chunk) {
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
