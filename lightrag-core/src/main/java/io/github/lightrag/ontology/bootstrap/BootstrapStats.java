package io.github.lightrag.ontology.bootstrap;

/**
 * Counters describing what deterministic bootstrap observed.
 *
 * @param entitySamples                  number of input entity sample rows
 * @param relationSamples                number of input relation sample rows
 * @param duplicateSamples               extra rows removed by deduplication
 * @param conflictingDuplicateAttributes duplicated attribute keys dropped
 *                                       because their values disagreed; counted
 *                                       once per deduplication key and attribute
 * @param droppedUngatedEndpoints        endpoint resolutions with no gated type
 * @param droppedAmbiguousEndpoints      endpoint resolutions with multiple gated
 *                                       types
 * @param droppedUnconstrainedRelationTypes gated relation types without any
 *                                       pattern that passed its gate
 */
public record BootstrapStats(
    int entitySamples,
    int relationSamples,
    int duplicateSamples,
    int conflictingDuplicateAttributes,
    int droppedUngatedEndpoints,
    int droppedAmbiguousEndpoints,
    int droppedUnconstrainedRelationTypes
) {}
