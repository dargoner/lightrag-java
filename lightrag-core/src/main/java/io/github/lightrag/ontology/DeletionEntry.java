package io.github.lightrag.ontology;

import java.util.Comparator;
import java.util.Objects;

/**
 * Typed deletion entry used by {@link DeletionManifest}.
 *
 * <p>Entries are canonically ordered by kind and then by their identifying
 * fields, which makes manifests suitable for cross-repository byte comparison.
 */
public sealed interface DeletionEntry {

    /**
     * Kind of ontology element affected by a deletion.
     */
    enum DeletionKind {
        ENTITY,
        RELATION,
        PROPERTY,
        PATTERN
    }

    /**
     * Kind of element that owns a deleted property.
     */
    enum OwnerKind {
        ENTITY,
        RELATION
    }

    /** Total order used to canonicalize deletion manifests. */
    Comparator<DeletionEntry> CANONICAL_ORDER = Comparator
        .comparing(DeletionEntry::kind)
        .thenComparing(DeletionEntry::sortKey);

    /** Kind of deletion. */
    DeletionKind kind();

    /** String key that determines the canonical order within a kind. */
    String sortKey();

    record Entity(String identityId) implements DeletionEntry {
        public Entity {
            Objects.requireNonNull(identityId, "identityId");
            identityId = Identity.identityId(identityId);
        }

        @Override
        public DeletionKind kind() {
            return DeletionKind.ENTITY;
        }

        @Override
        public String sortKey() {
            return identityId;
        }
    }

    record Relation(String identityId) implements DeletionEntry {
        public Relation {
            Objects.requireNonNull(identityId, "identityId");
            identityId = Identity.identityId(identityId);
        }

        @Override
        public DeletionKind kind() {
            return DeletionKind.RELATION;
        }

        @Override
        public String sortKey() {
            return identityId;
        }
    }

    record Property(OwnerKind ownerKind, String ownerIdentityId, String propertyIdentityId)
        implements DeletionEntry {
        public Property {
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(ownerIdentityId, "ownerIdentityId");
            Objects.requireNonNull(propertyIdentityId, "propertyIdentityId");
            ownerIdentityId = Identity.identityId(ownerIdentityId);
            propertyIdentityId = Identity.identityId(propertyIdentityId);
        }

        @Override
        public DeletionKind kind() {
            return DeletionKind.PROPERTY;
        }

        @Override
        public String sortKey() {
            return ownerKind.name() + "\u0000" + ownerIdentityId + "\u0000" + propertyIdentityId;
        }
    }

    record Pattern(String ownerRelIdentityId, String sourceIdentityId, String targetIdentityId)
        implements DeletionEntry {
        public Pattern {
            Objects.requireNonNull(ownerRelIdentityId, "ownerRelIdentityId");
            Objects.requireNonNull(sourceIdentityId, "sourceIdentityId");
            Objects.requireNonNull(targetIdentityId, "targetIdentityId");
            ownerRelIdentityId = Identity.identityId(ownerRelIdentityId);
            sourceIdentityId = Identity.identityId(sourceIdentityId);
            targetIdentityId = Identity.identityId(targetIdentityId);
        }

        @Override
        public DeletionKind kind() {
            return DeletionKind.PATTERN;
        }

        @Override
        public String sortKey() {
            return ownerRelIdentityId + "\u0000" + sourceIdentityId + "\u0000" + targetIdentityId;
        }
    }
}
