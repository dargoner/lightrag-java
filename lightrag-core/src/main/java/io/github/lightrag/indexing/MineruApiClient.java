package io.github.lightrag.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.types.RawDocumentSource;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.MediaType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipInputStream;

public final class MineruApiClient implements MineruClient {
    public static final String SOURCE_URL_METADATA_KEY = "sourceUrl";

    private final Transport transport;

    public MineruApiClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public String backend() {
        return "mineru_api";
    }

    @Override
    public ParseResult parse(RawDocumentSource source) {
        return transport.parse(source);
    }

    @FunctionalInterface
    public interface Transport {
        ParseResult parse(RawDocumentSource source);
    }

    public static final class HttpTransport implements Transport {
        private static final MediaType JSON = MediaType.get("application/json");
        private static final String DEFAULT_MODEL_VERSION = "vlm";
        private static final int DEFAULT_MAX_POLL_ATTEMPTS = 60;
        private static final int DEFAULT_POLL_INTERVAL_MILLIS = 1_000;

        private final HttpUrl createTaskUrl;
        private final String apiKey;
        private final OkHttpClient httpClient;
        private final ObjectMapper objectMapper;
        private final int pollIntervalMillis;
        private final int maxPollAttempts;

        public HttpTransport(String baseUrl, String apiKey) {
            this(baseUrl, apiKey, Duration.ofSeconds(60), DEFAULT_POLL_INTERVAL_MILLIS, DEFAULT_MAX_POLL_ATTEMPTS);
        }

        public HttpTransport(
            String baseUrl,
            String apiKey,
            Duration timeout,
            int pollIntervalMillis,
            int maxPollAttempts
        ) {
            this.createTaskUrl = resolveCreateTaskUrl(baseUrl);
            this.apiKey = requireNonBlank(apiKey, "apiKey");
            this.httpClient = new OkHttpClient.Builder()
                .callTimeout(Objects.requireNonNull(timeout, "timeout"))
                .build();
            this.objectMapper = new ObjectMapper();
            if (pollIntervalMillis < 0) {
                throw new IllegalArgumentException("pollIntervalMillis must not be negative");
            }
            if (maxPollAttempts <= 0) {
                throw new IllegalArgumentException("maxPollAttempts must be positive");
            }
            this.pollIntervalMillis = pollIntervalMillis;
            this.maxPollAttempts = maxPollAttempts;
        }

        @Override
        public ParseResult parse(RawDocumentSource source) {
            var rawSource = Objects.requireNonNull(source, "source");
            var sourceUrl = resolveSourceUrl(rawSource);
            var taskId = submitTask(sourceUrl);
            var zipUrl = awaitCompletedTask(taskId);
            return parseZip(downloadZip(zipUrl));
        }

        private String submitTask(String sourceUrl) {
            var payload = "{\"url\":\"" + escapeJson(sourceUrl) + "\",\"model_version\":\"" + DEFAULT_MODEL_VERSION + "\"}";
            var request = authorized(createTaskUrl)
                .post(RequestBody.create(payload, JSON))
                .build();
            try (var response = httpClient.newCall(request).execute()) {
                var body = response.body();
                var responseBody = body == null ? "" : body.string();
                if (!response.isSuccessful()) {
                    throw new MineruUnavailableException(httpFailureMessage(
                        "MinerU API task creation failed",
                        response.code(),
                        request.url().toString(),
                        response.header("x-request-id"),
                        responseBody
                    ));
                }
                var root = objectMapper.readTree(responseBody);
                ensureSuccess(root, "MinerU API task creation failed");
                var taskId = root.path("data").path("task_id").asText("").strip();
                if (taskId.isEmpty()) {
                    throw new MineruUnavailableException("MinerU API task creation response missing task_id");
                }
                return taskId;
            } catch (IOException exception) {
                throw new MineruUnavailableException("MinerU API task creation failed", exception);
            }
        }

