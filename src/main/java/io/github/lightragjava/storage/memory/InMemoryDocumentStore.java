package io.github.lightragjava.storage.memory;

import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.RollbackCapableDocumentStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class InMemoryDocumentStore implements RollbackCapableDocumentStore {
    private final ConcurrentNavigableMap<String, DocumentRecord> documents = new ConcurrentSkipListMap<>();

    @Override
    public void save(DocumentRecord document) {
        var record = Objects.requireNonNull(document, "document");
        documents.put(record.id(), record);
    }

    @Override
    public Optional<DocumentRecord> load(String documentId) {
        return Optional.ofNullable(documents.get(Objects.requireNonNull(documentId, "documentId")));
    }

    @Override
    public List<DocumentRecord> list() {
        return List.copyOf(documents.values());
    }

    @Override
    public boolean contains(String documentId) {
        return documents.containsKey(Objects.requireNonNull(documentId, "documentId"));
    }

    @Override
    public void restoreDocuments(List<DocumentRecord> snapshot) {
        var replacement = new ConcurrentSkipListMap<String, DocumentRecord>();
        for (var record : Objects.requireNonNull(snapshot, "snapshot")) {
            replacement.put(record.id(), record);
        }
        documents.clear();
        documents.putAll(Map.copyOf(replacement));
    }
}
