package io.github.lightrag.storage.nebula;

import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.SessionPool;
import com.vesoft.nebula.client.graph.SessionPoolConfig;
import com.vesoft.nebula.client.graph.data.HostAddress;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.InvalidConfigException;
import com.vesoft.nebula.client.graph.exception.InvalidValueException;
import com.vesoft.nebula.client.graph.exception.NotValidConnectionException;
import com.vesoft.nebula.client.graph.net.Session;
import io.github.lightrag.exception.StorageException;

import java.io.UnsupportedEncodingException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure helpers and the space bootstrap for the NebulaGraph backend, nGQL (NebulaGraph 3.x OSS)
 * dialect. One space holds every workspace: vertices and edges carry a {@code workspace_id}
 * property, and vertex ids are SHA-256 digests of workspace plus entity id, so workspaces cannot
 * collide inside the shared space.
 *
 * <p>Both the tag and the edge own an {@code attributes} STRING property holding the canonical
 * JSON attribute bag ({@code "{}"} when empty). Spaces created before that property existed are
 * upgraded in place: the bootstrap probes {@code DESCRIBE TAG/EDGE}, runs the idempotent
 * {@code ALTER TAG/EDGE ... ADD} when the column is missing, then verifies with a real
 * write-and-read-back probe before the store accepts any work. If the upgrade cannot be completed
 * the bootstrap fails with the exact manual {@code ALTER} statements instead of silently dropping
 * attributes at write time.</p>
 */
final class NebulaSupport {
    static final int VID_LENGTH = 64;

    static final int DELETE_BATCH_SIZE = 1000;

    /** Canonical-JSON attribute bag column shared by the tag and the edge. */
    static final String ATTRIBUTES_COLUMN = "attributes";

    static final String CREATE_TAG_STATEMENT =
        "CREATE TAG IF NOT EXISTS entity("
            + "entity_id string, name string, entity_type string, description string, aliases string, "
            + "source_id string, file_path string, workspace_id string, materialized bool, "
            + ATTRIBUTES_COLUMN + " string);";

    static final String CREATE_EDGE_STATEMENT =
        "CREATE EDGE IF NOT EXISTS directed("
            + "relation_id string, src_id string, tgt_id string, keywords string, description string, "
            + "weight double, source_id string, file_path string, workspace_id string, "
            + ATTRIBUTES_COLUMN + " string);";

    static final String DESCRIBE_TAG_STATEMENT = "DESCRIBE TAG entity;";

    static final String DESCRIBE_EDGE_STATEMENT = "DESCRIBE EDGE directed;";

    /** Idempotent schema upgrade for spaces created before attributes were materialized. */
    static final String ALTER_TAG_ATTRIBUTES_STATEMENT =
        "ALTER TAG entity ADD (" + ATTRIBUTES_COLUMN + " string);";

    static final String ALTER_EDGE_ATTRIBUTES_STATEMENT =
        "ALTER EDGE directed ADD (" + ATTRIBUTES_COLUMN + " string);";

    static final String CREATE_ENTITY_WORKSPACE_INDEX_STATEMENT =
        "CREATE TAG INDEX IF NOT EXISTS idx_entity_workspace_id ON entity(workspace_id(64));";

    static final String CREATE_DIRECTED_WORKSPACE_INDEX_STATEMENT =
        "CREATE EDGE INDEX IF NOT EXISTS idx_directed_workspace_id ON directed(workspace_id(64));";

    static final String CREATE_DIRECTED_WORKSPACE_RELATION_INDEX_STATEMENT =
        "CREATE EDGE INDEX IF NOT EXISTS idx_directed_workspace_relation_id "
            + "ON directed(workspace_id(64), relation_id(64));";

    private static final long SPACE_READY_POLL_MILLIS = 500;

    private static final long SPACE_READY_TIMEOUT_MILLIS = 60_000;

    private static final long SCHEMA_READY_TIMEOUT_MILLIS = 60_000;

    /** Bound for the post-upgrade attribute write/read probe (plan requires a real round trip). */
    private static final long ATTRIBUTES_WRITE_PROBE_TIMEOUT_MILLIS = 10_000;

    private static final String SCHEMA_PROBE_WORKSPACE_ID = "__schema_probe__";

    static final String ATTRIBUTES_PROBE_VERTEX_JSON = "{\"probe\":\"vertex\"}";

    static final String ATTRIBUTES_PROBE_EDGE_JSON = "{\"probe\":\"edge\"}";

