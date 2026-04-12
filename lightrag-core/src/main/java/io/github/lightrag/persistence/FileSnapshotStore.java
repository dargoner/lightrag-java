package io.github.lightrag.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.SnapshotStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class FileSnapshotStore implements SnapshotStore {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final ConcurrentNavigableMap<String, Path> knownSnapshots = new ConcurrentSkipListMap<>();

    @Override
    public void save(Path path, Snapshot snapshot) {
        var manifestPath = normalize(path);
        var payloadPath = payloadPathFor(manifestPath);
        var manifest = new SnapshotManifest(
            SnapshotManifest.CURRENT_SCHEMA_VERSION,
            Instant.now().toString(),
            payloadPath.getFileName().toString()
        );
        var payload = SnapshotPayload.fromSnapshot(Objects.requireNonNull(snapshot, "snapshot"));

        try {
            createParentDirectories(manifestPath);
            writeAtomically(payloadPath, payload);
            writeAtomically(manifestPath, manifest);
            knownSnapshots.put(manifestPath.toString(), manifestPath);
        } catch (IOException exception) {
            throw new StorageException("Failed to save snapshot: " + manifestPath, exception);
        }
    }

    @Override
    public Snapshot load(Path path) {
        var manifestPath = normalize(path);
        try {
            var manifest = readManifest(manifestPath);
            var payload = readPayload(manifestPath.resolveSibling(manifest.payloadFile()));
            knownSnapshots.put(manifestPath.toString(), manifestPath);
            return payload.toSnapshot();
        } catch (NoSuchElementException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new StorageException("Failed to load snapshot: " + manifestPath, exception);
        }
    }

    @Override
    public List<Path> list() {
        return List.copyOf(knownSnapshots.values());
    }

    SnapshotManifest readManifest(Path path) {
        var manifestPath = normalize(path);
        try (InputStream inputStream = openExistingFile(manifestPath)) {
            return OBJECT_MAPPER.readValue(inputStream, SnapshotManifest.class);
        } catch (NoSuchElementException exception) {
            throw new NoSuchElementException("No snapshot stored for path: " + manifestPath);
        } catch (IOException exception) {
            throw new StorageException("Failed to read snapshot manifest: " + manifestPath, exception);
        }
    }

    private static SnapshotPayload readPayload(Path payloadPath) throws IOException {
        try (InputStream inputStream = openExistingFile(payloadPath)) {
            return OBJECT_MAPPER.readValue(inputStream, SnapshotPayload.class);
        } catch (NoSuchElementException exception) {
            throw new NoSuchElementException("No snapshot payload stored for path: " + payloadPath);
        }
    }

    private static void writeAtomically(Path path, Object value) throws IOException {
        var tempPath = path.resolveSibling(path.getFileName().toString() + ".tmp");
        OBJECT_MAPPER.writeValue(tempPath.toFile(), value);
        moveAtomically(tempPath, path);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void createParentDirectories(Path path) throws IOException {
        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static InputStream openExistingFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new NoSuchElementException("No snapshot stored for path: " + path);
        }
        return Files.newInputStream(path);
    }

    private static Path payloadPathFor(Path manifestPath) {
        var fileName = manifestPath.getFileName().toString();
        var separator = fileName.lastIndexOf('.');
        if (separator > 0) {
            fileName = fileName.substring(0, separator);
        }
        return manifestPath.resolveSibling(fileName + ".payload.json");
    }

    private static Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }
}
