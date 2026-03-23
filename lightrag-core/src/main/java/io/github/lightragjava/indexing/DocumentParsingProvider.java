package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

public interface DocumentParsingProvider {
    ParsedDocument parse(RawDocumentSource source);
}
