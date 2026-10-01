package io.github.lightrag.indexing;

import io.github.lightrag.storage.StorageProvider;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether a storage provider can back source-id caps with per-chunk attribution snapshots.
 *
 * <p>Capping a graph record's {@code sourceChunkIds} truncates the only evidence the incremental
 * deletion path used to consult, so caps are enabled only where the snapshot store that replaces that
 * evidence is available. The probe never replaces the capability with a silent truncation: a provider
 * that cannot answer keeps its source ids unbounded and logs one warning per adapter class.</p>
 */
public final class GraphSnapshotCapabilities {
    private static final Set<Class<?>> WARNED = ConcurrentHashMap.newKeySet();
    private static final Logger log = LoggerFactory.getLogger(GraphSnapshotCapabilities.class);

    private GraphSnapshotCapabilities() {
    }

    /**
     * Capability is a property of the provider INSTANCE, never of its class: StorageCoordinator
     * (public constructor, StorageCoordinator.java:25-33) forwards this call to a mutable
     * RelationalStorageAdapter delegate (:86-87) whose default implementation throws
     * (RelationalStorageAdapter.java:24-27), while the PostgreSQL/MySQL adapters return a live store.
     * Deliberately no memo at all: the only call sites are pipeline constructors, so the probe is
     * cheap, always correct, and a static map would either pin provider instances or key on equality,
     * which the capability does not follow.
     */
    public static boolean supportsDocumentGraphSnapshots(StorageProvider provider) {
        try {
            provider.documentGraphSnapshotStore();
            return true;
        } catch (UnsupportedOperationException exception) {
            return false;
        }
    }

    /**
     * {@code capsRequested && available}. The WARN is deduplicated per adapter class — warn volume is a
     * noise policy, and it never replaces the per-instance capability answer.
     */
    public static boolean resolveCapsEnabled(
        StorageProvider provider,
        int maxSourceIdsPerEntity,
        int maxSourceIdsPerRelation
    ) {
        var requested = maxSourceIdsPerEntity < Integer.MAX_VALUE
            || maxSourceIdsPerRelation < Integer.MAX_VALUE;
        if (!requested) {
            return false;
        }
        var available = supportsDocumentGraphSnapshots(provider);
        if (!available && WARNED.add(provider.getClass())) {
            log.warn(
                "LightRAG graph source-id caps requested (entity={}, relation={}) but storage provider {} "
                    + "has no document graph snapshot store; keeping source ids unbounded",
                maxSourceIdsPerEntity,
                maxSourceIdsPerRelation,
                provider.getClass().getName()
            );
        }
        return available;
    }

    /** Test seams: the warn set is process-wide static state; tests reset it and read a class back. */
    static void resetWarnOnceForTests() {
        WARNED.clear();
    }

    static boolean warnedOnce(Class<?> providerClass) {
        return WARNED.contains(providerClass);
    }
}
