package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineRetrievalAccuracyTest {
    private static final String WORKSPACE = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "does", "for", "from", "how",
        "in", "is", "it", "of", "on", "or", "that", "the", "their", "to", "traditional",
        "what", "with"
    );

    @Test
    void naiveRetrievalShouldHitExpectedSourceDocumentsForSampleEvaluationDataset() throws Exception {
        var repositoryRoot = Path.of("").toAbsolutePath().normalize().getParent();
        var documentsDir = repositoryRoot.resolve("evaluation/ragas/sample_documents");
        var datasetPath = repositoryRoot.resolve("evaluation/ragas/sample_dataset.json");
        var testCases = loadTestCases(datasetPath);

        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new HashingEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();
        rag.ingest(WORKSPACE, loadEvaluationDocuments(documentsDir));

        var reports = new ArrayList<CaseReport>();
        for (var testCase : testCases) {
            var result = rag.query(WORKSPACE, QueryRequest.builder()
                .query(testCase.question())
                .mode(QueryMode.NAIVE)
                .chunkTopK(3)
                .build());
            var matchedDocumentIds = result.contexts().stream()
                .map(context -> documentIdFromSourceId(context.sourceId()))
                .toList();
            reports.add(new CaseReport(
                testCase.question(),
                testCase.expectedDocumentIds(),
                matchedDocumentIds,
                !matchedDocumentIds.isEmpty() && testCase.expectedDocumentIds().contains(matchedDocumentIds.get(0)),
                matchedDocumentIds.stream().anyMatch(testCase.expectedDocumentIds()::contains)
            ));
        }

        long top1Hits = reports.stream().filter(CaseReport::top1Hit).count();
        long top3Hits = reports.stream().filter(CaseReport::top3Hit).count();
        double top1HitRate = top1Hits / (double) reports.size();
        double top3HitRate = top3Hits / (double) reports.size();

        reports.forEach(report -> System.out.printf(
            Locale.ROOT,
            "[offline-retrieval] question=%s expected=%s matched=%s top1=%s top3=%s%n",
            report.question(),
            report.expectedDocumentIds(),
            report.matchedDocumentIds(),
            report.top1Hit(),
            report.top3Hit()
        ));
        System.out.printf(
            Locale.ROOT,
            "[offline-retrieval] top1HitRate=%.4f (%d/%d) top3HitRate=%.4f (%d/%d)%n",
            top1HitRate,
            top1Hits,
            reports.size(),
            top3HitRate,
            top3Hits,
            reports.size()
        );

        assertThat(top1HitRate).isGreaterThanOrEqualTo(4.0d / 6.0d);
        assertThat(top3HitRate).isEqualTo(1.0d);
    }

    private static List<TestCase> loadTestCases(Path datasetPath) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(datasetPath.toFile());
        var testCases = new ArrayList<TestCase>();
        int index = 0;
        for (JsonNode node : root.path("test_cases")) {
            testCases.add(new TestCase(
                node.path("question").asText(),
                expectedDocumentIdsFor(index++)
            ));
        }
        return List.copyOf(testCases);
    }

    private static List<io.github.lightrag.types.Document> loadEvaluationDocuments(Path documentsDir) throws Exception {
        try (var paths = Files.list(documentsDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().matches("\\d+_.*\\.md"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(RagasEvaluationService::toDocument)
                .toList();
        }
    }

    private static Set<String> expectedDocumentIdsFor(int index) {
        return switch (index) {
            case 0 -> Set.of("01-lightrag-overview");
            case 1 -> Set.of("02-rag-architecture");
            case 2 -> Set.of("03-lightrag-improvements");
            case 3 -> Set.of("04-supported-databases");
            case 4 -> Set.of("05-evaluation-and-deployment");
            case 5 -> Set.of("01-lightrag-overview", "03-lightrag-improvements");
            default -> throw new IllegalArgumentException("Unexpected dataset case index: " + index);
        };
    }

    private static String documentIdFromSourceId(String sourceId) {
        int separator = sourceId.indexOf(':');
        return separator >= 0 ? sourceId.substring(0, separator) : sourceId;
    }

    private record TestCase(String question, Set<String> expectedDocumentIds) {
    }

    private record CaseReport(
        String question,
        Set<String> expectedDocumentIds,
        List<String> matchedDocumentIds,
        boolean top1Hit,
        boolean top3Hit
    ) {
    }

    private static final class NoOpChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("Document Chunks")) {
                return "offline retrieval evaluation";
            }
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class HashingEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(HashingEmbeddingModel::embed).toList();
        }

        private static List<Double> embed(String text) {
            double[] vector = new double[VECTOR_DIMENSIONS];
            var tokens = tokens(text);
            for (String token : tokens) {
                int slot = Math.floorMod(token.hashCode(), VECTOR_DIMENSIONS);
                vector[slot] += 1.0d;
            }
            for (int index = 0; index + 1 < tokens.size(); index++) {
                var bigram = tokens.get(index) + "::" + tokens.get(index + 1);
                int slot = Math.floorMod(bigram.hashCode(), VECTOR_DIMENSIONS);
                vector[slot] += 1.5d;
            }
            double norm = 0.0d;
            for (double value : vector) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm == 0.0d) {
                norm = 1.0d;
            }
            var normalized = new ArrayList<Double>(VECTOR_DIMENSIONS);
            for (double value : vector) {
                normalized.add(value / norm);
            }
            return List.copyOf(normalized);
        }

        private static List<String> tokens(String text) {
            var uniqueTokens = new LinkedHashSet<String>();
            for (String rawToken : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
                if (rawToken.isBlank() || STOP_WORDS.contains(rawToken)) {
                    continue;
                }
                uniqueTokens.add(rawToken);
            }
            return List.copyOf(uniqueTokens);
        }
    }
}
