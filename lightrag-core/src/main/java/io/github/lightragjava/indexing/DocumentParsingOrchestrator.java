package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

import java.util.Objects;

public final class DocumentParsingOrchestrator {
    private final PlainTextParsingProvider plainTextProvider;

    public DocumentParsingOrchestrator(PlainTextParsingProvider plainTextProvider) {
        this.plainTextProvider = Objects.requireNonNull(plainTextProvider, "plainTextProvider");
    }

    public ParsedDocument parse(RawDocumentSource source) {
        var resolved = Objects.requireNonNull(source, "source");
        return plainTextProvider.parse(resolved);
    }
}
