package io.github.lightrag.indexing;

import io.github.lightrag.types.RawDocumentSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

public final class PlainTextParsingProvider implements DocumentParsingProvider {
    @Override
    public ParsedDocument parse(RawDocumentSource source) {
        var resolved = Objects.requireNonNull(source, "source");
        var text = new String(resolved.bytes(), StandardCharsets.UTF_8);
        var metadata = new java.util.LinkedHashMap<String, String>(resolved.metadata());
        metadata.put("parse_mode", "plain");
        metadata.put("parse_backend", "plain");
        return new ParsedDocument(
            resolved.sourceId(),
            resolved.fileName(),
            text,
            java.util.List.of(),
            Map.copyOf(metadata)
        );
    }
}
