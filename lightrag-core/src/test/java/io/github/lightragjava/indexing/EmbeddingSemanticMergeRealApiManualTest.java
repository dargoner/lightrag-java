package io.github.lightragjava.indexing;

import io.github.lightragjava.api.ChunkGranularity;
import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.types.Document;
import io.github.lightragjava.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EmbeddingSemanticMergeRealApiManualTest {
    private static final String GENERIC_DENSE_URL = "https://www.gutenberg.org/files/31632/31632-pdf.pdf";

    @Test
    void embeddingDrivenMergeWorksForGenericDensePublicPdf() {
        var parsed = parsePublicUrl("generic-dense.pdf", "application/pdf", GENERIC_DENSE_URL);
        var source = new Document(parsed.documentId(), parsed.title(), parsed.plainText(), parsed.metadata());
        var baselineChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(false)
            .semanticMergeThreshold(0.12d)
            .build());
        var baselineChunks = baselineChunker.chunk(source);
        var mergePair = firstMergeablePair(baselineChunks, 320);

        var embeddingModel = new DeterministicEmbeddingModel(vectorsFor(baselineChunks, mergePair.leftIndex(), mergePair.rightIndex()));
        var preparationStrategy = new SmartChunkerEmbeddingPreparationStrategy(
            new SemanticChunkRefiner(),
            new EmbeddingChunkSimilarityScorer(new EmbeddingBatcher(embeddingModel, 64)),
            0.95d
        );

        var prepared = preparationStrategy.prepare(source, baselineChunker);

        System.out.println("[EMBEDDING_GENERIC] parsed_blocks=" + parsed.blocks().size());
        System.out.println("[EMBEDDING_GENERIC] baseline_chunks=" + baselineChunks.size());
        System.out.println("[EMBEDDING_GENERIC] prepared_chunks=" + prepared.size());
        System.out.println("[EMBEDDING_GENERIC] merged_left=" + preview(mergePair.left().text()));
        System.out.println("[EMBEDDING_GENERIC] merged_right=" + preview(mergePair.right().text()));
        prepared.stream()
            .limit(5)
            .forEach(chunk -> System.out.println(
                "[EMBEDDING_GENERIC] chunk order=" + chunk.order()
                    + " section=" + chunk.metadata().getOrDefault("smart_chunker.section_path", "")
                    + " type=" + chunk.metadata().getOrDefault("smart_chunker.content_type", "")
                    + " text=" + preview(chunk.text())
            ));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(baselineChunks.size()).isGreaterThan(prepared.size());
        assertThat(prepared).hasSize(baselineChunks.size() - 1);
        assertThat(prepared)
            .extracting(io.github.lightragjava.types.Chunk::text)
            .contains(mergePair.left().text() + "\n" + mergePair.right().text());
        assertThat(embeddingModel.batchSizes()).containsExactly(64, 64, 64, 64, 52);
    }

    private static ParsedDocument parsePublicUrl(String fileName, String mediaType, String sourceUrl) {
        var parsingOrchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new MineruApiClient(new MineruApiClient.HttpTransport(
                    "https://mineru.net/api/v4/extract/task",
                    mineruApiKey(),
                    Duration.ofSeconds(90),
                    1_000,
                    90
                )),
                new MineruDocumentAdapter()
            ),
            null
        );
        return parsingOrchestrator.parse(
            RawDocumentSource.bytes(
                fileName,
                new byte[] {1},
                mediaType,
                Map.of(MineruApiClient.SOURCE_URL_METADATA_KEY, sourceUrl)
            ),
            new DocumentIngestOptions(
                DocumentTypeHint.GENERIC,
                ChunkGranularity.MEDIUM,
                ChunkingStrategyOverride.SMART,
                RegexChunkerConfig.empty(),
                ParentChildProfile.disabled()
            )
        );
    }

    private static AdjacentPair firstMergeablePair(List<io.github.lightragjava.types.Chunk> chunks, int maxTokens) {
        for (int index = 0; index + 1 < chunks.size(); index++) {
            var left = chunks.get(index);
            var right = chunks.get(index + 1);
            if (!left.metadata().getOrDefault("smart_chunker.section_path", "")
                .equals(right.metadata().getOrDefault("smart_chunker.section_path", ""))) {
                continue;
            }
            if (!"text".equals(left.metadata().get("smart_chunker.content_type"))
                || !"text".equals(right.metadata().get("smart_chunker.content_type"))) {
                continue;
            }
            var combined = left.text() + "\n" + right.text();
            if (combined.codePointCount(0, combined.length()) > maxTokens) {
                continue;
            }
            return new AdjacentPair(index, index + 1, left, right);
        }
        throw new IllegalStateException("No mergeable adjacent generic chunks found");
    }

    private static Map<String, List<Double>> vectorsFor(
        List<io.github.lightragjava.types.Chunk> chunks,
        int mergeLeftIndex,
        int mergeRightIndex
    ) {
        int dimensions = chunks.size() + 1;
        var vectors = new LinkedHashMap<String, List<Double>>();
        for (int index = 0; index < chunks.size(); index++) {
            int anchor = index + 1;
            if (index == mergeLeftIndex || index == mergeRightIndex) {
                anchor = 0;
            }
            var vector = new ArrayList<Double>(dimensions);
            for (int dimension = 0; dimension < dimensions; dimension++) {
                vector.add(dimension == anchor ? 1.0d : 0.0d);
            }
            vectors.put(chunks.get(index).text(), List.copyOf(vector));
        }
        return Map.copyOf(vectors);
    }

    private static String mineruApiKey() {
        var apiKey = System.getenv("MINERU_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "MINERU_API_KEY is required for this manual test");
        return apiKey;
    }

    private static String preview(String text) {
        var normalized = text.replaceAll("\\s+", " ").strip();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private record AdjacentPair(
        int leftIndex,
        int rightIndex,
        io.github.lightragjava.types.Chunk left,
        io.github.lightragjava.types.Chunk right
    ) {
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {
        private final Map<String, List<Double>> vectorsByText;
        private final List<Integer> batchSizes = new ArrayList<>();

        private DeterministicEmbeddingModel(Map<String, List<Double>> vectorsByText) {
            this.vectorsByText = Map.copyOf(vectorsByText);
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            batchSizes.add(texts.size());
            return texts.stream()
                .map(text -> {
                    var vector = vectorsByText.get(text);
                    if (vector == null) {
                        throw new IllegalStateException("Missing embedding for text: " + text);
                    }
                    return vector;
                })
                .toList();
        }

        private List<Integer> batchSizes() {
            return List.copyOf(batchSizes);
        }
    }
}
