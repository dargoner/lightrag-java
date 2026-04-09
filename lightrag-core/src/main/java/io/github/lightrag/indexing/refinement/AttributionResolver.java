package io.github.lightrag.indexing.refinement;

import java.util.List;

public interface AttributionResolver {
    List<ChunkExtractionPatch> distribute(RefinedWindowExtraction refinedWindow, RefinementWindow window);
}
