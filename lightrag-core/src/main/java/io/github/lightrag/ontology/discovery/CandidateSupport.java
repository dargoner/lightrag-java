package io.github.lightrag.ontology.discovery;

import java.util.List;
import java.util.Objects;

/**
 * Support evidence for one ontology candidate observed during discovery.
 *
 * <p>{@code identityKey} is the normalized candidate identity. Properties are
 * owned by an entity or relation type, so their key omits the owner; the owner
 * is carried by {@code ownerKind}/{@code ownerKey}. Pattern identity combines
 * the owner relation with the normalized source and target identities.
 *
 * @param kind candidate kind: {@code ENTITY}, {@code RELATION}, {@code PROPERTY}, or {@code PATTERN}
 * @param ownerKind owning item kind for properties/patterns, otherwise empty
 * @param ownerKey normalized owner identity, otherwise empty
 * @param identityKey normalized candidate identity
 * @param label display label as proposed by the model
 * @param supportCount number of distinct sampled chunks that proposed the candidate
 * @param supportDocCount number of distinct documents that proposed the candidate
 * @param chunkIds sampled chunk identifiers that proposed the candidate
 * @param documentIds document identifiers that proposed the candidate
 */
public record CandidateSupport(
    String kind,
    String ownerKind,
    String ownerKey,
    String identityKey,
    String label,
    int supportCount,
    int supportDocCount,
    List<String> chunkIds,
    List<String> documentIds
) {
    public CandidateSupport {
        kind = Objects.requireNonNull(kind, "kind");
        ownerKind = ownerKind == null ? "" : ownerKind;
        ownerKey = ownerKey == null ? "" : ownerKey;
        identityKey = Objects.requireNonNull(identityKey, "identityKey");
        label = label == null ? "" : label;
        if (supportCount < 1 || supportDocCount < 1) {
            throw new IllegalArgumentException("support counts must be positive");
        }
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
        documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
    }
}
