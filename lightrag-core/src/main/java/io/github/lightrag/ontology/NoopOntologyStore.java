package io.github.lightrag.ontology;

import java.util.Objects;
import java.util.Optional;

/**
 * No-op {@link OntologyStore} implementation.
 *
 * <p>{@link #load(String)} always returns {@link Optional#empty()} and
 * {@link #save(String, Ontology)} does nothing. Useful as a default or
 * placeholder in configurations where ontology persistence is disabled.
 */
public final class NoopOntologyStore implements OntologyStore {

    @Override
    public Optional<Ontology> load(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.empty();
    }

    @Override
    public void save(String key, Ontology ontology) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ontology, "ontology");
    }
}
