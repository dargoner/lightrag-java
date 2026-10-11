package io.github.lightrag.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;

/**
 * The self-contained single-file JSON artifact exchanged by {@code LightRag.exportData}/{@code LightRag.importData}:
 * a versioned envelope inlining the {@link SnapshotPayload}, so the artifact travels over any byte channel (HTTP
 * body, object storage, message queue) without a shared filesystem or a configured {@link SnapshotStore}. Unlike
 * {@link FileSnapshotStore}, the envelope is a single document and never carries vectors.
 */
public record ContentExport(int schemaVersion, String createdAt, SnapshotPayload payload) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    public ContentExport {
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        payload = Objects.requireNonNull(payload, "payload");
    }

    public static ContentExport of(SnapshotStore.Snapshot snapshot) {
        return new ContentExport(
            CURRENT_SCHEMA_VERSION,
            Instant.now().toString(),
            SnapshotPayload.fromSnapshot(Objects.requireNonNull(snapshot, "snapshot"))
        );
    }

    public SnapshotStore.Snapshot toSnapshot() {
        return payload.toSnapshot();
    }

    public static byte[] writeBytes(SnapshotStore.Snapshot snapshot) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(of(snapshot));
        } catch (IOException exception) {
            throw new StorageException("Failed to write content export", exception);
        }
    }

    /** Writes the artifact to a caller-owned stream, which is flushed but not closed. */
    public static void write(OutputStream outputStream, SnapshotStore.Snapshot snapshot) {
        var target = Objects.requireNonNull(outputStream, "outputStream");
        try {
            target.write(writeBytes(snapshot));
            target.flush();
        } catch (IOException exception) {
            throw new StorageException("Failed to write content export", exception);
        }
    }

    public static void write(Path path, SnapshotStore.Snapshot snapshot) {
        var target = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        try {
            var parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tempPath = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(tempPath, writeBytes(snapshot));
            moveAtomically(tempPath, target);
        } catch (IOException exception) {
            throw new StorageException("Failed to write content export: " + target, exception);
        }
    }

    public static SnapshotStore.Snapshot read(byte[] data) {
        var source = Objects.requireNonNull(data, "data");
        try {
            return parse(OBJECT_MAPPER.readValue(source, ContentExport.class));
        } catch (IOException exception) {
            throw new StorageException("Failed to read content export", exception);
        }
    }

    /** Reads the artifact from a caller-owned stream, which is left open. */
    public static SnapshotStore.Snapshot read(InputStream inputStream) {
        var source = Objects.requireNonNull(inputStream, "inputStream");
        try {
            return read(source.readAllBytes());
        } catch (IOException exception) {
            throw new StorageException("Failed to read content export", exception);
        }
    }

    public static SnapshotStore.Snapshot read(Path path) {
        var source = Objects.requireNonNull(path, "path");
        try {
            return read(Files.readAllBytes(source));
        } catch (IOException exception) {
            throw new StorageException("Failed to read content export: " + source, exception);
        }
    }

    private static SnapshotStore.Snapshot parse(ContentExport export) {
        if (export.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new StorageException(
                "Unsupported content export schemaVersion: " + export.schemaVersion()
                    + " (supported: " + CURRENT_SCHEMA_VERSION + ")"
            );
        }
        return export.toSnapshot();
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
