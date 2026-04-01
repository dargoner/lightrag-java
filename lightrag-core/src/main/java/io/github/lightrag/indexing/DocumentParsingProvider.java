package io.github.lightrag.indexing;

import io.github.lightrag.types.RawDocumentSource;

public interface DocumentParsingProvider {
    ParsedDocument parse(RawDocumentSource source);
}
