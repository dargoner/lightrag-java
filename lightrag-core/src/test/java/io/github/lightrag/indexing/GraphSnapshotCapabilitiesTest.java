package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GraphSnapshotCapabilitiesTest {
    @AfterEach
    void resetWarnState() {
        GraphSnapshotCapabilities.resetWarnOnceForTests();
    }

    @Test
    void capabilityIsResolvedPerInstanceNotPerClass() {
        var supporting = new ToggleableStorageProvider();
        var unsupported = new ToggleableStorageProvider();
        unsupported.snapshotsAvailable = false;

        assertThat(GraphSnapshotCapabilities.supportsDocumentGraphSnapshots(supporting)).isTrue();
        assertThat(GraphSnapshotCapabilities.supportsDocumentGraphSnapshots(unsupported)).isFalse();
        // A class-keyed memo would have answered the first result for both instances; the probe is per
        // instance, so a second provider may legitimately disagree.
        assertThat(GraphSnapshotCapabilities.supportsDocumentGraphSnapshots(supporting)).isTrue();
    }

    @Test
    void warnFiresOncePerAdapterClass() {
        GraphSnapshotCapabilities.resetWarnOnceForTests();
        var logger = (Logger) LoggerFactory.getLogger(GraphSnapshotCapabilities.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var first = new ToggleableStorageProvider();
            first.snapshotsAvailable = false;
            var second = new ToggleableStorageProvider();
            second.snapshotsAvailable = false;

            assertThat(GraphSnapshotCapabilities.resolveCapsEnabled(first, 200, 200)).isFalse();
            assertThat(GraphSnapshotCapabilities.resolveCapsEnabled(second, 200, 200)).isFalse();

            // The seam alone only proves set membership; assert the emitter ran exactly once.
            var warns = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
            assertThat(warns).hasSize(1);
            assertThat(warns.get(0).getFormattedMessage()).contains("has no document graph snapshot store");
            assertThat(GraphSnapshotCapabilities.warnedOnce(ToggleableStorageProvider.class)).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void explicitOptOutSkipsTheProbeEntirely() {
        var provider = new ToggleableStorageProvider();

        assertThat(GraphSnapshotCapabilities.resolveCapsEnabled(provider, Integer.MAX_VALUE, Integer.MAX_VALUE))
            .isFalse();
        assertThat(provider.snapshotStoreProbes).isZero();
        assertThat(GraphSnapshotCapabilities.warnedOnce(ToggleableStorageProvider.class)).isFalse();
    }

    private static final class ToggleableStorageProvider implements StorageProvider {
        private final InMemoryStorageProvider delegate = InMemoryStorageProvider.create();
        private boolean snapshotsAvailable = true;
        private int snapshotStoreProbes;

        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        @Override
        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            snapshotStoreProbes++;
            if (!snapshotsAvailable) {
                throw new UnsupportedOperationException(
                    StorageProvider.DOCUMENT_GRAPH_SNAPSHOT_STORE_UNSUPPORTED_MESSAGE);
            }
            return delegate.documentGraphSnapshotStore();
        }
    }
}
