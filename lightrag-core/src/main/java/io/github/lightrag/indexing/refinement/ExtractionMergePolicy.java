package io.github.lightrag.indexing.refinement;

import io.github.lightrag.indexing.GraphAssembler;

import java.util.List;

public interface ExtractionMergePolicy {
    GraphAssembler.ChunkExtraction merge(PrimaryChunkExtraction primary, List<ChunkExtractionPatch> patchesForChunk);
}
