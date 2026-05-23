package io.github.lightrag.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.types.RawDocumentSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LightRagSidecarParsingProvider implements DocumentParsingProvider {
    public static final String MEDIA_TYPE = "application/x-lightrag-sidecar+jsonl";

    private final ObjectMapper objectMapper;

    public LightRagSidecarParsingProvider() {
        this(new ObjectMapper());
    }

    LightRagSidecarParsingProvider(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public ParsedDocument parse(RawDocumentSource source) {
        var resolved = Objects.requireNonNull(source, "source");
        var lines = new String(resolved.bytes(), StandardCharsets.UTF_8).lines()
            .map(String::strip)
            .filter(line -> !line.isBlank())
            .toList();
        var metadata = new LinkedHashMap<String, String>(resolved.metadata());
        metadata.put("parse_mode", "sidecar");
        metadata.put("parse_backend", "lightrag_blocks_jsonl");

        JsonNode meta = null;
        var blocks = new ArrayList<ParsedBlock>();
        var contentOrder = 0;
        for (var line : lines) {
            var node = readLine(line);
            var type = textValue(node, "type");
            if ("meta".equals(type)) {
                meta = node;
                copyMeta(metadata, node);
                continue;
            }
            if (!"content".equals(type)) {
                continue;
            }
            blocks.add(toBlock(node, resolved, contentOrder++));
        }
        var plainText = blocks.stream()
            .map(ParsedBlock::text)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining("\n\n"));
        return new ParsedDocument(
            resolvedDocumentId(resolved, meta),
            resolvedTitle(resolved, meta),
            plainText,
            blocks,
            Map.copyOf(metadata)
        );
    }

    private JsonNode readLine(String line) {
        try {
            return objectMapper.readTree(line);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid LightRAG sidecar JSONL line", exception);
        }
    }

    private static ParsedBlock toBlock(JsonNode node, RawDocumentSource source, int order) {
        var heading = textValue(node, "heading");
        var parentHeadings = stringArray(node.get("parent_headings"));
        var hierarchy = new ArrayList<>(parentHeadings);
        if (!heading.isBlank()) {
            hierarchy.add(heading);
        }
        var metadata = new LinkedHashMap<String, String>();
        putIfPresent(metadata, "sidecar.format", textValue(node, "format"));
        putIfPresent(metadata, "sidecar.session_type", textValue(node, "session_type"));
        putIfPresent(metadata, "sidecar.table_slice", textValue(node, "table_slice"));
        if (node.has("level")) {
            metadata.put("sidecar.level", node.get("level").asText());
        }
        if (node.has("positions")) {
            metadata.put("sidecar.positions", node.get("positions").toString());
        }
        return new ParsedBlock(
            defaultIfBlank(textValue(node, "blockid"), source.sourceId() + ":sidecar:" + order),
            inferBlockType(node),
            textValue(node, "content"),
            resolvedSectionPath(hierarchy, source.fileName()),
            List.copyOf(hierarchy),
            null,
            "",
            order,
            Map.copyOf(metadata)
        );
    }

    private static String inferBlockType(JsonNode node) {
        var explicit = textValue(node, "block_type");
        if (!explicit.isBlank()) {
            return explicit;
        }
        var content = textValue(node, "content").toLowerCase(java.util.Locale.ROOT);
        if (content.contains("<table ")) {
            return "table";
        }
        return "paragraph";
    }

    private static String resolvedDocumentId(RawDocumentSource source, JsonNode meta) {
        return meta == null ? source.sourceId() : defaultIfBlank(textValue(meta, "doc_id"), source.sourceId());
    }

    private static String resolvedTitle(RawDocumentSource source, JsonNode meta) {
        if (meta == null) {
            return source.fileName();
        }
        return defaultIfBlank(textValue(meta, "doc_title"), defaultIfBlank(textValue(meta, "document_name"), source.fileName()));
    }

    private static String resolvedSectionPath(List<String> hierarchy, String fallback) {
        if (hierarchy.isEmpty()) {
            return fallback;
        }
        return String.join(" > ", hierarchy);
    }

    private static void copyMeta(Map<String, String> metadata, JsonNode node) {
        copyMeta(metadata, node, "format");
        copyMeta(metadata, node, "version");
        copyMeta(metadata, node, "document_name");
        copyMeta(metadata, node, "document_format");
        copyMeta(metadata, node, "document_hash");
        copyMeta(metadata, node, "parse_engine");
        copyMeta(metadata, node, "doc_id");
        copyMeta(metadata, node, "doc_title");
    }

    private static void copyMeta(Map<String, String> metadata, JsonNode node, String field) {
        putIfPresent(metadata, "sidecar." + field, textValue(node, field));
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private static String textValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asText("").strip();
    }

    private static List<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        var values = new ArrayList<String>();
        for (var item : node) {
            var value = item.asText("").strip();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
