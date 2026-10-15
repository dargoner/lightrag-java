package io.github.lightrag.ontology;

/**
 * Marks a type that can produce a stable cross-repository identity key.
 */
interface HasIdentity {
    String identityId();
}
