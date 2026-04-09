package io.github.lightrag.indexing.refinement;

import java.util.List;
import java.util.Optional;

public interface RefinementWindowResolver {
    Optional<RefinementWindow> resolve(List<PrimaryChunkExtraction> extractions, int index, GapAssessment assessment);
}
