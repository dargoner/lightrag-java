package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.exception.StorageException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ArcadeSchemaManager {
    private final ArcadeDbClient client;
    private final ArcadeDbConfig config;

    public ArcadeSchemaManager(ArcadeDbClient client, ArcadeDbConfig config) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
    }

    public void bootstrap() {
        for (var statement : statements()) {
            try {
                client.command("sql", normalizeStatement(statement));
            } catch (StorageException exception) {
                if (!isAlreadyExistsFailure(exception)) {
                    throw exception;
                }
            }
        }
    }

    private static String normalizeStatement(String statement) {
        return statement.replace(" IF NOT EXISTS", "");
    }

    private static boolean isAlreadyExistsFailure(StorageException exception) {
        var message = exception.getMessage();
        if (message == null) {
            return false;
        }
        var normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("already exists")
            || normalized.contains("already defined")
            || normalized.contains("duplicated")
            || normalized.contains("duplicate");
    }

    private List<String> statements() {
        return List.of(
            "CREATE DOCUMENT TYPE IF NOT EXISTS Document",
            "CREATE PROPERTY IF NOT EXISTS Document.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Document.id STRING",
            "CREATE PROPERTY IF NOT EXISTS Document.title STRING",
            "CREATE PROPERTY IF NOT EXISTS Document.content STRING",
            "CREATE PROPERTY IF NOT EXISTS Document.metadata STRING",
            "CREATE INDEX IF NOT EXISTS ON Document (workspaceId, id) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS Chunk",
            "CREATE PROPERTY IF NOT EXISTS Chunk.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.id STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.text STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.tokenCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS Chunk.chunkOrder INTEGER",
            "CREATE PROPERTY IF NOT EXISTS Chunk.metadata STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.filePath STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.contentType STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.sectionPath STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.source STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.tenantId STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.createdAt STRING",
            "CREATE PROPERTY IF NOT EXISTS Chunk.searchable BOOLEAN",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, id) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, documentId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, contentType) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, source) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Chunk (workspaceId, searchable) NOTUNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS Entity",
            "CREATE PROPERTY IF NOT EXISTS Entity.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.id STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.name STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.type STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.description STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.aliases STRING",
            "CREATE PROPERTY IF NOT EXISTS Entity.sourceChunkIds STRING",
            "CREATE INDEX IF NOT EXISTS ON Entity (workspaceId, id) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS Relation",
            "CREATE PROPERTY IF NOT EXISTS Relation.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.id STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.srcId STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.tgtId STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.keywords STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.description STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.weight DOUBLE",
            "CREATE PROPERTY IF NOT EXISTS Relation.sourceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Relation.filePath STRING",
            "CREATE INDEX IF NOT EXISTS ON Relation (workspaceId, id) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Relation (workspaceId, srcId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Relation (workspaceId, tgtId) NOTUNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS VectorEntry",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.namespace STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.id STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.embedding ARRAY_OF_FLOATS",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.searchableText STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.keywords STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.sparseTokens ARRAY_OF_INTEGERS",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.sparseWeights ARRAY_OF_FLOATS",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.srcId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.tgtId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.filePath STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.sourceId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.contentType STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.sectionPath STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.source STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.tenantId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.createdAt STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorEntry.searchable BOOLEAN",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, id) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, documentId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, tenantId) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, source) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, contentType) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, filePath) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (workspaceId, namespace, searchable) NOTUNIQUE",
            ("CREATE INDEX IF NOT EXISTS ON VectorEntry (embedding) LSM_VECTOR METADATA { dimensions: %d, similarity: 'COSINE' }")
                .formatted(config.vectorDimensions()),
            "CREATE INDEX IF NOT EXISTS ON VectorEntry (sparseTokens, sparseWeights) LSM_SPARSE_VECTOR METADATA { dimensions: 0, modifier: 'IDF' }",

            "CREATE DOCUMENT TYPE IF NOT EXISTS VectorMetadata",
            "CREATE PROPERTY IF NOT EXISTS VectorMetadata.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorMetadata.namespace STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorMetadata.vectorId STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorMetadata.field STRING",
            "CREATE PROPERTY IF NOT EXISTS VectorMetadata.value STRING",
            "CREATE INDEX IF NOT EXISTS ON VectorMetadata (workspaceId, namespace, vectorId, field) NOTUNIQUE",
            "CREATE INDEX IF NOT EXISTS ON VectorMetadata (workspaceId, namespace, field, value) NOTUNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS DocumentStatus",
            "CREATE PROPERTY IF NOT EXISTS DocumentStatus.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentStatus.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentStatus.status STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentStatus.summary STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentStatus.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON DocumentStatus (workspaceId, documentId) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS Task",
            "CREATE PROPERTY IF NOT EXISTS Task.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.taskId STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.taskType STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.status STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.requestedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.startedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.finishedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.summary STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.errorMessage STRING",
            "CREATE PROPERTY IF NOT EXISTS Task.cancelRequested BOOLEAN",
            "CREATE PROPERTY IF NOT EXISTS Task.metadata STRING",
            "CREATE INDEX IF NOT EXISTS ON Task (workspaceId, taskId) UNIQUE",
            "CREATE INDEX IF NOT EXISTS ON Task (workspaceId, status) NOTUNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS TaskStage",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.taskId STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.stage STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.status STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.sequence INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.startedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.finishedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.message STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskStage.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON TaskStage (workspaceId, taskId, stage) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS TaskDocument",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.taskId STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.status STRING",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.chunkCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.entityCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.relationCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.chunkVectorCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.entityVectorCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.relationVectorCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS TaskDocument.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON TaskDocument (workspaceId, taskId, documentId) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS DocumentGraphSnapshot",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.version INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.status STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.source STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.chunkCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.createdAt STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.updatedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphSnapshot.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON DocumentGraphSnapshot (workspaceId, documentId) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS ChunkGraphSnapshot",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.chunkId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.chunkOrder INTEGER",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.contentHash STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.extractStatus STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.entities STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.relations STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.updatedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphSnapshot.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON ChunkGraphSnapshot (workspaceId, documentId, chunkId) UNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS DocumentGraphJournal",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.snapshotVersion INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.status STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.lastMode STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.expectedEntityCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.expectedRelationCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.materializedEntityCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.materializedRelationCount INTEGER",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.lastFailureStage STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.createdAt STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.updatedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS DocumentGraphJournal.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON DocumentGraphJournal (workspaceId, documentId) NOTUNIQUE",

            "CREATE DOCUMENT TYPE IF NOT EXISTS ChunkGraphJournal",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.workspaceId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.documentId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.chunkId STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.snapshotVersion INTEGER",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.mergeStatus STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.graphStatus STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.expectedEntityKeys STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.expectedRelationKeys STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.materializedEntityKeys STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.materializedRelationKeys STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.lastFailureStage STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.updatedAt STRING",
            "CREATE PROPERTY IF NOT EXISTS ChunkGraphJournal.errorMessage STRING",
            "CREATE INDEX IF NOT EXISTS ON ChunkGraphJournal (workspaceId, documentId, chunkId) NOTUNIQUE"
        );
    }
}
