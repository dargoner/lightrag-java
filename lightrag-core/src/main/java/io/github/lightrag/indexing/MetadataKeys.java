package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import java.util.Objects;

/**
 * Plain chunk/document metadata keys the indexing pipeline reads, in the {@link SmartChunkMetadata}
 * style.
 */
public final class MetadataKeys {
    public static final String FILE_PATH = "file_path";

    /** Upstream's sentinel for a chunk with no known source path ({@code base.py:1344}, {@code operate.py:714}). */
    public static final String DEFAULT_FILE_PATH = "unknown_source";

    private MetadataKeys() {
    }

    /** The chunk's {@code file_path}, or upstream's {@code unknown_source} sentinel when absent. */
    public static String filePathOf(Chunk chunk) {
        var value = Objects.requireNonNull(chunk, "chunk").metadata().get(FILE_PATH);
        return value == null || value.isBlank() ? DEFAULT_FILE_PATH : value.strip();
    }
}