    /**
     * A fresh 64-character probe vid. Concurrent bootstraps in the same space must not delete
     * each other's probe rows, so every invocation gets its own ids instead of a shared constant.
     */
    static String newProbeVid() {
        return UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    }

    static String schemaProbeInsertStatement(String probeVid) {
        return "INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, file_path, "
            + "workspace_id, materialized) VALUES " + quoted(probeVid)
            + ":(\"__schema_probe__\", \"\", \"\", \"\", \"\", \"\", \"\", " + quoted(SCHEMA_PROBE_WORKSPACE_ID)
            + ", true);";
    }

    static String schemaProbeDeleteStatement(String probeVid) {
        return "DELETE VERTEX " + quoted(probeVid) + " WITH EDGE;";
    }

    static String attributesProbeVertexInsertStatement(String probeVid, String targetVid) {
        return "INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, file_path, "
            + "workspace_id, materialized, " + ATTRIBUTES_COLUMN + ") VALUES "
            + quoted(probeVid) + ":"
            + tuple("__schema_probe__", "", "", "", "", "", "", SCHEMA_PROBE_WORKSPACE_ID, true,
                ATTRIBUTES_PROBE_VERTEX_JSON)
            + ", " + quoted(targetVid) + ":"
            + tuple("__schema_probe_target__", "", "", "", "", "", "", SCHEMA_PROBE_WORKSPACE_ID, true,
                ATTRIBUTES_PROBE_VERTEX_JSON)
            + ";";
    }

    static String attributesProbeEdgeInsertStatement(String probeVid, String targetVid) {
        return "INSERT EDGE directed(relation_id, src_id, tgt_id, keywords, description, weight, source_id, "
            + "file_path, workspace_id, " + ATTRIBUTES_COLUMN + ") VALUES "
            + quoted(probeVid) + "->" + quoted(targetVid) + ":"
            + tuple("__schema_probe_edge__", "__schema_probe__", "__schema_probe_target__", "", "", 0.0d,
                "", "", SCHEMA_PROBE_WORKSPACE_ID, ATTRIBUTES_PROBE_EDGE_JSON)
            + ";";
    }

    static String attributesProbeVertexReadStatement(String probeVid) {
        // Tag-qualified access is required: unqualified v.attributes reads back NULL on 3.8
        // even though properties(v) contains the value.
        return "MATCH (v:entity) WHERE id(v) == " + quoted(probeVid)
            + " RETURN v.entity." + ATTRIBUTES_COLUMN + " AS attrs;";
    }

    static String attributesProbeEdgeReadStatement(String probeVid, String targetVid) {
        return "MATCH (a:entity)-[e:directed]->(b:entity) WHERE id(a) == " + quoted(probeVid)
            + " AND id(b) == " + quoted(targetVid)
            + " RETURN e." + ATTRIBUTES_COLUMN + " AS attrs;";
    }

    static String attributesProbeDeleteStatement(String probeVid, String targetVid) {
        return "DELETE VERTEX " + quoted(probeVid) + ", " + quoted(targetVid) + " WITH EDGE;";
    }

    private NebulaSupport() {
    }

    /**
     * The exact upgrade statements an operator must apply to a space created before attributes
     * were materialized. Included verbatim in every schema-upgrade failure message.
     */
    static String attributesUpgradeInstruction() {
        return ALTER_TAG_ATTRIBUTES_STATEMENT + " " + ALTER_EDGE_ATTRIBUTES_STATEMENT;
    }

    /**
     * Vertex id for an entity: the SHA-256 hex digest of workspace id + NUL + entity id. The NUL
     * separator keeps {@code ("ab", "c")} and {@code ("a", "bc")} apart, and the fixed 64-character
     * digest matches the space's {@code FIXED_STRING(64)} vid type.
     */
    static String scopedVid(String workspaceId, String entityId) {
        return sha256Hex(workspaceId + "\u0000" + entityId);
    }

