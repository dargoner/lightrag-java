package io.github.lightragjava.storage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface DocumentStore {
    void save(DocumentRecord document);

    Optional<DocumentRecord> load(String documentId);

    List<DocumentRecord> list();

    boolean contains(String documentId);

    record DocumentRecord(String id, String title, String content, Map<String, String> metadata) {
        public DocumentRecord {
            id = Objects.requireNonNull(id, "id");
            title = Objects.requireNonNull(title, "title");
            content = Objects.requireNonNull(content, "content");
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        }
    }
}
