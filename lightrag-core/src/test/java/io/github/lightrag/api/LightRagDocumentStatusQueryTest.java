package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagDocumentStatusQueryTest {
    private static final String WORKSPACE = "default";

    @Test
    void filtersByStatusWithOffsetAndLimit() {
        var storage = InMemoryStorageProvider.create();
        for (int i = 0; i < 5; i++) {
            save(storage, "ok-" + i, DocumentStatus.PROCESSED);
        }
        for (int i = 0; i < 3; i++) {
            save(storage, "failed-" + i, DocumentStatus.FAILED);
        }

        var page = newLightRag(storage).queryDocumentStatuses(WORKSPACE, Set.of(DocumentStatus.FAILED), 1, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.status()).isEqualTo(DocumentStatus.FAILED));
        assertThat(page.items()).extracting(DocumentProcessingStatus::documentId)
            .containsExactly("failed-1", "failed-2");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.offset()).isEqualTo(1);
        assertThat(page.limit()).isEqualTo(2);
    }

    @Test
    void treatsAnEmptyStatusFilterAsAllStatuses() {
        var storage = InMemoryStorageProvider.create();
        save(storage, "ok-1", DocumentStatus.PROCESSED);
        save(storage, "failed-1", DocumentStatus.FAILED);

        var page = newLightRag(storage).queryDocumentStatuses(WORKSPACE, Set.of(), 0, 10);

        assertThat(page.items()).extracting(DocumentProcessingStatus::documentId)
            .containsExactly("failed-1", "ok-1");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void rejectsNegativePagingParameters() {
        var lightRag = newLightRag(InMemoryStorageProvider.create());

        assertThatThrownBy(() -> lightRag.queryDocumentStatuses(WORKSPACE, Set.of(), -1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("offset");
        assertThatThrownBy(() -> lightRag.queryDocumentStatuses(WORKSPACE, Set.of(), 0, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit");
    }

    @Test
    void looksUpSeveralDocumentsByIdAndOmitsUnknownIds() {
        var storage = InMemoryStorageProvider.create();
        save(storage, "d1", DocumentStatus.PROCESSED);

        var statuses = newLightRag(storage).getDocumentStatuses(WORKSPACE, List.of("d1", "missing"));

        assertThat(statuses).extracting(DocumentProcessingStatus::documentId).containsExactly("d1");
    }

    @Test
    void exposesTheRawStatusMetadata() {
        var storage = InMemoryStorageProvider.create();
        storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
            "d1",
            DocumentStatus.PROCESSED,
            "processed",
            null,
            Map.of("file_path", "docs/d1.md")
        ));

        var status = newLightRag(storage).getDocumentStatus(WORKSPACE, "d1");

        assertThat(status.metadata()).containsEntry("file_path", "docs/d1.md");
    }

    private static void save(InMemoryStorageProvider storage, String documentId, DocumentStatus status) {
        storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
            documentId,
            status,
            status.name().toLowerCase(java.util.Locale.ROOT),
            status == DocumentStatus.FAILED ? "boom" : null
        ));
    }

    private static LightRag newLightRag(InMemoryStorageProvider storage) {
        return LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new NoopVectors())
            .storage(storage)
            .build();
    }

    private static final class NoopChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            throw new UnsupportedOperationException("status queries must not call the chat model");
        }
    }

    private static final class NoopVectors implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            throw new UnsupportedOperationException("status queries must not call the embedding model");
        }
    }
}