    /**
     * A double-quoted nGQL string literal. Only backslash, double quote, newline, carriage return
     * and tab are escaped - everything else (including CJK, emoji, backticks, semicolons and
     * percent signs) passes through unchanged, verified against the 3.8 parser.
     */
    static String quoted(String value) {
        var text = value == null ? "" : value;
        var builder = new StringBuilder(text.length() + 2);
        builder.append('"');
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    /** The single address list both pools are built from. */
    static List<HostAddress> addresses(NebulaGraphConfig config) {
        return List.of(new HostAddress(config.host(), config.port()));
    }

    /**
     * Boots the space (idempotently) and opens the session pool bound to it. The bootstrap must
     * complete first: a {@link SessionPool} binds every session with {@code USE space}, so it
     * cannot even be constructed before the space exists.
     */
    static SessionPool openSessionPool(NebulaGraphConfig config) {
        ensureSpace(config);
        return new SessionPool(new SessionPoolConfig(
            addresses(config),
            config.space(),
            config.username(),
            config.password()
        ));
    }

    /**
     * Creates the space, its tag/edge schema and indexes when missing, upgrades the attribute
     * columns of spaces created before they existed, then waits until the space is usable and the
     * schema accepts writes - a freshly created space is not queryable until the meta heartbeat
     * catches up, and schema changes reach the storage engine on that same heartbeat. Every
     * statement is idempotent, so replaying the bootstrap on an existing space is a no-op. The
     * attribute upgrade completes only after a real vertex/edge write-and-read-back probe.
     */
    static void ensureSpace(NebulaGraphConfig config) {
        var pool = new NebulaPool();
        try {
            if (!pool.init(addresses(config), new NebulaPoolConfig())) {
                throw new StorageException(
                    "NebulaGraph bootstrap cannot reach all graphd addresses " + addresses(config)
                );
            }
            var session = pool.getSession(config.username(), config.password(), true);
            try {
                run(session, "create space", createSpaceStatement(config));
                awaitSpaceReady(session, config.space());
                run(session, "create entity tag", CREATE_TAG_STATEMENT);
                run(session, "create directed edge", CREATE_EDGE_STATEMENT);
                run(session, "create entity workspace index", CREATE_ENTITY_WORKSPACE_INDEX_STATEMENT);
                run(session, "create directed workspace index", CREATE_DIRECTED_WORKSPACE_INDEX_STATEMENT);
                run(session, "create directed workspace relation index", CREATE_DIRECTED_WORKSPACE_RELATION_INDEX_STATEMENT);
                awaitSchemaReady(session);
                ensureAttributesSchema(session, config.space());
                awaitAttributesWritable(session, config.space());
            } finally {
                session.release();
            }
        } catch (StorageException exception) {
            throw exception;
        } catch (UnknownHostException | InvalidConfigException exception) {
            throw new StorageException("NebulaGraph bootstrap failed to initialise the connection pool", exception);
        } catch (NotValidConnectionException | IOErrorException | AuthFailedException
                 | ClientServerIncompatibleException exception) {
            throw new StorageException("NebulaGraph bootstrap could not open a session", exception);
        } finally {
            pool.close();
        }
    }

    static String createSpaceStatement(NebulaGraphConfig config) {
        return ("CREATE SPACE IF NOT EXISTS `%s` "
            + "(partition_num = %d, replica_factor = %d, vid_type = FIXED_STRING(%d));")
            .formatted(config.space(), config.partitionNum(), config.replicaFactor(), VID_LENGTH);
    }

    /** Runs one statement on a bootstrap/direct session; throws on any failed result. */
    static ResultSet run(Session session, String description, String statement) throws IOErrorException {
        var resultSet = session.execute(statement);
        if (!resultSet.isSucceeded()) {
            throw new StorageException(
                "NebulaGraph %s failed: %s".formatted(description, errorText(resultSet))
            );
        }
        return resultSet;
    }

    static String errorText(ResultSet resultSet) {
        if (resultSet == null) {
            return "no result";
        }
        return "[%d] %s".formatted(resultSet.getErrorCode(), resultSet.getErrorMessage());
    }

    /** Reads one value that must be a string (a vid or a space name). */
    static String string(ValueWrapper value, String description) {
        if (value == null) {
            throw new StorageException("NebulaGraph " + description + " is missing");
        }
        try {
            return value.asString();
        } catch (InvalidValueException | UnsupportedEncodingException exception) {
            throw new StorageException("NebulaGraph " + description + " is not a string value", exception);
        }
    }

    static <T> List<List<T>> batches(List<T> values) {
        if (values.size() <= DELETE_BATCH_SIZE) {
            return List.of(List.copyOf(values));
        }
        var batches = new ArrayList<List<T>>();
        for (var index = 0; index < values.size(); index += DELETE_BATCH_SIZE) {
            batches.add(List.copyOf(values.subList(index, Math.min(index + DELETE_BATCH_SIZE, values.size()))));
        }
        return List.copyOf(batches);
    }

    static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    /**
     * A fresh space answers {@code USE} with {@code SpaceNotFound} until the meta heartbeat
     * publishes it (about two seconds on a local single-node cluster), so every failed attempt is
     * retried until the deadline; the last error is reported if it never becomes usable.
     */
    private static void awaitSpaceReady(Session session, String space) throws IOErrorException {
        var deadline = System.currentTimeMillis() + SPACE_READY_TIMEOUT_MILLIS;
        while (true) {
            var resultSet = session.execute("USE `" + space + "`;");
            if (resultSet.isSucceeded()) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new StorageException(
                    "NebulaGraph space '%s' did not become usable within %d ms: %s"
                        .formatted(space, SPACE_READY_TIMEOUT_MILLIS, errorText(resultSet))
                );
            }
            sleep(SPACE_READY_POLL_MILLIS, "NebulaGraph space '%s'".formatted(space));
        }
    }

