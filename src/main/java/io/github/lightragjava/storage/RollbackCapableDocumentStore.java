package io.github.lightragjava.storage;

import java.util.List;

public interface RollbackCapableDocumentStore extends DocumentStore {
    void restoreDocuments(List<DocumentRecord> snapshot);
}
