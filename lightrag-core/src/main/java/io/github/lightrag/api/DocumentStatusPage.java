package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

/** A page of {@link DocumentProcessingStatus} records; {@code total} counts all matches before paging. */
public record DocumentStatusPage(
    List<DocumentProcessingStatus> items,
    int total,
    int offset,
    int limit
) {
    public DocumentStatusPage {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative");
        }
    }
}
