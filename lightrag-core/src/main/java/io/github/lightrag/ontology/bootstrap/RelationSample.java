package io.github.lightrag.ontology.bootstrap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One relation observation supplied to deterministic ontology bootstrap.
 *
 * <p>{@code keywords} is the raw, comma-separated relation keyword list. It is
 * split, trimmed, identity-deduplicated, and sorted during bootstrap.
 *
 * @param sourceEntityName  raw source entity name
 * @param targetEntityName  raw target entity name
 * @param keywords          raw comma-separated relation keywords
 * @param attributes        raw attributes as observed in the sample
 * @param documentId        source document identifier
 * @param chunkId           source chunk identifier
 */
public record RelationSample(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    Map<String, Object> attributes,
    String documentId,
    String chunkId
) {
    public RelationSample {
        sourceEntityName = Objects.requireNonNull(sourceEntityName, "sourceEntityName");
        targetEntityName = Objects.requireNonNull(targetEntityName, "targetEntityName");
        keywords = keywords == null ? "" : keywords;
        attributes = immutableAttributes(attributes);
        documentId = documentId == null ? "" : documentId;
        chunkId = chunkId == null ? "" : chunkId;
    }

    private static Map<String, Object> immutableAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
