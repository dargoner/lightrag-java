package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, canonically ordered set of ontology deletion entries.
 *
 * <p>Manifests support precise equality and a deterministic order, which makes
 * them suitable for cross-repository byte comparison.
 */
public record DeletionManifest(List<DeletionEntry> entries) {

    public DeletionManifest {
        Objects.requireNonNull(entries, "entries");
        List<DeletionEntry> sorted = new ArrayList<>(entries);
        sorted.sort(DeletionEntry.CANONICAL_ORDER);
        entries = List.copyOf(sorted);
    }

    /** Returns an empty manifest. */
    public static DeletionManifest empty() {
        return new DeletionManifest(List.of());
    }

    /** Creates a manifest from the supplied entries. */
    public static DeletionManifest of(DeletionEntry... entries) {
        return new DeletionManifest(List.of(entries));
    }

    /** Creates a manifest from the supplied collection of entries. */
    public static DeletionManifest of(Collection<? extends DeletionEntry> entries) {
        return new DeletionManifest(new ArrayList<>(entries));
    }

    /** Returns a new manifest that additionally contains the supplied entries. */
    public DeletionManifest withEntries(Collection<? extends DeletionEntry> additionalEntries) {
        List<DeletionEntry> combined = new ArrayList<>(this.entries);
        combined.addAll(additionalEntries);
        return new DeletionManifest(combined);
    }

    /** Returns true if this manifest contains no entries. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
