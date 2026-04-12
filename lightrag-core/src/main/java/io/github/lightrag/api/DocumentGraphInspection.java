package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record DocumentGraphInspection(
    String documentId,
    DocumentStatus documentStatus,
    GraphMaterializationStatus graphStatus,
    SnapshotStatus snapshotStatus,
    int snapshotVersion,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    List<String> missingEntityKeys,
    List<String> missingRelationKeys,
    List<String> orphanEntityKeys,
    List<String> orphanRelationKeys,
    GraphMaterializationMode recommendedMode,
    boolean repairable,
    String summary
) {
    public DocumentGraphInspection {
        documentId = requireNonBlank(documentId, "documentId");
        documentStatus = Objects.requireNonNull(documentStatus, "documentStatus");
        graphStatus = Objects.requireNonNull(graphStatus, "graphStatus");
        snapshotStatus = Objects.requireNonNull(snapshotStatus, "snapshotStatus");
        missingEntityKeys = List.copyOf(Objects.requireNonNull(missingEntityKeys, "missingEntityKeys"));
        missingRelationKeys = List.copyOf(Objects.requireNonNull(missingRelationKeys, "missingRelationKeys"));
        orphanEntityKeys = List.copyOf(Objects.requireNonNull(orphanEntityKeys, "orphanEntityKeys"));
        orphanRelationKeys = List.copyOf(Objects.requireNonNull(orphanRelationKeys, "orphanRelationKeys"));
        recommendedMode = Objects.requireNonNull(recommendedMode, "recommendedMode");
        summary = summary == null ? "" : summary.strip();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
