package io.github.lightragjava.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryResult;
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
            "--storage-profile", "in-memory",
            "--run-label", "candidate-rerank-4"
        ));
        var output = new RagasBatchEvaluationCli.OutputEnvelope(
            new RagasBatchEvaluationCli.RequestMetadata(
                config.batchRequest().documentsDir(),
                config.batchRequest().datasetPath(),
                config.batchRequest().mode(),
                config.batchRequest().topK(),
                config.batchRequest().chunkTopK(),
                config.batchRequest().storageProfile(),
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
        assertThat(config.batchRequest().storageProfile()).isEqualTo(RagasStorageProfile.IN_MEMORY);
        assertThat(config.runLabel()).isEqualTo("baseline");
    }
}
