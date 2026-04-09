package io.github.lightrag.indexing.refinement;

import java.util.List;

public interface ExtractionGapDetector {
    GapAssessment assess(List<PrimaryChunkExtraction> extractions, int index);
}
