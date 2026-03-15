package io.github.lightragjava.storage;

import java.util.List;

public interface RollbackCapableChunkStore extends ChunkStore {
    void restoreChunks(List<ChunkRecord> snapshot);
}
