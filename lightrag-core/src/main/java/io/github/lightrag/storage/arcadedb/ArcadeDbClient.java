package io.github.lightrag.storage.arcadedb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.lightrag.exception.StorageException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ArcadeDbClient implements AutoCloseable {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ArcadeDbConfig config;
    private final HttpClient httpClient;
    private final String authorization;

    public ArcadeDbClient(ArcadeDbConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(config.timeout())
            .build();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
            (config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8)
        );
    }

    public List<Map<String, Object>> query(String sql, Object... parameters) {
        var prepared = prepareSqlAndParams(sql, parameters);
        return command("sql", prepared.sql(), prepared.params());
    }

    public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return command("sql", sql, params);
    }

    public List<Map<String, Object>> command(String language, String command, Object... parameters) {
        var prepared = prepareSqlAndParams(command, parameters);
        return command(language, prepared.sql(), prepared.params());
    }

    public List<Map<String, Object>> command(String language, String command, Map<String, Object> params) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(command, "command");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("language", language);
        payload.put("command", command);
        if (params != null && !params.isEmpty()) {
            payload.put("params", params);
        }
        return sendCommand(payload);
    }

    public List<Map<String, Object>> script(String language, String script) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(script, "script");
        var payload = new LinkedHashMap<String, Object>();
        payload.put("language", language);
        payload.put("command", script);
        return sendCommand(payload);
    }

    private List<Map<String, Object>> sendCommand(Map<String, Object> payload) {
        try {
            var request = HttpRequest.newBuilder(commandUri())
                .timeout(config.timeout())
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ArcadeJsonCodec.writeJson(payload)))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new StorageException("ArcadeDB command failed with HTTP " + response.statusCode() + ": " + response.body());
            }
            var commandResponse = ArcadeJsonCodec.OBJECT_MAPPER.readValue(response.body(), CommandResponse.class);
            if (commandResponse.error != null && !commandResponse.error.isBlank()) {
                throw new StorageException("ArcadeDB command failed: " + commandResponse.error);
            }
            if (commandResponse.result == null) {
                return List.of();
            }
            return commandResponse.result.stream()
                .map(row -> ArcadeJsonCodec.OBJECT_MAPPER.convertValue(row, MAP_TYPE))
                .toList();
        } catch (IOException exception) {
            throw new StorageException("Failed to execute ArcadeDB command", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while executing ArcadeDB command", exception);
        }
    }

    private PreparedCommand prepareSqlAndParams(String sql, Object... parameters) {
        Objects.requireNonNull(sql, "sql");
        if (parameters == null || parameters.length == 0 || sql.indexOf('?') < 0) {
            return new PreparedCommand(sql, Map.of());
        }
        var params = new LinkedHashMap<String, Object>();
        var builder = new StringBuilder(sql.length() + parameters.length * 4);
        int index = 0;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '?') {
                var key = "p" + index++;
                builder.append(':').append(key);
                params.put(key, parameters[index - 1]);
            } else {
                builder.append(ch);
            }
        }
        if (index != parameters.length) {
            throw new IllegalArgumentException("Parameter count does not match placeholder count");
        }
        return new PreparedCommand(builder.toString(), Collections.unmodifiableMap(params));
    }

    private URI commandUri() {
        var base = config.baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/api/v1/command/" + URLEncoder.encode(config.database(), StandardCharsets.UTF_8));
    }

    @Override
    public void close() {
        // HttpClient has no explicit close hook on Java 17.
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CommandResponse {
        @JsonProperty("result")
        private List<Object> result;

        @JsonProperty("error")
        private String error;
    }

    private record PreparedCommand(String sql, Map<String, Object> params) {
    }
}
