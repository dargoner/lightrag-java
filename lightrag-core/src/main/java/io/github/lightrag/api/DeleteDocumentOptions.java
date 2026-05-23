package io.github.lightrag.api;

public record DeleteDocumentOptions(boolean deleteLlmCache) {
    public static DeleteDocumentOptions defaults() {
        return new DeleteDocumentOptions(false);
    }

    public static DeleteDocumentOptions withLlmCacheDeletion() {
        return new DeleteDocumentOptions(true);
    }
}
