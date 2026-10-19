package io.github.lightrag.ontology.bootstrap;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One entity observation supplied to deterministic ontology bootstrap.
 *
 * <p>{@code documentId} and {@code chunkId} participate in deduplication and in
 * the frequency/document gates. They are normalized to empty strings when
 * {@code null}; {@code attributes} is defensively copied and normalized to an
 * empty map when {@code null}.
 *
 * @param name        the raw entity name; never {@code null}
 * @param type        the raw entity type; never {@code null}
 * @param attributes  raw attributes as observed in the sample
 * @param documentId  source document identifier
 * @param chunkId     source chunk identifier
 */
public record EntitySample(
    String name,
    String type,
    Map<String, Object> attributes,
    String documentId,
    String chunkId
) {
    public EntitySample {
        name = Objects.requireNonNull(name, "name");
        type = Objects.requireNonNull(type, "type");
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
