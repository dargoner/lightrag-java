package io.github.lightrag.ontology.bootstrap;

/**
 * One deterministic conflict detected while bootstrapping.
 *
 * @param ownerLabel canonical identity of the owning entity or relation sample
 *                   (or the inferred owner type)
 * @param item       canonical identity of the conflicting item, usually a
 *                   property or endpoint name
 * @param detail     deterministic human-readable explanation
 */
public record BootstrapConflict(
    String ownerLabel,
    String item,
    String detail
) {
    public BootstrapConflict {
        ownerLabel = ownerLabel == null ? "" : ownerLabel;
        item = item == null ? "" : item;
        detail = detail == null ? "" : detail;
    }
}
