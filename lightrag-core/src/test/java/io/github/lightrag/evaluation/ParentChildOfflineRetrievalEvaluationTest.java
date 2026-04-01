package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.ChunkGranularity;
import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.indexing.ChunkingStrategyOverride;
import io.github.lightrag.indexing.DocumentTypeHint;
import io.github.lightrag.indexing.ParentChildProfile;
import io.github.lightrag.indexing.RegexChunkerConfig;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

class ParentChildOfflineRetrievalEvaluationTest {
    private static final String WORKSPACE = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}a-z0-9]+");
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "与", "和", "及", "包括", "便于", "后续"
    );

    @Test
    void parentChildProfileImprovesTop1HitRateOnSectionAwareDataset() throws Exception {
        var repositoryRoot = Path.of("").toAbsolutePath().normalize().getParent();
        var documentsDir = repositoryRoot.resolve("evaluation/parent_child/sample_documents");
        var datasetPath = repositoryRoot.resolve("evaluation/parent_child/sample_dataset.json");
        var testCases = loadTestCases(datasetPath);

        var disabledReports = evaluate(
            documentsDir,
            testCases,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.disabled()
        );
        var enabledReports = evaluate(
            documentsDir,
            testCases,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.enabled(18, 4)
        );

        var disabledTop1HitRate = hitRate(disabledReports);
        var enabledTop1HitRate = hitRate(enabledReports);

        disabledReports.forEach(report -> System.out.printf(
            Locale.ROOT,
            "[parent-child-offline][disabled] question=%s expected=%s matched=%s hit=%s%n",
            report.question(),
            report.expectedDocumentId(),
            report.matchedDocumentId(),
            report.top1Hit()
        ));
        enabledReports.forEach(report -> System.out.printf(
            Locale.ROOT,
            "[parent-child-offline][enabled] question=%s expected=%s matched=%s hit=%s%n",
            report.question(),
            report.expectedDocumentId(),
            report.matchedDocumentId(),
            report.top1Hit()
        ));
        System.out.printf(
            Locale.ROOT,
            "[parent-child-offline] disabledTop1=%.4f enabledTop1=%.4f%n",
            disabledTop1HitRate,
            enabledTop1HitRate
        );

        assertThat(disabledTop1HitRate).isLessThan(enabledTop1HitRate);
        assertThat(disabledTop1HitRate).isEqualTo(1.0d / 3.0d);
        assertThat(enabledTop1HitRate).isEqualTo(1.0d);
    }

    @Test
    void reportsFixedSmartAndHierarchicalRetrievalComparisonOnSectionAwareDataset() throws Exception {
        var repositoryRoot = Path.of("").toAbsolutePath().normalize().getParent();
        var documentsDir = repositoryRoot.resolve("evaluation/parent_child/sample_documents");
        var datasetPath = repositoryRoot.resolve("evaluation/parent_child/sample_dataset.json");
        var testCases = loadTestCases(datasetPath);

        var fixedReports = evaluate(
            documentsDir,
            testCases,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.FIXED,
            ParentChildProfile.disabled()
        );
        var smartReports = evaluate(
            documentsDir,
            testCases,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.disabled()
        );
        var hierarchicalReports = evaluate(
            documentsDir,
            testCases,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.enabled(18, 4)
        );

        var fixedTop1 = hitRate(fixedReports);
        var smartTop1 = hitRate(smartReports);
        var hierarchicalTop1 = hitRate(hierarchicalReports);

        System.out.printf(
            Locale.ROOT,
            "[parent-child-compare] fixedTop1=%.4f smartTop1=%.4f hierarchicalTop1=%.4f%n",
            fixedTop1,
            smartTop1,
            hierarchicalTop1
        );

        assertThat(smartTop1).isEqualTo(1.0d / 3.0d);
        assertThat(hierarchicalTop1).isEqualTo(1.0d);
        assertThat(hierarchicalTop1).isGreaterThan(smartTop1);
    }

    private static List<CaseReport> evaluate(
        Path documentsDir,
        List<TestCase> testCases,
        ChunkGranularity granularity,
        ChunkingStrategyOverride strategyOverride,
        ParentChildProfile profile
    ) throws Exception {
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new HashingEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.ingestSources(WORKSPACE, loadDocumentSources(documentsDir), new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            granularity,
            strategyOverride,
            RegexChunkerConfig.empty(),
            profile
        ));

        var reports = new ArrayList<CaseReport>();
        for (var testCase : testCases) {
            var result = rag.query(WORKSPACE, QueryRequest.builder()
                .query(testCase.question())
                .mode(QueryMode.NAIVE)
                .chunkTopK(1)
                .build());
            var matchedDocumentId = result.contexts().isEmpty()
                ? ""
                : documentIdFromSourceId(result.contexts().get(0).sourceId());
            reports.add(new CaseReport(
                testCase.question(),
                testCase.expectedDocumentId(),
                matchedDocumentId,
                testCase.expectedDocumentId().equals(matchedDocumentId)
            ));
        }
        return List.copyOf(reports);
    }

    private static double hitRate(List<CaseReport> reports) {
        long hits = reports.stream().filter(CaseReport::top1Hit).count();
        return hits / (double) reports.size();
    }

    private static List<TestCase> loadTestCases(Path datasetPath) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(datasetPath.toFile());
        var testCases = new ArrayList<TestCase>();
        for (JsonNode node : root.path("test_cases")) {
            testCases.add(new TestCase(
                node.path("question").asText(),
                node.path("expected_document_id").asText()
            ));
        }
        return List.copyOf(testCases);
    }

    private static List<RawDocumentSource> loadDocumentSources(Path documentsDir) throws Exception {
        try (var paths = Files.list(documentsDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(ParentChildOfflineRetrievalEvaluationTest::toSource)
                .toList();
        }
    }

    private static RawDocumentSource toSource(Path path) {
        try {
            var fileName = path.getFileName().toString();
            var content = Files.readString(path);
            return new RawDocumentSource(
                RagasEvaluationService.documentId(fileName),
                fileName,
                "text/markdown",
                content.getBytes(StandardCharsets.UTF_8),
                java.util.Map.of("source", fileName)
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load document source: " + path, exception);
        }
    }

    private static String documentIdFromSourceId(String sourceId) {
        int separator = sourceId.indexOf(':');
        return separator >= 0 ? sourceId.substring(0, separator) : sourceId;
    }

    private record TestCase(String question, String expectedDocumentId) {
    }

    private record CaseReport(
        String question,
        String expectedDocumentId,
        String matchedDocumentId,
        boolean top1Hit
    ) {
    }

    private static final class NoOpChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("---Role---") || request.systemPrompt().contains("Document Chunks")) {
                return "parent child offline retrieval evaluation";
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
