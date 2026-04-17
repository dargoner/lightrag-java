package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.ChunkGranularity;
import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.api.QueryResult;
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

class RealConvertedMarkdownRetrievalEvaluationTest {
    private static final String WORKSPACE = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}a-z0-9]+");
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final Set<String> STOP_WORDS = Set.of(
        "的", "了", "与", "和", "及", "包括", "阶段", "工作", "目标", "什么", "哪些", "对应"
    );

    @Test
    void reportsTop1Top3MatchedDocumentsAndFragmentsForRealConvertedMarkdownFixtures() throws Exception {
        var repositoryRoot = Path.of("").toAbsolutePath().normalize().getParent();
        var documentsDir = repositoryRoot.resolve("evaluation/real_converted_markdown/sample_documents");
        var datasetPath = repositoryRoot.resolve("evaluation/real_converted_markdown/sample_dataset.json");
        var testCases = loadTestCases(datasetPath);

        var fixed = evaluate(
            documentsDir,
            testCases,
            ChunkingStrategyOverride.FIXED,
            ParentChildProfile.disabled()
        );
        var smart = evaluate(
            documentsDir,
            testCases,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.disabled()
        );
        var hierarchical = evaluate(
            documentsDir,
            testCases,
            ChunkingStrategyOverride.SMART,
            ParentChildProfile.enabled(18, 4)
        );

        printModeReports("fixed", fixed);
        printModeReports("smart", smart);
        printModeReports("hierarchical", hierarchical);

        assertThat(summary(hierarchical).top1HitRate()).isGreaterThanOrEqualTo(summary(smart).top1HitRate());
        assertThat(summary(hierarchical).top3HitRate()).isEqualTo(1.0d);
        assertThat(summary(hierarchical).fragmentHitRate()).isEqualTo(1.0d);
    }

    private static EvaluationSummary summary(List<CaseReport> reports) {
        long top1Hits = reports.stream().filter(CaseReport::top1Hit).count();
        long top3Hits = reports.stream().filter(CaseReport::top3Hit).count();
        long fragmentHits = reports.stream().filter(CaseReport::fragmentHit).count();
        double total = reports.size();
        return new EvaluationSummary(top1Hits / total, top3Hits / total, fragmentHits / total);
    }

    private static void printModeReports(String mode, List<CaseReport> reports) {
        var summary = summary(reports);
        for (var report : reports) {
            System.out.printf(
                Locale.ROOT,
                "[real-markdown][%s] question=%s top1=%s top3=%s matchedDocs=%s matchedSnippets=%s fragmentHit=%s%n",
                mode,
                report.question(),
                report.top1Hit(),
                report.top3Hit(),
                report.top3MatchedDocumentIds(),
                report.matchedSnippets(),
                report.fragmentHit()
            );
        }
        System.out.printf(
            Locale.ROOT,
            "[real-markdown][%s] top1HitRate=%.4f top3HitRate=%.4f fragmentHitRate=%.4f%n",
            mode,
            summary.top1HitRate(),
            summary.top3HitRate(),
            summary.fragmentHitRate()
        );
    }

    private static List<CaseReport> evaluate(
        Path documentsDir,
        List<TestCase> testCases,
        ChunkingStrategyOverride strategyOverride,
        ParentChildProfile parentChildProfile
    ) throws Exception {
        var rag = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new HashingEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .automaticQueryKeywordExtraction(false)
            .build();

        rag.ingestSources(WORKSPACE, loadDocumentSources(documentsDir), new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            strategyOverride,
            RegexChunkerConfig.empty(),
            parentChildProfile
        ));

        var reports = new ArrayList<CaseReport>();
        for (var testCase : testCases) {
            var result = rag.query(WORKSPACE, QueryRequest.builder()
                .query(testCase.question())
                .mode(QueryMode.NAIVE)
                .chunkTopK(3)
                .build());
            reports.add(toCaseReport(testCase, result));
        }
        return List.copyOf(reports);
    }

    private static CaseReport toCaseReport(TestCase testCase, QueryResult result) {
        var matchedDocumentIds = result.contexts().stream()
            .map(QueryResult.Context::sourceId)
            .map(RealConvertedMarkdownRetrievalEvaluationTest::documentIdFromSourceId)
            .toList();
        var matchedSnippets = result.contexts().stream()
            .limit(3)
            .map(QueryResult.Context::text)
            .map(RealConvertedMarkdownRetrievalEvaluationTest::preview)
            .toList();
        boolean top1Hit = !matchedDocumentIds.isEmpty() && testCase.expectedDocumentId().equals(matchedDocumentIds.get(0));
        boolean top3Hit = matchedDocumentIds.stream().limit(3).anyMatch(testCase.expectedDocumentId()::equals);
        boolean fragmentHit = result.contexts().stream()
            .limit(3)
            .map(QueryResult.Context::text)
            .anyMatch(text -> normalize(text).contains(normalize(testCase.expectedFragment())));
        return new CaseReport(
            testCase.question(),
            testCase.expectedDocumentId(),
            testCase.expectedFragment(),
            matchedDocumentIds.stream().limit(3).toList(),
            matchedSnippets,
            top1Hit,
            top3Hit,
            fragmentHit
        );
    }

    private static List<TestCase> loadTestCases(Path datasetPath) throws Exception {
        JsonNode root = OBJECT_MAPPER.readTree(datasetPath.toFile());
        var testCases = new ArrayList<TestCase>();
        for (JsonNode node : root.path("test_cases")) {
            testCases.add(new TestCase(
                node.path("question").asText(),
                node.path("expected_document_id").asText(),
                node.path("expected_fragment").asText()
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
                .map(RealConvertedMarkdownRetrievalEvaluationTest::toSource)
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

    private static String preview(String text) {
        var normalized = normalize(text);
        if (normalized.length() <= 72) {
            return normalized;
        }
        return normalized.substring(0, 72) + "...";
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", "").trim();
    }

    private record TestCase(String question, String expectedDocumentId, String expectedFragment) {
    }

    private record CaseReport(
        String question,
        String expectedDocumentId,
        String expectedFragment,
        List<String> top3MatchedDocumentIds,
        List<String> matchedSnippets,
        boolean top1Hit,
        boolean top3Hit,
        boolean fragmentHit
    ) {
    }

    private record EvaluationSummary(double top1HitRate, double top3HitRate, double fragmentHitRate) {
    }

    private static final class NoOpChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("Document Chunks")) {
                return "real converted markdown retrieval evaluation";
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
            var hanSequence = new StringBuilder();
            text.codePoints().forEach(codePoint -> {
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                    hanSequence.appendCodePoint(codePoint);
                    return;
                }
                appendHanTokens(uniqueTokens, hanSequence);
            });
            appendHanTokens(uniqueTokens, hanSequence);
            return List.copyOf(uniqueTokens);
        }

        private static void appendHanTokens(Set<String> tokens, StringBuilder hanSequence) {
            if (hanSequence.isEmpty()) {
                return;
            }
            var text = hanSequence.toString();
            for (int index = 0; index < text.length(); index++) {
                var current = text.substring(index, index + 1);
                if (!STOP_WORDS.contains(current)) {
                    tokens.add(current);
                }
                if (index + 1 < text.length()) {
                    var bigram = text.substring(index, index + 2);
                    if (!STOP_WORDS.contains(bigram)) {
                        tokens.add(bigram);
                    }
                }
            }
            hanSequence.setLength(0);
        }
    }
}
