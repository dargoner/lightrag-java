package io.github.lightrag.storage.arcadedb;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

abstract class ArcadeStoreSupport {
    protected final ArcadeDbClient client;
    protected final String workspaceId;

    ArcadeStoreSupport(ArcadeDbClient client, String workspaceId) {
        this.client = Objects.requireNonNull(client, "client");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    protected List<Map<String, Object>> query(String sql, Object... parameters) {
        return client.query(sql, parameters);
    }

    protected List<Map<String, Object>> query(String sql, Map<String, Object> parameters) {
        return client.query(sql, parameters);
    }

    protected void execute(String sql, Object... parameters) {
        client.command("sql", sql, parameters);
    }

    protected Optional<Map<String, Object>> first(String sql, Object... parameters) {
        var rows = query(sql, parameters);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    protected void upsertByWorkspaceId(String type, String idField, String idValue, Map<String, Object> properties) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idField, "idField");
        Objects.requireNonNull(idValue, "idValue");
        var existing = first("SELECT @rid FROM " + type + " WHERE workspaceId = ? AND " + idField + " = ? LIMIT 1", workspaceId, idValue);
        if (existing.isPresent()) {
            var sql = "UPDATE " + type + " SET " + assignmentList(properties) + " WHERE workspaceId = ? AND " + idField + " = ?";
            var params = new java.util.ArrayList<>(properties.values());
            params.add(workspaceId);
            params.add(idValue);
            execute(sql, params.toArray());
        } else {
            var merged = new java.util.LinkedHashMap<String, Object>();
            merged.put("workspaceId", workspaceId);
            merged.put(idField, idValue);
            merged.putAll(properties);
            var sql = "INSERT INTO " + type + " SET " + assignmentList(merged);
            execute(sql, merged.values().toArray());
        }
    }

    protected void deleteWorkspaceRows(String type) {
        execute("DELETE FROM " + type + " WHERE workspaceId = ?", workspaceId);
    }

    private static String assignmentList(Map<String, Object> properties) {
        if (properties.isEmpty()) {
            throw new IllegalArgumentException("properties must not be empty");
        }
        return String.join(", ", properties.keySet().stream().map(key -> key + " = ?").toList());
    }
}
