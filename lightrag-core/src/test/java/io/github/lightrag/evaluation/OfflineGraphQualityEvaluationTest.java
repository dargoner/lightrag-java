package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.indexing.DescriptionFragments;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline, deterministic harness for the merge-time description policy (build-side plan Task 1).
 *
 * <p>The sample evaluation documents are ingested with a rule-based chat model so that merges over shared
 * entities and relations are reproducible without an LLM endpoint. Three configurations are measured on the
 * same fixtures: summarization disabled (the pre-Task-1 concatenation path), the shipped defaults, and an
 * aggressive threshold that forces summarization for every merged description.</p>
 */
class OfflineGraphQualityEvaluationTest {
    private static final String WORKSPACE = "default";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int VECTOR_DIMENSIONS = 1024;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern INPUT_TEXT = Pattern.compile(
        "<Input Text>\\s*```\\s*(.*?)\\s*```\\s*<Output JSON>",
        Pattern.DOTALL
    );
    private static final String EXTRACTION_SYSTEM_MARKER =
        "Knowledge Graph Specialist responsible for extracting entities";
    private static final String SUMMARY_PROMPT_MARKER = "synthesize a list of descriptions";
    private static final String CONTINUATION_MARKER = "Based on the last extraction task";
    private static final Path METRICS_PATH = Path.of("build", "graph-quality-metrics.json");

    private static final List<VocabularyEntry> VOCABULARY = List.of(
        new VocabularyEntry("LightRAG", "framework", "\\bLightRAG\\b"),
        new VocabularyEntry("RAG", "concept", "\\bRAG\\b"),
        new VocabularyEntry("LLM", "technology", "\\bLLMs?\\b"),
        new VocabularyEntry("HKUDS", "organization", "\\bHKUDS\\b"),
        new VocabularyEntry("ChromaDB", "database", "\\bChromaDB\\b"),
        new VocabularyEntry("Neo4j", "database", "\\bNeo4j\\b"),
        new VocabularyEntry("Milvus", "database", "\\bMilvus\\b"),
        new VocabularyEntry("Qdrant", "database", "\\bQdrant\\b"),
        new VocabularyEntry("Redis", "database", "\\bRedis\\b"),
        new VocabularyEntry("MongoDB Atlas", "database", "\\bMongoDB Atlas\\b"),
        new VocabularyEntry("nano-vectordb", "database", "\\bnano-vectordb\\b"),
        new VocabularyEntry("Faithfulness", "metric", "\\bFaithfulness\\b"),
        new VocabularyEntry("Answer Relevance", "metric", "\\bAnswer Relevance\\b"),
        new VocabularyEntry("Context Recall", "metric", "\\bContext Recall\\b"),
        new VocabularyEntry("Context Precision", "metric", "\\bContext Precision\\b")
    );

    @Test
    void recordsEntityRelationAndDescriptionMetricsAcrossSummarizationConfigurations() throws Exception {
        var repositoryRoot = Path.of("").toAbsolutePath().normalize().getParent();
        var documentsDir = repositoryRoot.resolve("evaluation/ragas/sample_documents");
        var documents = loadEvaluationDocuments(documentsDir);

        var reports = new LinkedHashMap<String, ConfigReport>();
        reports.put("summarization-disabled", ingestAndMeasure(documents, false, false));
        reports.put("shipped-defaults", ingestAndMeasure(documents, false, true));
        reports.put("aggressive-threshold", ingestAndMeasure(documents, true, true));

        reports.forEach((label, report) -> System.out.printf(
            Locale.ROOT,
            "[graph-quality] config=%-24s entities=%d relations=%d meanEntityDescription=%d maxEntityDescription=%d "
                + "meanRelationDescription=%d summaryCalls=%d entitySep=%d relationSep=%d%n",
            label,
            report.entityCount(),
            report.relationCount(),
            report.meanEntityDescriptionLength(),
            report.maxEntityDescriptionLength(),
            report.meanRelationDescriptionLength(),
            report.summaryCalls(),
            countContainingSeparator(report.entityDescriptions()),
            countContainingSeparator(report.relationDescriptions())
        ));
        Files.createDirectories(METRICS_PATH.getParent());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(METRICS_PATH.toFile(), reports);

        var disabled = reports.get("summarization-disabled");
        var defaults = reports.get("shipped-defaults");
        var aggressive = reports.get("aggressive-threshold");

        assertThat(disabled.entityCount()).isPositive();
        assertThat(disabled.relationCount()).isPositive();
        assertThat(disabled.summaryCalls()).isZero();
        assertThat(disabled.meanEntityDescriptionLength())
            .isGreaterThanOrEqualTo(aggressive.meanEntityDescriptionLength());
        assertThat(aggressive.summaryCalls()).isPositive();
        assertThat(aggressive.meanEntityDescriptionLength())
            .isLessThan(disabled.meanEntityDescriptionLength());
        assertThat(aggressive.meanRelationDescriptionLength())
            .isLessThanOrEqualTo(disabled.meanRelationDescriptionLength());
        assertThat(defaults.entityCount()).isEqualTo(disabled.entityCount());
        assertThat(defaults.relationCount()).isEqualTo(disabled.relationCount());
        assertThat(aggressive.entityCount()).isEqualTo(disabled.entityCount());
        assertThat(aggressive.relationCount()).isEqualTo(disabled.relationCount());

        assertThat(countContainingSeparator(disabled.entityDescriptions())
            + countContainingSeparator(disabled.relationDescriptions())).isPositive();
        assertThat(countContainingSeparator(defaults.entityDescriptions())
            + countContainingSeparator(defaults.relationDescriptions())).isPositive();
        assertThat(countContainingSeparator(aggressive.entityDescriptions())).isZero();
        assertThat(countContainingSeparator(aggressive.relationDescriptions())).isZero();
    }