    /**
     * Waits until the bootstrapped schema accepts writes on the full write path. Freshly created
     * tags and edges reach the storage engine asynchronously (about two heartbeats), and until
     * then the graph engine rejects with {@code No schema found} and storaged with
     * {@code Tag not found}. An INSERT probe exercises exactly that path, while a read such as
     * {@code DESCRIBE TAG} could already be served from the graph engine's cached metadata and
     * return before storaged is ready.
     */
    private static void awaitSchemaReady(Session session) throws IOErrorException {
        var deadline = System.currentTimeMillis() + SCHEMA_READY_TIMEOUT_MILLIS;
        String probeVid = newProbeVid();
        while (true) {
            var resultSet = session.execute(schemaProbeInsertStatement(probeVid));
            if (resultSet.isSucceeded()) {
                run(session, "delete schema probe vertex", schemaProbeDeleteStatement(probeVid));
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new StorageException(
                    "NebulaGraph schema did not accept writes within %d ms: %s"
                        .formatted(SCHEMA_READY_TIMEOUT_MILLIS, errorText(resultSet))
                );
            }
            sleep(SPACE_READY_POLL_MILLIS, "NebulaGraph schema to accept writes");
        }
    }

    /**
     * Upgrades spaces created before attributes were materialized. A missing column is added with
     * the idempotent {@code ALTER TAG/EDGE ... ADD}; a concurrent bootstrap that added it first is
     * tolerated as success. Any other failure reports the exact manual upgrade statements instead
     * of letting later writes silently skip the attribute bag.
     */
    private static void ensureAttributesSchema(Session session, String space) throws IOErrorException {
        ensureAttributesColumn(session, space, "tag 'entity'", DESCRIBE_TAG_STATEMENT, ALTER_TAG_ATTRIBUTES_STATEMENT);
        ensureAttributesColumn(session, space, "edge 'directed'", DESCRIBE_EDGE_STATEMENT, ALTER_EDGE_ATTRIBUTES_STATEMENT);
    }

    private static void ensureAttributesColumn(
        Session session,
        String space,
        String subject,
        String describeStatement,
        String alterStatement
    ) throws IOErrorException {
        if (describeHasColumn(session, space, subject, describeStatement)) {
            return;
        }
        var alterResult = session.execute(alterStatement);
        if (!alterResult.isSucceeded() && !isDuplicateColumnFailure(alterResult)) {
            throw new StorageException(
                attributesUpgradeFailure(space, subject, "automatic ALTER failed: " + errorText(alterResult))
            );
        }
        if (!describeHasColumn(session, space, subject, describeStatement)) {
            throw new StorageException(attributesUpgradeFailure(
                space, subject, "automatic ALTER did not add the column: " + alterStatement
            ));
        }
    }

    private static boolean describeHasColumn(
        Session session,
        String space,
        String subject,
        String describeStatement
    ) throws IOErrorException {
        var resultSet = session.execute(describeStatement);
        if (resultSet == null || !resultSet.isSucceeded()) {
            throw new StorageException(attributesUpgradeFailure(
                space, subject, describeStatement + " failed: " + errorText(resultSet)
            ));
        }
        var fields = new ArrayList<String>(resultSet.rowsSize());
        for (var index = 0; index < resultSet.rowsSize(); index++) {
            var field = resultSet.rowValues(index).get("Field");
            if (field != null && !field.isNull()) {
                fields.add(string(field, subject + " field name"));
            }
        }
        return containsColumn(fields, ATTRIBUTES_COLUMN);
    }