        private String awaitCompletedTask(String taskId) {
            var statusUrl = createTaskUrl.newBuilder().addPathSegment(taskId).build();
            for (int attempt = 0; attempt < maxPollAttempts; attempt++) {
                var request = authorized(statusUrl).get().build();
                try (var response = httpClient.newCall(request).execute()) {
                    var body = response.body();
                    var responseBody = body == null ? "" : body.string();
                    if (!response.isSuccessful()) {
                        throw new MineruUnavailableException(httpFailureMessage(
                            "MinerU API task polling failed",
                            response.code(),
                            request.url().toString(),
                            response.header("x-request-id"),
                            responseBody
                        ));
                    }
                    var root = objectMapper.readTree(responseBody);
                    ensureSuccess(root, "MinerU API task polling failed");
                    var data = root.path("data");
                    var state = data.path("state").asText("").strip().toLowerCase(java.util.Locale.ROOT);
                    if ("done".equals(state)) {
                        var zipUrl = data.path("full_zip_url").asText("").strip();
                        if (zipUrl.isEmpty()) {
                            throw new MineruUnavailableException("MinerU API task completed without full_zip_url");
                        }
                        return zipUrl;
                    }
                    if ("failed".equals(state) || "error".equals(state)) {
                        var errorMessage = data.path("err_msg").asText("").strip();
                        throw new MineruUnavailableException(errorMessage.isEmpty()
                            ? "MinerU API task failed"
                            : errorMessage);
                    }
                } catch (IOException exception) {
                    throw new MineruUnavailableException("MinerU API task polling failed", exception);
                }
                sleepBeforeRetry();
            }
            throw new MineruUnavailableException("MinerU API task polling timed out");
        }

        private byte[] downloadZip(String zipUrl) {
            var request = new Request.Builder()
                .url(zipUrl)
                .get()
                .build();
            try (var response = httpClient.newCall(request).execute()) {
                var body = response.body();
                if (!response.isSuccessful()) {
                    var responseBody = body == null ? "" : body.string();
                    throw new MineruUnavailableException(httpFailureMessage(
                        "MinerU API zip download failed",
                        response.code(),
                        request.url().toString(),
                        response.header("x-request-id"),
                        responseBody
                    ));
                }
                if (body == null) {
                    throw new MineruUnavailableException("MinerU API zip download returned an empty body");
                }
                return body.bytes();
            } catch (IOException exception) {
                throw new MineruUnavailableException("MinerU API zip download failed", exception);
            }
        }

