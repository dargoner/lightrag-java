package io.github.lightrag.ontology;

import java.util.Set;

/**
 * Labels reserved by the kernel and the extraction fallback policy.
 *
 * <p>Kernel labels are the structural concepts every backend materializes
 * (chunk, document, and graph entity/relation records); an ontology element
 * whose identity collides with one of them would be indistinguishable from
 * kernel data. The extraction fallback labels are reserved by the conformance
 * validator's {@code Other} policy.
 */
public final class ReservedLabels {

    private static final Set<String> KERNEL = Set.of(
        Identity.identityId("entity"),
        Identity.identityId("relation"),
        Identity.identityId("chunk"),
        Identity.identityId("document")
    );

    private static final Set<String> EXTRACTION_FALLBACK = Set.of(
        Identity.identityId("Other"),
        Identity.identityId("Unknown")
    );

    private ReservedLabels() {}

    /** Returns true when the identity collides with a kernel structural concept. */
    public static boolean isKernelReserved(String identityId) {
        return identityId != null && KERNEL.contains(identityId);
    }

    /** Returns true when the identity collides with an extraction fallback label. */
    public static boolean isExtractionFallback(String identityId) {
        return identityId != null && EXTRACTION_FALLBACK.contains(identityId);
    }
}