    /** Whether a {@code DESCRIBE TAG/EDGE} field list already contains the attribute column. */
    static boolean containsColumn(List<String> fields, String column) {
        for (var field : fields) {
            if (field != null && column.equals(field.strip())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Concurrent bootstraps may both run ALTER; the loser reports "already exists"/"duplicate"/
     * "conflict", which means the column is present and the upgrade is effectively done.
     */
    private static boolean isDuplicateColumnFailure(ResultSet resultSet) {
        var message = resultSet == null ? null : resultSet.getErrorMessage();
        if (message == null) {
            return false;
        }
        var normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("already exist")
            || normalized.contains("duplicate")
            || normalized.contains("conflict");
    }

    /**
     * Verifies that a non-empty attribute bag really persists on both the tag and the edge before
     * the store serves any work. Schema changes propagate asynchronously, so the probe retries until
     * {@link #ATTRIBUTES_WRITE_PROBE_TIMEOUT_MILLIS} and then fails with the manual upgrade steps.
     */
    private static void awaitAttributesWritable(Session session, String space) throws IOErrorException {
        var deadline = System.currentTimeMillis() + ATTRIBUTES_WRITE_PROBE_TIMEOUT_MILLIS;
        String lastFailure = null;
        while (true) {
            try {
                verifyAttributesRoundTrip(session);
                return;
            } catch (StorageException failure) {
                lastFailure = failure.getMessage();
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new StorageException(attributesUpgradeFailure(
                    space,
                    "tag 'entity' and edge 'directed'",
                    "write/read attribute probe failed: " + lastFailure
                ));
            }
            sleep(SPACE_READY_POLL_MILLIS, "NebulaGraph attributes schema to accept writes");
        }
    }

    private static void verifyAttributesRoundTrip(Session session) throws IOErrorException {
        String probeVid = newProbeVid();
        String targetVid = newProbeVid();
        try {
            run(session, "write attributes probe vertices",
                attributesProbeVertexInsertStatement(probeVid, targetVid));
            run(session, "write attributes probe edge",
                attributesProbeEdgeInsertStatement(probeVid, targetVid));
            assertProbeValue(
                run(session, "read attributes probe vertex",
                    attributesProbeVertexReadStatement(probeVid)),
                ATTRIBUTES_PROBE_VERTEX_JSON,
                "entity"
            );
            assertProbeValue(
                run(session, "read attributes probe edge",
                    attributesProbeEdgeReadStatement(probeVid, targetVid)),
                ATTRIBUTES_PROBE_EDGE_JSON,
                "edge"
            );
        } finally {
            try {
                session.execute(attributesProbeDeleteStatement(probeVid, targetVid));
            } catch (IOErrorException ignored) {
                // Best-effort cleanup: unique probe vids are harmless if they linger.
            }
        }
    }

    private static void assertProbeValue(ResultSet resultSet, String expected, String subject) {
        if (resultSet.rowsSize() == 0) {
            throw new StorageException(
                "NebulaGraph " + subject + " attribute probe read back no row"
            );
        }
        var stored = string(resultSet.rowValues(0).get("attrs"), subject + " attribute probe value");
        if (!expected.equals(stored)) {
            throw new StorageException(
                "NebulaGraph %s attribute probe read back '%s' instead of '%s'"
                    .formatted(subject, stored, expected)
            );
        }
    }

    private static String attributesUpgradeFailure(String space, String subject, String detail) {
        return ("NebulaGraph space '%s' cannot persist attributes: the '%s' column is missing from %s. "
            + "Apply the upgrade manually before using this SDK: %s "
            + "Then verify with '%s' and '%s'. (%s)")
            .formatted(
                space,
                ATTRIBUTES_COLUMN,
                subject,
                attributesUpgradeInstruction(),
                DESCRIBE_TAG_STATEMENT,
                DESCRIBE_EDGE_STATEMENT,
                detail
            );
    }

    /** One VALUES field list: strings become quoted literals, everything else a raw literal. */
    private static String tuple(Object... fields) {
        var parts = new ArrayList<String>(fields.length);
        for (var field : fields) {
            parts.add(field instanceof String text ? quoted(text) : String.valueOf(field));
        }
        return "(" + String.join(", ", parts) + ")";
    }

    private static void sleep(long millis, String waitingFor) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while waiting for " + waitingFor, interruptedException);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }
}