        private ParseResult parseZip(byte[] zipBytes) {
            String markdown = "";
            String structuredJson = null;
            try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    var name = entry.getName();
                    var text = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    if ("content_list_v2.json".equals(name)) {
                        structuredJson = text;
                    } else if (structuredJson == null && (name.endsWith("_content_list.json") || "content_list.json".equals(name))) {
                        structuredJson = text;
                    } else if ("full.md".equals(name)) {
                        markdown = text;
                    }
                }
            } catch (IOException exception) {
                throw new MineruUnavailableException("MinerU API zip parsing failed", exception);
            }
            var blocks = structuredJson == null ? List.<Block>of() : parseStructuredBlocks(structuredJson);
            return new ParseResult(blocks, markdown);
        }

        private List<Block> parseStructuredBlocks(String structuredJson) {
            try {
                var root = objectMapper.readTree(structuredJson);
                var blocks = new ArrayList<Block>();
                var sectionHierarchy = new ArrayList<String>();
                int order = 0;
                if (!root.isArray()) {
                    return List.of();
                }
                for (int pageIndex = 0; pageIndex < root.size(); pageIndex++) {
                    var page = root.get(pageIndex);
                    if (!page.isArray()) {
                        continue;
                    }
                    for (var blockNode : page) {
                        var blockType = blockNode.path("type").asText("").strip();
                        if (blockType.isEmpty()) {
                            continue;
                        }
                        var contentNode = blockNode.path("content");
                        var text = extractText(contentNode).strip();
                        if (text.isEmpty()) {
                            continue;
                        }
                        if ("title".equals(blockType)) {
                            var level = Math.max(1, contentNode.path("level").asInt(1));
                            while (sectionHierarchy.size() >= level) {
                                sectionHierarchy.remove(sectionHierarchy.size() - 1);
                            }
                            sectionHierarchy.add(text);
                        }
                        var currentSectionHierarchy = List.copyOf(sectionHierarchy);
                        var metadata = new LinkedHashMap<String, String>();
                        if ("title".equals(blockType)) {
                            metadata.put("level", Integer.toString(Math.max(1, contentNode.path("level").asInt(1))));
                        }
                        blocks.add(new Block(
                            "page-" + (pageIndex + 1) + "-block-" + order,
                            blockType,
                            text,
                            currentSectionHierarchy.isEmpty() ? "" : String.join(" / ", currentSectionHierarchy),
                            currentSectionHierarchy,
                            pageIndex + 1,
                            extractBbox(blockNode.path("bbox")),
                            order,
                            Map.copyOf(metadata)
                        ));
                        order++;
                    }
                }
                return List.copyOf(blocks);
            } catch (IOException exception) {
                throw new MineruUnavailableException("MinerU structured content parsing failed", exception);
            }
        }

        private Request.Builder authorized(HttpUrl url) {
            return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json");
        }

        private void ensureSuccess(JsonNode root, String defaultMessage) {
            if (root.path("code").asInt(-1) != 0) {
                var message = root.path("msg").asText("").strip();
                throw new MineruUnavailableException(message.isEmpty() ? defaultMessage : message);
            }
        }

        private void sleepBeforeRetry() {
            if (pollIntervalMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new MineruUnavailableException("MinerU API task polling interrupted", exception);
            }
        }

        private static String resolveSourceUrl(RawDocumentSource source) {
            for (var key : List.of(SOURCE_URL_METADATA_KEY, "source_url", "url")) {
                var value = source.metadata().get(key);
                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
            throw new MineruUnavailableException(
                "MinerU API requires a public source URL in metadata[sourceUrl] or metadata[url]"
            );
        }

        private static HttpUrl resolveCreateTaskUrl(String baseUrl) {
            var normalized = requireNonBlank(baseUrl, "baseUrl");
            var trimmed = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
            var createTaskUrl = trimmed.endsWith("/extract/task")
                ? trimmed
                : trimmed.contains("/api/v4")
                    ? trimmed + "/extract/task"
                    : trimmed + "/api/v4/extract/task";
            try {
                return Objects.requireNonNull(HttpUrl.get(createTaskUrl), "createTaskUrl");
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid MinerU baseUrl: " + baseUrl, exception);
            }
        }

        private static String extractText(JsonNode node) {
            if (node == null || node.isNull() || node.isMissingNode()) {
                return "";
            }
            if (node.isTextual()) {
                return node.asText();
            }
            var builder = new StringBuilder();
            if (node.isArray()) {
                for (var child : node) {
                    appendText(builder, extractText(child));
                }
                return builder.toString();
            }
            if (node.isObject()) {
                if (node.has("content")) {
                    return extractText(node.get("content"));
                }
                for (Iterator<Map.Entry<String, JsonNode>> iterator = node.fields(); iterator.hasNext(); ) {
                    var entry = iterator.next();
                    if ("type".equals(entry.getKey()) || "level".equals(entry.getKey())) {
                        continue;
                    }
                    appendText(builder, extractText(entry.getValue()));
                }
            }
            return builder.toString();
        }

        private static String extractBbox(JsonNode bboxNode) {
            if (bboxNode == null || !bboxNode.isArray() || bboxNode.isEmpty()) {
                return null;
            }
            var values = new ArrayList<String>(bboxNode.size());
            for (var value : bboxNode) {
                values.add(value.asText());
            }
            return String.join(",", values);
        }

        private static void appendText(StringBuilder builder, String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(text.strip());
        }

        private static String escapeJson(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        }

        private static String httpFailureMessage(
            String prefix,
            int statusCode,
            String requestUrl,
            String requestId,
            String responseBody
        ) {
            var builder = new StringBuilder(prefix).append(" with HTTP ").append(statusCode);
            if (requestId != null && !requestId.isBlank()) {
                builder.append("; requestId=").append(requestId);
            }
            if (requestUrl != null && !requestUrl.isBlank()) {
                builder.append("; requestUrl=").append(requestUrl);
            }
            var compactBody = compactResponseBody(responseBody);
            if (compactBody != null && !compactBody.isBlank()) {
                builder.append("; responseBody=").append(compactBody);
            }
            return builder.toString();
        }

        private static String compactResponseBody(String responseBody) {
            if (responseBody == null) {
                return null;
            }
            var normalized = responseBody.strip();
            if (normalized.length() <= 500) {
                return normalized;
            }
            return normalized.substring(0, 500) + "...";
        }

        private static String requireNonBlank(String value, String fieldName) {
            Objects.requireNonNull(value, fieldName);
            var normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
