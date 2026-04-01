package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagasCliOutputTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void batchCliEnvelopeIncludesRequestSummaryAndStructuredResults() throws Exception {
        var config = RagasBatchEvaluationCli.buildConfig(Map.of(
            "--mode", "mix",
            "--top-k", "10",
            "--chunk-top-k", "12",
            "--max-hop", "4",
            "--path-top-k", "5",
            "--multi-hop-enabled", "false",
            "--storage-profile", "in-memory",
            "--retrieval-only", "true",
            "--run-label", "candidate-rerank-4"
        ));
        var output = new RagasBatchEvaluationCli.OutputEnvelope(
            new RagasBatchEvaluationCli.RequestMetadata(
                config.batchRequest().documentsDir(),
                config.batchRequest().datasetPath(),
                config.batchRequest().mode(),
                config.batchRequest().topK(),
                config.batchRequest().chunkTopK(),
                config.batchRequest().maxHop(),
                config.batchRequest().pathTopK(),
                config.batchRequest().multiHopEnabled(),
                config.batchRequest().storageProfile(),
                config.batchRequest().retrievalOnly(),
                config.runLabel()
            ),
            new RagasBatchEvaluationCli.Summary(1),
            List.of(new RagasBatchEvaluationService.Result(
                0,
                "Who works with Bob?",
                "Alice works with Bob.",
                Map.of("project", "alpha"),
                "Alice works with Bob.",
                List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "1", "notes.md")),
                List.of(new QueryResult.Reference("1", "notes.md"))
            ))
        );

        var json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(output));

        assertThat(json.path("request").path("mode").asText()).isEqualTo("MIX");
        assertThat(json.path("request").path("maxHop").asInt()).isEqualTo(4);
        assertThat(json.path("request").path("pathTopK").asInt()).isEqualTo(5);
        assertThat(json.path("request").path("multiHopEnabled").asBoolean()).isFalse();
        assertThat(json.path("request").path("retrievalOnly").asBoolean()).isTrue();
        assertThat(json.path("request").path("runLabel").asText()).isEqualTo("candidate-rerank-4");
        assertThat(json.path("summary").path("totalCases").asInt()).isEqualTo(1);
        assertThat(json.path("results").isArray()).isTrue();
        assertThat(json.path("results").get(0).path("groundTruth").asText()).isEqualTo("Alice works with Bob.");
        assertThat(json.path("results").get(0).path("contexts").get(0).path("sourceId").asText()).isEqualTo("chunk-1");
        assertThat(json.path("results").get(0).path("references").get(0).path("referenceId").asText()).isEqualTo("1");
    }

    @Test
    void singleCliEnvelopeIncludesRequestAndStructuredResult() throws Exception {
        var output = new RagasEvaluationCli.OutputEnvelope(
            new RagasEvaluationCli.RequestMetadata(
                Path.of("docs"),
                "Who works with Bob?",
                QueryMode.NAIVE,
                5,
                7
            ),
            new RagasEvaluationService.EvaluationResult(
                "Alice works with Bob.",
                List.of(new QueryResult.Context("chunk-1", "Alice works with Bob", "", ""))
            )
        );

        var json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(output));

        assertThat(json.path("request").path("chunkTopK").asInt()).isEqualTo(7);
        assertThat(json.path("result").path("answer").asText()).isEqualTo("Alice works with Bob.");
        assertThat(json.path("result").path("contexts").get(0).path("text").asText()).contains("Alice works with Bob");
    }

    @Test
    void batchCliConfigDefaultsToSampleDatasetAndBaselineRunLabel() {
        var config = RagasBatchEvaluationCli.buildConfig(Map.of());

        assertThat(config.batchRequest().documentsDir()).isEqualTo(Path.of("evaluation/ragas/sample_documents"));
        assertThat(config.batchRequest().datasetPath()).isEqualTo(Path.of("evaluation/ragas/sample_dataset.json"));
        assertThat(config.batchRequest().mode()).isEqualTo(QueryMode.MIX);
        assertThat(config.batchRequest().topK()).isEqualTo(10);
        assertThat(config.batchRequest().chunkTopK()).isEqualTo(10);
        assertThat(config.batchRequest().maxHop()).isEqualTo(2);
        assertThat(config.batchRequest().pathTopK()).isEqualTo(3);
        assertThat(config.batchRequest().multiHopEnabled()).isTrue();
        assertThat(config.batchRequest().storageProfile()).isEqualTo(RagasStorageProfile.IN_MEMORY);
        assertThat(config.batchRequest().retrievalOnly()).isFalse();
        assertThat(config.runLabel()).isEqualTo("baseline");
    }

    @Test
    void retrievalOnlyCliUsesLocalNoOpChatModel() {
        var chatModel = RagasBatchEvaluationCli.createChatModel(new RagasBatchEvaluationService.BatchRequest(
            Path.of("docs"),
            Path.of("dataset.json"),
            QueryMode.MIX,
            10,
            10,
            2,
            3,
            true,
            RagasStorageProfile.IN_MEMORY,
            true
        ));

        var response = chatModel.generate(new io.github.lightrag.model.ChatModel.ChatRequest(
            "Extract entities and relations from the provided text.",
            "Chunk text"
        ));

        assertThat(response).contains("\"entities\": []");
        assertThat(response).contains("\"relations\": []");
    }
}
