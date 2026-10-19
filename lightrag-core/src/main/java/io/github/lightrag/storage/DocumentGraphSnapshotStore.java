package io.github.lightrag.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

public interface DocumentGraphSnapshotStore {
    void saveDocument(DocumentGraphSnapshot snapshot);

    Optional<DocumentGraphSnapshot> loadDocument(String documentId);

    void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks);

    List<ChunkGraphSnapshot> listChunks(String documentId);

    void delete(String documentId);

    record DocumentGraphSnapshot(
        String documentId,
        int version,
        SnapshotStatus status,
        SnapshotSource source,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage
    ) {
        public DocumentGraphSnapshot {
            documentId = requireNonBlank(documentId, "documentId");
            if (version < 0) {
                throw new IllegalArgumentException("version must not be negative");
            }
            status = Objects.requireNonNull(status, "status");
            source = Objects.requireNonNull(source, "source");
            if (chunkCount < 0) {
                throw new IllegalArgumentException("chunkCount must not be negative");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    record ChunkGraphSnapshot(
        String documentId,
        String chunkId,
        int chunkOrder,
        String contentHash,
        ChunkExtractStatus extractStatus,
        List<ExtractedEntityRecord> entities,
        List<ExtractedRelationRecord> relations,
        Instant updatedAt,
        String errorMessage
    ) {
        public ChunkGraphSnapshot {
            documentId = requireNonBlank(documentId, "documentId");
            chunkId = requireNonBlank(chunkId, "chunkId");
            if (chunkOrder < 0) {
                throw new IllegalArgumentException("chunkOrder must not be negative");
            }
            contentHash = requireNonBlank(contentHash, "contentHash");
            extractStatus = Objects.requireNonNull(extractStatus, "extractStatus");
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    record ExtractedEntityRecord(
        String name,
        String type,
        String description,
        List<String> aliases,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> attributes
    ) {
        public ExtractedEntityRecord(
            String name,
            String type,
            String description,
            List<String> aliases
        ) {
            this(name, type, description, aliases, Map.of());
        }

        public ExtractedEntityRecord {
            name = requireNonBlank(name, "name");
            type = requireNonBlank(type, "type");
            description = normalizeNullable(description);
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
            attributes = normalizeAttributes(attributes);
        }
    }

    record ExtractedRelationRecord(
        String sourceEntityName,
        String targetEntityName,
        String keywords,
        String description,
        double weight,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> attributes
    ) {
        public ExtractedRelationRecord(
            String sourceEntityName,
            String targetEntityName,
            String keywords,
            String description,
            double weight
        ) {
            this(sourceEntityName, targetEntityName, keywords, description, weight, Map.of());
        }

        public ExtractedRelationRecord {
            sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
            targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
            keywords = requireNonBlank(keywords, "keywords");
            description = normalizeNullable(description);
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("weight must be finite");
            }
            attributes = normalizeAttributes(attributes);
        }
    }

    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        var sorted = new TreeMap<String, Object>();
        for (var entry : attributes.entrySet()) {
            var key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException("attribute key must not be null");
            }
            var value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("attribute value must not be null: " + key);
            }
            sorted.put(key, copyAttributeValue(key, value));
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static Object copyAttributeValue(String key, Object value) {
        if (!(value instanceof List<?> values)) {
            return value;
        }
        for (var element : values) {
            if (element == null) {
                throw new IllegalArgumentException("attribute list value must not contain null: " + key);
            }
        }
        return List.copyOf(values);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
