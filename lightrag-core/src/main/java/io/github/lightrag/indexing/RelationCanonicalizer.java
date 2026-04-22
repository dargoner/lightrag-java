package io.github.lightrag.indexing;

import io.github.lightrag.api.CreateRelationRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class RelationCanonicalizer {
    public static final int DEFAULT_ENTITY_NAME_MAX_LENGTH = 256;
    public static final int DEFAULT_MILVUS_RELATION_ID_MAX_LENGTH = 64;
    public static final String GRAPH_FIELD_SEPARATOR = "<SEP>";
    public static final String RELATION_ID_PREFIX = "rel-";

    private RelationCanonicalizer() {
    }

    public static CanonicalRelationRef canonicalize(String source, String target) {
        var normalizedSource = CreateRelationRequest.normalizeEndpoint(source, "source");
        var normalizedTarget = CreateRelationRequest.normalizeEndpoint(target, "target");
        if (normalizedSource.equals(normalizedTarget)) {
            throw new IllegalArgumentException("self-loop relations are not allowed");
        }
        if (normalizedSource.compareTo(normalizedTarget) <= 0) {
            return new CanonicalRelationRef(relationId(normalizedSource, normalizedTarget), normalizedSource, normalizedTarget);
        }
        return new CanonicalRelationRef(relationId(normalizedTarget, normalizedSource), normalizedTarget, normalizedSource);
    }

    public static String relationId(String srcId, String tgtId) {
        return RELATION_ID_PREFIX + md5Hex(srcId + tgtId);
    }

    public static String joinValues(List<String> values) {
        var normalized = new LinkedHashSet<String>();
        for (var value : Objects.requireNonNull(values, "values")) {
            if (value == null) {
                continue;
            }
            var candidate = value.strip();
            if (!candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        return String.join(GRAPH_FIELD_SEPARATOR, normalized);
    }

    public static List<String> splitValues(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        var items = value.split(java.util.regex.Pattern.quote(GRAPH_FIELD_SEPARATOR));
        var normalized = new ArrayList<String>(items.length);
        for (var item : items) {
            var candidate = item.strip();
            if (!candidate.isEmpty()) {
                normalized.add(candidate);
            }
        }
        return List.copyOf(normalized);
    }

    public static String mergeCsv(String current, String incoming) {
        var values = new LinkedHashSet<String>();
        addCsv(values, current);
        addCsv(values, incoming);
        return String.join(",", values);
    }

    private static void addCsv(LinkedHashSet<String> values, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (var item : csv.split(",")) {
            var candidate = item.strip();
            if (!candidate.isEmpty()) {
                values.add(candidate);
            }
        }
    }

    private static String md5Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 digest is unavailable", exception);
        }
    }

    public record CanonicalRelationRef(String relationId, String srcId, String tgtId) {
        public CanonicalRelationRef {
            relationId = Objects.requireNonNull(relationId, "relationId");
            srcId = Objects.requireNonNull(srcId, "srcId");
            tgtId = Objects.requireNonNull(tgtId, "tgtId");
        }
    }
}
