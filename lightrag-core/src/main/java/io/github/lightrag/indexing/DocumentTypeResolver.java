package io.github.lightrag.indexing;

import java.util.Objects;

public final class DocumentTypeResolver {
    public DocumentType resolve(DocumentTypeHint hint) {
        return switch (Objects.requireNonNull(hint, "hint")) {
            case AUTO, GENERIC -> DocumentType.GENERIC;
            case LAW -> DocumentType.LAW;
            case BOOK -> DocumentType.BOOK;
            case QA -> DocumentType.QA;
        };
    }
}
