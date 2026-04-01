package io.github.lightrag.query;

import io.github.lightrag.indexing.ParentChildChunkBuilder;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ScoredChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class ParentChunkExpander {
    private static final String CONTENT_TYPE = "smart_chunker.content_type";
    private static final String SECTION_PATH = "smart_chunker.section_path";
    private static final String PREV_CHUNK_ID = "smart_chunker.prev_chunk_id";
    private static final String NEXT_CHUNK_ID = "smart_chunker.next_chunk_id";
    private static final String PRIMARY_IMAGE_PATH = "smart_chunker.primary_image_path";

    private static final String CONTENT_TYPE_CAPTION = "caption";
    private static final String CONTENT_TYPE_TEXT = "text";
    private static final String CONTENT_TYPE_IMAGE_PLACEHOLDER = "image_placeholder";

    private final ChunkStore chunkStore;

    ParentChunkExpander(ChunkStore chunkStore) {
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore");
    }

    List<ScoredChunk> expand(List<ScoredChunk> matchedChunks, int limit) {
        var expanded = new LinkedHashMap<String, ScoredChunk>();
        for (var matchedChunk : Objects.requireNonNull(matchedChunks, "matchedChunks")) {
            var resolved = resolve(matchedChunk);
            expanded.merge(
                resolved.chunkId(),
                resolved,
                ParentChunkExpander::mergeScores
            );
        }
        return expanded.values().stream()
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(ScoredChunk::chunkId))
            .limit(limit)
            .toList();
    }

    private static ScoredChunk mergeScores(ScoredChunk left, ScoredChunk right) {
        var preferredChunk = left.score() >= right.score() ? left.chunk() : right.chunk();
        return new ScoredChunk(left.chunkId(), preferredChunk, left.score() + right.score());
    }

    private ScoredChunk resolve(ScoredChunk scoredChunk) {
        var parentChunkId = scoredChunk.chunk().metadata().get(ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID);
        var resolved = scoredChunk;
        if (parentChunkId != null && !parentChunkId.isBlank()) {
            resolved = chunkStore.load(parentChunkId)
                .map(parent -> new ScoredChunk(parent.id(), toChunk(parent), scoredChunk.score()))
                .orElse(scoredChunk);
        }
        return expandCaptionNeighborhood(resolved);
    }

    private ScoredChunk expandCaptionNeighborhood(ScoredChunk scoredChunk) {
        if (!CONTENT_TYPE_CAPTION.equals(scoredChunk.chunk().metadata().get(CONTENT_TYPE))) {
            return scoredChunk;
        }
        var assembled = new ArrayList<Chunk>();
        appendCaptionLeadingContext(scoredChunk.chunk(), assembled);
        assembled.add(scoredChunk.chunk());
        appendCaptionTrailingContext(scoredChunk.chunk(), assembled);
        if (assembled.size() == 1) {
            return scoredChunk;
        }
        var mergedText = assembled.stream()
            .map(Chunk::text)
            .filter(text -> !text.isBlank())
            .distinct()
            .reduce((left, right) -> left + "\n" + right)
            .orElse(scoredChunk.chunk().text());
        var mergedTokenCount = assembled.stream()
            .mapToInt(Chunk::tokenCount)
            .sum();
        var mergedChunk = new Chunk(
            scoredChunk.chunkId(),
            scoredChunk.chunk().documentId(),
            mergedText,
            mergedTokenCount,
            scoredChunk.chunk().order(),
            scoredChunk.chunk().metadata()
        );
        return new ScoredChunk(scoredChunk.chunkId(), mergedChunk, scoredChunk.score());
    }

    private void appendCaptionLeadingContext(Chunk captionChunk, List<Chunk> assembled) {
        loadAdjacent(captionChunk, PREV_CHUNK_ID)
            .filter(candidate -> sameSection(captionChunk, candidate))
            .ifPresent(previous -> {
                if (isContentType(previous, CONTENT_TYPE_TEXT)) {
                    assembled.add(toChunk(previous));
                    return;
                }
                if (!isMatchingImagePlaceholder(captionChunk, previous)) {
                    return;
                }
                loadAdjacent(previous, PREV_CHUNK_ID)
                    .filter(candidate -> sameSection(captionChunk, candidate))
                    .filter(candidate -> isContentType(candidate, CONTENT_TYPE_TEXT))
                    .map(ParentChunkExpander::toChunk)
                    .ifPresent(assembled::add);
                assembled.add(toChunk(previous));
            });
    }

    private void appendCaptionTrailingContext(Chunk captionChunk, List<Chunk> assembled) {
        loadAdjacent(captionChunk, NEXT_CHUNK_ID)
            .filter(candidate -> sameSection(captionChunk, candidate))
            .filter(candidate -> isContentType(candidate, CONTENT_TYPE_TEXT))
            .map(ParentChunkExpander::toChunk)
            .ifPresent(assembled::add);
    }

    private boolean isMatchingImagePlaceholder(Chunk captionChunk, ChunkStore.ChunkRecord candidate) {
        if (!isContentType(candidate, CONTENT_TYPE_IMAGE_PLACEHOLDER)) {
            return false;
        }
        var captionImagePath = captionChunk.metadata().get(PRIMARY_IMAGE_PATH);
        var candidateImagePath = candidate.metadata().get(PRIMARY_IMAGE_PATH);
        return captionImagePath != null
            && !captionImagePath.isBlank()
            && captionImagePath.equals(candidateImagePath);
    }

    private java.util.Optional<ChunkStore.ChunkRecord> loadAdjacent(Chunk chunk, String pointerKey) {
        return loadAdjacent(chunk.metadata(), pointerKey);
    }

    private java.util.Optional<ChunkStore.ChunkRecord> loadAdjacent(ChunkStore.ChunkRecord chunk, String pointerKey) {
        return loadAdjacent(chunk.metadata(), pointerKey);
    }

    private java.util.Optional<ChunkStore.ChunkRecord> loadAdjacent(java.util.Map<String, String> metadata, String pointerKey) {
        var chunkId = metadata.get(pointerKey);
        if (chunkId == null || chunkId.isBlank()) {
            return java.util.Optional.empty();
        }
        return chunkStore.load(chunkId);
    }

    private static boolean sameSection(Chunk captionChunk, ChunkStore.ChunkRecord candidate) {
        return Objects.equals(captionChunk.metadata().get(SECTION_PATH), candidate.metadata().get(SECTION_PATH));
    }

    private static boolean isContentType(ChunkStore.ChunkRecord candidate, String contentType) {
        return contentType.equals(candidate.metadata().get(CONTENT_TYPE));
    }

    private static Chunk toChunk(ChunkStore.ChunkRecord chunk) {
        return new Chunk(
            chunk.id(),
            chunk.documentId(),
            chunk.text(),
            chunk.tokenCount(),
            chunk.order(),
            chunk.metadata()
        );
    }
}
