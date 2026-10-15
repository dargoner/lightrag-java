package io.github.lightrag.ontology;

import java.util.Optional;

/**
 * Persistence backend for {@link Ontology} snapshots.
 *
 * <p>Implementations are responsible for storing and retrieving ontology
 * documents by a stable key. The codec layer ({@link OntologyJsonCodec}) owns
 * serialization and hashing concerns.
 */
public interface OntologyStore {

    /**
     * Loads the ontology associated with the given key.
     *
     * @param key storage key; must not be {@code null}
     * @return the stored ontology, or empty if none exists for the key
     */
    Optional<Ontology> load(String key);

    /**
     * Stores the ontology under the given key.
     *
     * @param key      storage key; must not be {@code null}
     * @param ontology ontology to store; must not be {@code null}
     */
    void save(String key, Ontology ontology);
}
