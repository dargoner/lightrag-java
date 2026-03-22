package io.github.lightragjava.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.persistence.FileSnapshotStore;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.neo4j.Neo4jGraphConfig;
import io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.types.Document;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RagasBatchEvaluationService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<Result> evaluateBatch(BatchRequest request, ChatModel chatModel, EmbeddingModel embeddingModel) throws IOException {
        var batchRequest = Objects.requireNonNull(request, "request");
        var testCases = loadTestCases(batchRequest.datasetPath());
        var documents = RagasEvaluationService.loadDocuments(batchRequest.documentsDir());

        try (var runtime = createRuntime(batchRequest.storageProfile(), chatModel, embeddingModel, batchRequest.retrievalOnly())) {
            runtime.rag().ingest(documents);
            var results = new ArrayList<Result>(testCases.size());
            for (int index = 0; index < testCases.size(); index++) {
                var testCase = testCases.get(index);
                var queryResult = runtime.rag().query(QueryRequest.builder()
                    .query(testCase.question())
                    .mode(batchRequest.mode())
                    .topK(batchRequest.topK())
                    .chunkTopK(batchRequest.chunkTopK())
                    .onlyNeedContext(batchRequest.retrievalOnly())
                    .build());
                results.add(new Result(
                    index,
                    testCase.question(),
                    testCase.groundTruth(),
                    testCase.metadata(),
                    queryResult.answer(),
                    queryResult.contexts(),
                    queryResult.references()
                ));
            }
            return List.copyOf(results);
        }
    }

    private static EvaluationRuntime createRuntime(
        RagasStorageProfile profile,
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        boolean retrievalOnly
    ) {
        return switch (profile) {
            case IN_MEMORY -> new EvaluationRuntime(
                LightRag.builder()
                    .chatModel(chatModel)
                    .embeddingModel(embeddingModel)
                    .storage(InMemoryStorageProvider.create())
                    .automaticQueryKeywordExtraction(!retrievalOnly)
                    .build(),
                () -> {
                }
            );
            case POSTGRES_NEO4J_TESTCONTAINERS -> postgresNeo4jRuntime(chatModel, embeddingModel, retrievalOnly);
        };
    }

    private static EvaluationRuntime postgresNeo4jRuntime(ChatModel chatModel, EmbeddingModel embeddingModel, boolean retrievalOnly) {
        var postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        );
        var neo4j = new Neo4jContainer<>("neo4j:5-community").withAdminPassword("password");
        postgres.start();
        neo4j.start();

        try {
            int vectorDimensions = embeddingModel.embedAll(List.of("dimension probe")).get(0).size();
            var storage = new PostgresNeo4jStorageProvider(
                new PostgresStorageConfig(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    "lightrag",
                    vectorDimensions,
                    "rag_"
                ),
                new Neo4jGraphConfig(
                    neo4j.getBoltUrl(),
                    "neo4j",
                    neo4j.getAdminPassword(),
                    "neo4j"
                ),
                new FileSnapshotStore()
            );
            var rag = LightRag.builder()
                .chatModel(chatModel)
                .embeddingModel(embeddingModel)
                .storage(storage)
                .automaticQueryKeywordExtraction(!retrievalOnly)
                .build();
            return new EvaluationRuntime(
                rag,
                () -> {
                    try (storage; postgres; neo4j) {
                        // close in reverse lifetime order
                    }
                }
            );
        } catch (RuntimeException exception) {
            try {
                neo4j.close();
            } catch (RuntimeException ignored) {
            }
            try {
                postgres.close();
            } catch (RuntimeException ignored) {
            }
            throw exception;
        }
    }

    private static List<TestCase> loadTestCases(Path datasetPath) throws IOException {
        var root = OBJECT_MAPPER.readTree(Objects.requireNonNull(datasetPath, "datasetPath").toFile());
        var testCases = new ArrayList<TestCase>();
        for (JsonNode testCase : root.path("test_cases")) {
            var question = testCase.path("question").asText("").strip();
            if (!question.isEmpty()) {
                var metadata = new LinkedHashMap<String, Object>();
                testCase.fields().forEachRemaining(entry -> {
                    if (!"question".equals(entry.getKey()) && !"ground_truth".equals(entry.getKey())) {
                        metadata.put(entry.getKey(), OBJECT_MAPPER.convertValue(entry.getValue(), Object.class));
                    }
                });
                testCases.add(new TestCase(
                    question,
                    testCase.path("ground_truth").asText(""),
                    Map.copyOf(metadata)
                ));
            }
        }
        return List.copyOf(testCases);
    }

    public record BatchRequest(
        Path documentsDir,
        Path datasetPath,
        QueryMode mode,
        int topK,
        int chunkTopK,
        RagasStorageProfile storageProfile,
        boolean retrievalOnly
    ) {
        public BatchRequest {
            documentsDir = Objects.requireNonNull(documentsDir, "documentsDir");
            datasetPath = Objects.requireNonNull(datasetPath, "datasetPath");
            mode = Objects.requireNonNull(mode, "mode");
            storageProfile = Objects.requireNonNull(storageProfile, "storageProfile");
        }
    }

    public record Result(
        int caseIndex,
        String question,
        String groundTruth,
        Map<String, Object> caseMetadata,
        String answer,
        List<QueryResult.Context> contexts,
        List<QueryResult.Reference> references
    ) {
        public Result {
            question = Objects.requireNonNull(question, "question");
            groundTruth = Objects.requireNonNull(groundTruth, "groundTruth");
            caseMetadata = Map.copyOf(Objects.requireNonNull(caseMetadata, "caseMetadata"));
            answer = Objects.requireNonNull(answer, "answer");
            contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
            references = List.copyOf(Objects.requireNonNull(references, "references"));
        }
    }

    private record TestCase(String question, String groundTruth, Map<String, Object> metadata) {
    }

    @FunctionalInterface
    private interface RuntimeCloseable {
        void close();
    }

    private record EvaluationRuntime(LightRag rag, RuntimeCloseable closeable) implements AutoCloseable {
        @Override
        public void close() {
            closeable.close();
        }
    }
}
