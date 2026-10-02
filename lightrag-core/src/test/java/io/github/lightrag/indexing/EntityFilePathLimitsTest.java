package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.Entity;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityFilePathLimitsTest {
    @Test
    void mergesFilePathsAcrossBatchesDeduplicatingUnions() {
        // The COMPLETE entity merge (IndexingPipeline.mergeEntityWithCaps, extracted package-private so it
        // is directly testable): stored paths lead and the incoming batch appends its new ones
        // (operate.py:1939-1973), under the shared file-path cap (:2634-2689).
        var existing = new GraphStore.EntityRecord(
            "alice", "Alice", "person", "stored", List.of(), List.of("c1"), "/a.md<SEP>/b.md");
        var incoming = new Entity(
            "alice", "Alice", "person", "fresh", List.of(), List.of("c2"), "/b.md<SEP>/c.md");

        var merged = IndexingPipeline.mergeEntityWithCaps(
            existing,
            incoming,
            true,
            200,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            DescriptionSummarizer.withoutLlm()
        );

        assertThat(merged.filePaths()).containsExactly("/a.md", "/b.md", "/c.md");
        assertThat(merged.sourceChunkIds()).containsExactly("c1", "c2");
    }

    @Test
    void keepCapsToTheHeadAndFifoToTheTailWithUpstreamMarkers() {
        var existing = new GraphStore.EntityRecord(
            "alice", "Alice", "person", "", List.of(), List.of(), "/a.md<SEP>/b.md");
        var incoming = new Entity("alice", "Alice", "person", "", List.of(), List.of(), "/c.md<SEP>/d.md");

        var keep = IndexingPipeline.mergeEntityWithCaps(
            existing, incoming, true, 200, SourceIdLimits.Method.KEEP, 3, DescriptionSummarizer.withoutLlm());
        assertThat(keep.filePaths())
            .containsExactly("/a.md", "/b.md", "/c.md", "...truncated...(KEEP Old)");

        var fifo = IndexingPipeline.mergeEntityWithCaps(
            existing, incoming, true, 200, SourceIdLimits.Method.FIFO, 3, DescriptionSummarizer.withoutLlm());
        assertThat(fifo.filePaths())
            .containsExactly("/b.md", "/c.md", "/d.md", "...truncated...(FIFO)");
    }

    @Test
    void refeedsDoNotAccumulateTruncationMarkers() {
        var existing = new GraphStore.EntityRecord(
            "alice", "Alice", "person", "", List.of(), List.of(), "/a.md<SEP>/b.md");
        var incoming = new Entity("alice", "Alice", "person", "", List.of(), List.of(), "/c.md<SEP>/d.md");
        var once = IndexingPipeline.mergeEntityWithCaps(
            existing, incoming, true, 200, SourceIdLimits.Method.KEEP, 3, DescriptionSummarizer.withoutLlm());

        var again = IndexingPipeline.mergeEntityWithCaps(
            once, incoming, true, 200, SourceIdLimits.Method.KEEP, 3, DescriptionSummarizer.withoutLlm());

        assertThat(again.filePaths())
            .containsExactly("/a.md", "/b.md", "/c.md", "...truncated...(KEEP Old)");
    }

    @Test
    void keepsTheUnknownSourceSentinelAndDropsBlankEntries() {
        // Upstream's sentinel is a legitimate path value (MetadataKeys.DEFAULT_FILE_PATH); only missing or
        // blank entries are dropped.
        var existing = new GraphStore.EntityRecord(
            "openai", "OpenAI", "ORGANIZATION", "", List.of(), List.of(), "unknown_source");
        var incoming = new Entity("openai", "OpenAI", "ORGANIZATION", "", List.of(), List.of(), "   ");

        var merged = IndexingPipeline.mergeEntityWithCaps(
            existing,
            incoming,
            true,
            200,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            DescriptionSummarizer.withoutLlm()
        );

        assertThat(merged.filePaths()).containsExactly("unknown_source");
    }

    @Test
    void aFirstSeenEntityAlsoAppliesTheCap() {
        var entity = new Entity(
            "alice", "Alice", "person", "desc", List.of(), List.of("c1"), "/a.md<SEP>/b.md<SEP>/c.md<SEP>/d.md");

        var record = IndexingPipeline.newEntityRecord(
            entity, true, 200, SourceIdLimits.Method.FIFO, 3, DescriptionSummarizer.withoutLlm());

        assertThat(record.filePaths())
            .containsExactly("/b.md", "/c.md", "/d.md", "...truncated...(FIFO)");
    }
}
