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
import java.util.Objects;

/**
 * Pure helpers and the space bootstrap for the NebulaGraph backend, nGQL (NebulaGraph 3.x OSS)
 * dialect. One space holds every workspace: vertices and edges carry a {@code workspace_id}
 * property, and vertex ids are SHA-256 digests of workspace plus entity id, so workspaces cannot
 * collide inside the shared space.
 */
final class NebulaSupport {
    static final int VID_LENGTH = 64;

    static final int DELETE_BATCH_SIZE = 1000;

    static final String CREATE_TAG_STATEMENT =
        "CREATE TAG IF NOT EXISTS entity("
            + "entity_id string, name string, entity_type string, description string, aliases string, "
            + "source_id string, file_path string, workspace_id string, materialized bool);";

    static final String CREATE_EDGE_STATEMENT =
        "CREATE EDGE IF NOT EXISTS directed("
            + "relation_id string, src_id string, tgt_id string, keywords string, description string, "
            + "weight double, source_id string, file_path string, workspace_id string);";

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

    /** A fixed all-zero vid, cannot collide with the SHA-256 vids real entities get. */
    private static final String SCHEMA_PROBE_VID = "0".repeat(VID_LENGTH);

    private static final String SCHEMA_PROBE_WORKSPACE_ID = "__schema_probe__";

    private static final String SCHEMA_PROBE_INSERT_STATEMENT =
        "INSERT VERTEX entity(entity_id, name, entity_type, description, aliases, source_id, file_path, "
            + "workspace_id, materialized) VALUES " + quoted(SCHEMA_PROBE_VID)
            + ":(\"__schema_probe__\", \"\", \"\", \"\", \"\", \"\", \"\", " + quoted(SCHEMA_PROBE_WORKSPACE_ID)
            + ", true);";

    private static final String SCHEMA_PROBE_DELETE_STATEMENT =
        "DELETE VERTEX " + quoted(SCHEMA_PROBE_VID) + " WITH EDGE;";

    private NebulaSupport() {
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
     * Creates the space, its tag/edge schema and indexes when missing, then waits until the space
     * is usable and the schema accepts writes - a freshly created space is not queryable until the
     * meta heartbeat catches up, and schema changes reach the storage engine on that same
     * heartbeat. Every statement is idempotent, so replaying the bootstrap on an existing space is
     * a no-op.
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
        while (true) {
            var resultSet = session.execute(SCHEMA_PROBE_INSERT_STATEMENT);
            if (resultSet.isSucceeded()) {
                run(session, "delete schema probe vertex", SCHEMA_PROBE_DELETE_STATEMENT);
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
