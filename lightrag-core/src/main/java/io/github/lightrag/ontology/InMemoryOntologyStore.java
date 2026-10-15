package io.github.lightrag.ontology;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory {@link OntologyStore} backed by {@link ConcurrentHashMap}.
 *
 * <p>This store is intended for tests, caching, and single-node deployments.
 * It does not persist data across process restarts.
 */
public final class InMemoryOntologyStore implements OntologyStore {

    private final ConcurrentMap<String, Ontology> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Ontology> load(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void save(String key, Ontology ontology) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ontology, "ontology");
        store.put(key, ontology);
    }
}