    private static ConfigReport ingestAndMeasure(
        List<io.github.lightrag.types.Document> documents,
        boolean aggressive,
        boolean summarizationEnabled
    ) {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new RuleBasedChatModel();
        var builder = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new HashingEmbeddingModel())
            .storage(storage);
        if (!summarizationEnabled) {
            builder.forceLlmSummaryOnMerge(Integer.MAX_VALUE);
            builder.summaryMaxTokens(Integer.MAX_VALUE);
        } else if (aggressive) {
            builder.forceLlmSummaryOnMerge(2);
            builder.summaryMaxTokens(400);
        }
        var rag = builder.build();
        rag.ingest(WORKSPACE, documents);

        var entities = storage.graphStore().allEntities();
        var relations = storage.graphStore().allRelations();
        var entityDescriptions = entities.stream().map(GraphStore.EntityRecord::description).toList();
        var relationDescriptions = relations.stream().map(GraphStore.RelationRecord::description).toList();
        return new ConfigReport(
            entities.size(),
            relations.size(),
            meanLength(entityDescriptions),
            entityDescriptions.stream().mapToInt(String::length).max().orElse(0),
            meanLength(relationDescriptions),
            chatModel.summaryCalls(),
            entityDescriptions,
            relationDescriptions
        );
    }

    private static int countContainingSeparator(List<String> descriptions) {
        return (int) descriptions.stream()
            .filter(description -> description.contains(DescriptionFragments.SEPARATOR))
            .count();
    }

    private static int meanLength(List<String> values) {
        return values.isEmpty() ? 0 : (int) Math.round(values.stream().mapToInt(String::length).average().orElse(0));
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

    private record VocabularyEntry(String name, String type, String regex) {
    }

    private record ConfigReport(
        int entityCount,
        int relationCount,
        int meanEntityDescriptionLength,
        int maxEntityDescriptionLength,
        int meanRelationDescriptionLength,
        int summaryCalls,
        List<String> entityDescriptions,
        List<String> relationDescriptions
    ) {
    }

    private static final class RuleBasedChatModel implements ChatModel {
        private int summaryCalls;

        int summaryCalls() {
            return summaryCalls;
        }

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains(SUMMARY_PROMPT_MARKER)) {
                summaryCalls++;
                return summarize(request.userPrompt());
            }
            if (request.systemPrompt().contains(EXTRACTION_SYSTEM_MARKER)) {
                if (request.userPrompt().contains(CONTINUATION_MARKER)) {
                    return """
                        {"entities": [], "relations": []}
                        """;
                }
                return extract(request.userPrompt());
            }
            throw new IllegalStateException("Unexpected chat prompt: " + request.systemPrompt());
        }

        private static String extract(String userPrompt) {
            var matcher = INPUT_TEXT.matcher(userPrompt);
            var inputText = matcher.find() ? matcher.group(1) : userPrompt;
            var sentences = List.of(SENTENCE_SPLIT.split(inputText.replace('\n', ' ')));

            var entities = new ArrayList<Map<String, Object>>();
            var matchedNames = new ArrayList<String>();
            for (var entry : VOCABULARY) {
                var pattern = Pattern.compile(entry.regex());
                if (!pattern.matcher(inputText).find()) {
                    continue;
                }
                matchedNames.add(entry.name());
                var description = firstSentenceMatching(sentences, pattern, entry.name());
                entities.add(Map.of(
                    "name", entry.name(),
                    "type", entry.type(),
                    "description", description,
                    "aliases", List.of()
                ));
            }

            var relations = new ArrayList<Map<String, Object>>();
            for (int index = 0; index + 1 < matchedNames.size(); index++) {
                var source = matchedNames.get(index);
                var target = matchedNames.get(index + 1);
                var description = firstSentenceMatchingBoth(sentences, source, target);
                relations.add(Map.of(
                    "source_entity", source,
                    "target_entity", target,
                    "relationship_keywords", "co-occurs",
                    "relationship_description", description
                ));
            }

            try {
                return OBJECT_MAPPER.writeValueAsString(Map.of("entities", entities, "relations", relations));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static String firstSentenceMatching(List<String> sentences, Pattern pattern, String fallback) {
            return sentences.stream()
                .filter(sentence -> pattern.matcher(sentence).find())
                .map(String::strip)
                .findFirst()
                .orElse(fallback);
        }

        private static String firstSentenceMatchingBoth(List<String> sentences, String source, String target) {
            var pattern = Pattern.compile("\\b" + Pattern.quote(source) + "\\b");
            var targetPattern = Pattern.compile("\\b" + Pattern.quote(target) + "\\b");
            return sentences.stream()
                .filter(sentence -> pattern.matcher(sentence).find() && targetPattern.matcher(sentence).find())
                .map(String::strip)
                .findFirst()
                .orElse(source + " and " + target + " appear together in the input text.");
        }

        private static String summarize(String userPrompt) {
            var fragments = Pattern.compile("\\{\"Description\": \"(.*?)\"\\}", Pattern.DOTALL)
                .matcher(userPrompt);
            var distinct = new LinkedHashSet<String>();
            while (fragments.find()) {
                distinct.add(unescapeJson(fragments.group(1)).strip());
            }
            var joined = String.join(" ", distinct);
            var consolidated = new LinkedHashSet<String>();
            for (var sentence : SENTENCE_SPLIT.split(joined)) {
                var stripped = sentence.strip();
                if (!stripped.isEmpty()) {
                    consolidated.add(stripped);
                }
            }
            var condensed = String.join(" ", consolidated);
            return condensed.length() <= 240 ? condensed : condensed.substring(0, 240);
        }

        private static String unescapeJson(String value) {
            var unescaped = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current != '\\' || index + 1 >= value.length()) {
                    unescaped.append(current);
                    continue;
                }
                char next = value.charAt(++index);
                switch (next) {
                    case 'n' -> unescaped.append('\n');
                    case 'r' -> unescaped.append('\r');
                    case 't' -> unescaped.append('\t');
                    case 'b' -> unescaped.append('\b');
                    case 'f' -> unescaped.append('\f');
                    case '"', '\\', '/' -> unescaped.append(next);
                    case 'u' -> {
                        if (index + 4 < value.length()) {
                            unescaped.append((char) Integer.parseInt(value.substring(index + 1, index + 5), 16));
                            index += 4;
                        } else {
                            unescaped.append('\\').append(next);
                        }
                    }
                    default -> unescaped.append('\\').append(next);
                }
            }
            return unescaped.toString();
        }
    }

    private static final class HashingEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(HashingEmbeddingModel::embed).toList();
        }

        private static List<Double> embed(String text) {
            double[] vector = new double[VECTOR_DIMENSIONS];
            var tokens = text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+");
            for (String token : tokens) {
                if (token.isEmpty()) {
                    continue;
                }
                vector[Math.floorMod(token.hashCode(), VECTOR_DIMENSIONS)] += 1.0d;
            }
            for (int index = 0; index + 1 < tokens.length; index++) {
                if (tokens[index].isEmpty() || tokens[index + 1].isEmpty()) {
                    continue;
                }
                var bigram = tokens[index] + "::" + tokens[index + 1];
                vector[Math.floorMod(bigram.hashCode(), VECTOR_DIMENSIONS)] += 1.5d;
            }
            double norm = 0.0d;
            for (double value : vector) {
                norm += value * value;
            }
            norm = norm == 0.0d ? 1.0d : Math.sqrt(norm);
            var normalized = new ArrayList<Double>(VECTOR_DIMENSIONS);
            for (double value : vector) {
                normalized.add(value / norm);
            }
            return List.copyOf(normalized);
        }
    }
}
