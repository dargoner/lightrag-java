package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class MineruParsingProvider implements DocumentParsingProvider {
    private final MineruClient client;
    private final MineruDocumentAdapter adapter;

    public MineruParsingProvider(MineruClient client, MineruDocumentAdapter adapter) {
        this.client = Objects.requireNonNull(client, "client");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public ParsedDocument parse(RawDocumentSource source) {
        var rawSource = Objects.requireNonNull(source, "source");
        var adapted = adapter.adapt(client.parse(rawSource), rawSource);
        var metadata = new LinkedHashMap<String, String>(adapted.metadata());
        metadata.put("parse_mode", "mineru");
        metadata.put("parse_backend", client.backend());
        return new ParsedDocument(
            adapted.documentId(),
            adapted.title(),
            adapted.plainText(),
            adapted.blocks(),
            Map.copyOf(metadata)
        );
    }
}

class MineruUnavailableException extends IllegalArgumentException {
    MineruUnavailableException(String message) {
        super(message);
    }

    MineruUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
