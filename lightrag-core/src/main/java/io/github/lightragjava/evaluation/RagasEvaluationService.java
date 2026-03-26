package io.github.lightragjava.evaluation;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.types.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RagasEvaluationService {
    private static final String WORKSPACE = "default";

    public EvaluationResult evaluate(Request request, ChatModel chatModel, EmbeddingModel embeddingModel) throws IOException {
        var evaluationRequest = Objects.requireNonNull(request, "request");
        var rag = LightRag.builder()
            .chatModel(Objects.requireNonNull(chatModel, "chatModel"))
            .embeddingModel(Objects.requireNonNull(embeddingModel, "embeddingModel"))
            .storage(InMemoryStorageProvider.create())
            .build();

        rag.ingest(WORKSPACE, loadDocuments(evaluationRequest.documentsDir()));
        var result = rag.query(WORKSPACE, QueryRequest.builder()
            .query(evaluationRequest.question())
            .mode(evaluationRequest.mode())
            .topK(evaluationRequest.topK())
            .chunkTopK(evaluationRequest.chunkTopK())
            .build());
        return new EvaluationResult(
            result.answer(),
            result.contexts()
        );
    }

    static List<Document> loadDocuments(Path documentsDir) throws IOException {
        try (var paths = Files.list(Objects.requireNonNull(documentsDir, "documentsDir"))) {
            return paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .map(RagasEvaluationService::toDocument)
                .toList();
        }
    }

    static Document toDocument(Path path) {
        try {
            var content = Files.readString(path);
            var fileName = path.getFileName().toString();
            var title = extractTitle(content, fileName);
            return new Document(
                documentId(fileName),
                title,
                content,
                java.util.Map.of("source", fileName)
            );
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read document: " + path, exception);
        }
    }

    static String extractTitle(String content, String fileName) {
        return content.lines()
            .map(String::trim)
            .filter(line -> line.startsWith("# "))
            .map(line -> line.substring(2).trim())
            .filter(line -> !line.isEmpty())
            .findFirst()
            .orElse(fileName);
    }

    static String documentId(String fileName) {
        var baseName = fileName.replaceFirst("\\.md$", "");
        return baseName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public record Request(
        Path documentsDir,
        String question,
        QueryMode mode,
        int topK,
        int chunkTopK
    ) {
        public Request {
            documentsDir = Objects.requireNonNull(documentsDir, "documentsDir");
            question = Objects.requireNonNull(question, "question");
            mode = Objects.requireNonNull(mode, "mode");
        }
    }

    public record EvaluationResult(String answer, List<QueryResult.Context> contexts) {
        public EvaluationResult {
            answer = Objects.requireNonNull(answer, "answer");
            contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        }
    }
}
