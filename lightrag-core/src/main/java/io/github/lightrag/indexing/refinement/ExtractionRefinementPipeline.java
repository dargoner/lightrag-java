package io.github.lightrag.indexing.refinement;

import io.github.lightrag.indexing.GraphAssembler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

public final class ExtractionRefinementPipeline {
    private final ExtractionRefinementOptions options;
    private final ExtractionGapDetector detector;
    private final RefinementWindowResolver windowResolver;
    private final BiFunction<RefinementWindow, List<PrimaryChunkExtraction>, RefinedWindowExtraction> refiner;
    private final AttributionResolver attributionResolver;
    private final ExtractionMergePolicy mergePolicy;

    public ExtractionRefinementPipeline(
        ExtractionRefinementOptions options,
        ExtractionGapDetector detector,
        RefinementWindowResolver windowResolver,
        BiFunction<RefinementWindow, List<PrimaryChunkExtraction>, RefinedWindowExtraction> refiner,
        AttributionResolver attributionResolver,
        ExtractionMergePolicy mergePolicy
    ) {
        this.options = Objects.requireNonNull(options, "options");
        this.detector = Objects.requireNonNull(detector, "detector");
        this.windowResolver = Objects.requireNonNull(windowResolver, "windowResolver");
        this.refiner = Objects.requireNonNull(refiner, "refiner");
        this.attributionResolver = Objects.requireNonNull(attributionResolver, "attributionResolver");
        this.mergePolicy = Objects.requireNonNull(mergePolicy, "mergePolicy");
    }

    public List<GraphAssembler.ChunkExtraction> refine(List<PrimaryChunkExtraction> primaryExtractions) {
        var primary = List.copyOf(Objects.requireNonNull(primaryExtractions, "primaryExtractions"));
        if (!options.enabled()) {
            return primary.stream()
                .map(extraction -> new GraphAssembler.ChunkExtraction(extraction.chunk().id(), extraction.extraction()))
                .toList();
        }

        var patchesByChunkId = new LinkedHashMap<String, List<ChunkExtractionPatch>>();
        int refinements = 0;
        for (int index = 0; index < primary.size() && refinements < options.maxRefinementPerDocument(); index++) {
            var assessment = detector.assess(primary, index);
            if (!meetsThresholds(assessment)) {
                continue;
            }
            var window = windowResolver.resolve(primary, index, assessment).orElse(null);
            if (window == null || exceedsWindowLimits(window)) {
                continue;
            }
            var refined = refiner.apply(window, primary);
            for (var patch : attributionResolver.distribute(refined, window)) {
                patchesByChunkId.computeIfAbsent(patch.chunkId(), ignored -> new ArrayList<>()).add(patch);
            }
            refinements++;
        }
        return primary.stream()
            .map(extraction -> mergePolicy.merge(
                extraction,
                patchesByChunkId.getOrDefault(extraction.chunk().id(), List.of())
            ))
            .toList();
    }

    private boolean meetsThresholds(GapAssessment assessment) {
        return assessment.requiresRefinement()
            && assessment.prescreenSignals().size() >= options.minPrescreenSignals()
            && assessment.qualitySignals().size() >= options.minQualitySignals();
    }

    private boolean exceedsWindowLimits(RefinementWindow window) {
        return window.chunks().size() > options.maxWindowChunks()
            || window.estimatedTokenCount() > options.maxWindowTokens();
    }
}
