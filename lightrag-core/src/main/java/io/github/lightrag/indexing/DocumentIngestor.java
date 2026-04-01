package io.github.lightrag.indexing;

import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public final class DocumentIngestor {
    private final AtomicStorageProvider storageProvider;
    private final Chunker chunker;
    private final DocumentChunkPreparationStrategy chunkPreparationStrategy;
    private final ChunkingOrchestrator chunkingOrchestrator;
    private final ParentChildChunkBuilder parentChildChunkBuilder;

    public DocumentIngestor(AtomicStorageProvider storageProvider, Chunker chunker) {
        this(
            storageProvider,
            chunker,
            new DefaultChunkPreparationStrategy(),
            new ChunkingOrchestrator(),
            new ParentChildChunkBuilder()
        );
    }

    DocumentIngestor(
        AtomicStorageProvider storageProvider,
        Chunker chunker,
        DocumentChunkPreparationStrategy chunkPreparationStrategy
    ) {
        this(
            storageProvider,
            chunker,
            chunkPreparationStrategy,
            new ChunkingOrchestrator(),
            new ParentChildChunkBuilder()
        );
    }

    DocumentIngestor(
        AtomicStorageProvider storageProvider,
        Chunker chunker,
        DocumentChunkPreparationStrategy chunkPreparationStrategy,
        ChunkingOrchestrator chunkingOrchestrator
    ) {
        this(
            storageProvider,
            chunker,
            chunkPreparationStrategy,
            chunkingOrchestrator,
            new ParentChildChunkBuilder()
        );
    }

    DocumentIngestor(
        AtomicStorageProvider storageProvider,
        Chunker chunker,
        DocumentChunkPreparationStrategy chunkPreparationStrategy,
        ChunkingOrchestrator chunkingOrchestrator,
        ParentChildChunkBuilder parentChildChunkBuilder
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.chunkPreparationStrategy = Objects.requireNonNull(chunkPreparationStrategy, "chunkPreparationStrategy");
        this.chunkingOrchestrator = Objects.requireNonNull(chunkingOrchestrator, "chunkingOrchestrator");
        this.parentChildChunkBuilder = Objects.requireNonNull(parentChildChunkBuilder, "parentChildChunkBuilder");
    }

    public List<Chunk> ingest(List<Document> documents) {
        var prepared = prepare(documents);
        storageProvider.writeAtomically(storage -> {
            validateIdsNotInStorage(prepared.documents(), storage.documentStore());
            persist(prepared.documentRecords(), prepared.chunkRecords(), storage.documentStore(), storage.chunkStore());
            return null;
        });
        return prepared.chunks();
    }

    PreparedIngest prepare(List<Document> documents) {
        var batch = List.copyOf(Objects.requireNonNull(documents, "documents"));
        validateUniqueBatchIds(batch);
        var documentRecords = new ArrayList<DocumentStore.DocumentRecord>(batch.size());
        var chunkRecords = new ArrayList<ChunkStore.ChunkRecord>();
        var stagedChunks = new ArrayList<Chunk>();

        for (var document : batch) {
            documentRecords.add(new DocumentStore.DocumentRecord(
                document.id(),
                document.title(),
                document.content(),
                document.metadata()
            ));

            var chunks = chunkPreparationStrategy.prepare(document, chunker);
            validateChunkContract(document, chunks);
            for (var chunk : chunks) {
                chunkRecords.add(new ChunkStore.ChunkRecord(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.text(),
                    chunk.tokenCount(),
                    chunk.order(),
                    chunk.metadata()
                ));
            }
            stagedChunks.addAll(chunks);
        }

        return new PreparedIngest(
            batch,
            List.copyOf(documentRecords),
            List.copyOf(chunkRecords),
            List.copyOf(stagedChunks)
        );
    }

    PreparedIngest prepareParsed(ParsedDocument parsedDocument, DocumentIngestOptions options) {
        var parsed = Objects.requireNonNull(parsedDocument, "parsedDocument");
        var resolvedOptions = Objects.requireNonNull(options, "options");
        var metadata = new java.util.LinkedHashMap<String, String>(parsed.metadata());
        metadata.putAll(resolvedOptions.toMetadata());
        var normalized = new ParsedDocument(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.blocks(),
            java.util.Map.copyOf(metadata)
        );
        var document = new Document(normalized.documentId(), normalized.title(), normalized.plainText(), normalized.metadata());
        var chunkingResult = chunkingOrchestrator.chunk(normalized, resolvedOptions);
        var parentChildChunkSet = parentChildChunkBuilder.build(chunkingResult, resolvedOptions.parentChildProfile());
        validateChunkContract(document, parentChildChunkSet.searchableChunks());
        return new PreparedIngest(
            List.of(document),
            List.of(new DocumentStore.DocumentRecord(document.id(), document.title(), document.content(), document.metadata())),
            toChunkRecords(parentChildChunkSet.allChunks()),
            parentChildChunkSet.searchableChunks()
        );
    }

    void persist(PreparedIngest prepared, AtomicStorageProvider.AtomicStorageView storage) {
        var source = Objects.requireNonNull(prepared, "prepared");
        var storageView = Objects.requireNonNull(storage, "storage");
        persist(
            source.documentRecords(),
            source.chunkRecords(),
            storageView.documentStore(),
            storageView.chunkStore()
        );
    }

    private void validateUniqueBatchIds(List<Document> documents) {
        var seenIds = new HashSet<String>();
        for (var document : documents) {
            var source = Objects.requireNonNull(document, "document");
            if (!seenIds.add(source.id())) {
                throw new IllegalArgumentException("Duplicate document id in batch: " + source.id());
            }
        }
    }

    private void validateIdsNotInStorage(List<Document> documents, DocumentStore documentStore) {
        for (var document : documents) {
            if (documentStore.contains(document.id())) {
                throw new IllegalArgumentException("Document id already exists in storage: " + document.id());
            }
        }
    }

    private void validateChunkContract(Document document, List<Chunk> chunks) {
        var seenChunkIds = new HashSet<String>();
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = Objects.requireNonNull(chunks.get(index), "chunk");
            if (!chunk.documentId().equals(document.id())) {
                throw new IllegalArgumentException("chunk documentId must match source document id: " + document.id());
            }
            if (!seenChunkIds.add(chunk.id())) {
                throw new IllegalArgumentException("chunk id must be unique within document: " + chunk.id());
            }
            if (chunk.order() != index) {
                throw new IllegalArgumentException("chunk order must be contiguous and start at 0");
            }
        }
    }

    private void persist(
        List<DocumentStore.DocumentRecord> documentRecords,
        List<ChunkStore.ChunkRecord> chunkRecords,
        DocumentStore documentStore,
        ChunkStore chunkStore
    ) {
        for (var documentRecord : documentRecords) {
            documentStore.save(documentRecord);
        }
        for (var chunkRecord : chunkRecords) {
            chunkStore.save(chunkRecord);
        }
    }

    private static List<ChunkStore.ChunkRecord> toChunkRecords(List<Chunk> chunks) {
        var records = new ArrayList<ChunkStore.ChunkRecord>(chunks.size());
        for (var chunk : chunks) {
            records.add(new ChunkStore.ChunkRecord(
                chunk.id(),
                chunk.documentId(),
                chunk.text(),
                chunk.tokenCount(),
                chunk.order(),
                chunk.metadata()
            ));
        }
        return List.copyOf(records);
    }

    record PreparedIngest(
        List<Document> documents,
        List<DocumentStore.DocumentRecord> documentRecords,
        List<ChunkStore.ChunkRecord> chunkRecords,
        List<Chunk> chunks
    ) {
    }

}
