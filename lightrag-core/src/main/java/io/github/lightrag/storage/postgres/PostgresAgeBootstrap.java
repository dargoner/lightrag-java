package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Bootstraps Apache AGE for one workspace, ported from the upstream Python
 * {@code PostgreSQLDB.configure_age_extension} and {@code PGGraphStorage.initialize}
 * ({@code kg/postgres_impl.py}). Runs once per provider construction on a dedicated
 * auto-commit connection.
 *
 * <p>Index DDL deliberately uses plain {@code CREATE INDEX IF NOT EXISTS} rather than
 * {@code CREATE INDEX CONCURRENTLY}: the concurrent form waits for every transaction that
 * was open when it started, which deadlocks hold-and-wait against an application transaction
 * that is itself waiting for the bootstrap (observed: a platform transaction holding a
 * knowledge-base row lock blocked the bootstrap call until it finished, while the concurrent
 * index build waited for that same transaction forever). Bootstrap only creates missing
 * indexes on an AGE graph it just ensured exists, so the plain build is effectively
 * instantaneous for the common (new graph) case; an already-populated graph that is missing
 * an index briefly blocks writes to its own label tables, which is acceptable.</p>
 *
 * <p>The version gate refuses Apache AGE 1.8.0 and newer unless
 * {@code POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION} (environment variable or system property)
 * is set: from that release on, graph queries can crash the PostgreSQL backend
 * (https://github.com/apache/age/issues/2500). Unreadable or unparseable versions fail
 * closed, matching upstream.</p>
 */
final class PostgresAgeBootstrap {
    private static final Logger log = LoggerFactory.getLogger(PostgresAgeBootstrap.class);

    static final String ALLOW_UNSUPPORTED_VERSION_ENV = "POSTGRES_AGE_ALLOW_UNSUPPORTED_VERSION";
    static final PostgresAgeSupport.AgeVersion FIRST_UNSUPPORTED_VERSION = new PostgresAgeSupport.AgeVersion(1, 8, 0);

    private static final String INSTALLED_VERSION_SQL = "SELECT extversion FROM pg_extension WHERE extname = 'age'";
    private static final String AVAILABLE_VERSION_SQL =
        "SELECT default_version FROM pg_available_extensions WHERE name = 'age'";

    private final DataSource dataSource;
    private final String graphName;

    PostgresAgeBootstrap(DataSource dataSource, String workspaceId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.graphName = PostgresAgeSupport.graphName(workspaceId);
    }

    void bootstrap() {
        var override = allowUnsupportedVersion();
        try (var connection = dataSource.getConnection()) {
            var originalAutoCommit = connection.getAutoCommit();
            try {
                if (!originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
                var decision = decide(
                    readInstalledProbe(connection),
                    readAvailableProbe(connection),
                    override
                );
                if (!decision.proceed()) {
                    throw new StorageException(decision.message());
                }
                if (decision.message() != null) {
                    if (decision.unverified()) {
                        log.warn(decision.message());
                    } else {
                        log.info(decision.message());
                    }
                }
                installExtension(connection);
                var originalSearchPath = readSearchPath(connection);
                try {
                    applySearchPath(connection, agCatalogFirstSearchPath(originalSearchPath));
                    ensureGraph(connection);
                    ensureLabelsAndIndexes(connection);
                } finally {
                    if (originalSearchPath != null) {
                        try {
                            applySearchPath(connection, originalSearchPath);
                        } catch (SQLException restoreFailure) {
                            log.debug(
                                "Could not restore search_path on the Apache AGE bootstrap connection",
                                restoreFailure
                            );
                        }
                    }
                }
            } finally {
                if (!originalAutoCommit) {
                    try {
                        connection.setAutoCommit(originalAutoCommit);
                    } catch (SQLException restoreFailure) {
                        log.debug("Could not restore auto-commit on the Apache AGE bootstrap connection", restoreFailure);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new StorageException(
                "Failed to bootstrap the Apache AGE graph '%s'".formatted(graphName),
                exception
            );
        }
    }

    private void installExtension(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS AGE CASCADE");
        } catch (SQLException exception) {
            throw new StorageException(
                "Could not create the Apache AGE extension for graph '%s'. The 'age' extension must be "
                    .formatted(graphName)
                    + "installable on this PostgreSQL server (for example the official apache/age image); configure "
                    + "lightrag.storage.postgres.graph-backend=table to use the default table backend instead.",
                exception
            );
        }
        log.info("Apache AGE extension enabled for graph '{}'", graphName);
    }

    private void ensureGraph(Connection connection) throws SQLException {
        if (graphExists(connection)) {
            return;
        }
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT ag_catalog.create_graph('" + graphName + "')");
        } catch (SQLException exception) {
            if (!isAlreadyExists(exception)) {
                throw exception;
            }
            log.debug("Apache AGE graph '{}' concurrently created elsewhere: {}", graphName, exception.getMessage());
            return;
        }
        log.info("Apache AGE graph created: {}", graphName);
    }

    private boolean graphExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            "SELECT 1 FROM ag_catalog.ag_graph WHERE name = left(?::text, "
                + PostgresAgeSupport.PG_NAME_MAX_BYTES + ")::name"
        )) {
            statement.setString(1, graphName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * AGE's {@code create_graph} and the label helpers resolve the {@code graphid_ops} opclass by
     * its unqualified name, so {@code ag_catalog} must be on the search_path; without it the
     * bootstrap fails with 'operator class "graphid_ops" does not exist for access method "btree"'
     * (observed with AGE 1.8.0 on PostgreSQL 18). The connection stays in auto-commit, so
     * {@code SET LOCAL} cannot be used: the path is applied with session scope and the caller
     * restores the original value afterwards.
     */
    static String agCatalogFirstSearchPath(String original) {
        if (original == null || original.isBlank()) {
            return "ag_catalog, \"$user\", public";
        }
        return "ag_catalog, " + original.strip();
    }

    private static String readSearchPath(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SHOW search_path")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static void applySearchPath(Connection connection, String searchPath) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('search_path', ?, false)")) {
            statement.setString(1, searchPath);
            statement.execute();
        }
    }

    private void ensureLabelsAndIndexes(Connection connection) throws SQLException {
        var presentLabels = presentLabels(connection);
        if (!presentLabels.contains("base")) {
            executeIgnoringAlreadyExists(connection, "SELECT ag_catalog.create_vlabel('" + graphName + "', 'base')");
        }
        if (!presentLabels.contains("DIRECTED")) {
            executeIgnoringAlreadyExists(connection, "SELECT ag_catalog.create_elabel('" + graphName + "', 'DIRECTED')");
        }
        for (var ddl : indexDdl()) {
            executeIgnoringAlreadyExists(connection, ddl);
        }
    }

    private Set<String> presentLabels(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
            SELECT l.name::text AS name
            FROM ag_catalog.ag_label l
            JOIN ag_catalog.ag_graph g ON l.graph = g.graphid
            WHERE g.name = left(?::text, %d)::name
            """.formatted(PostgresAgeSupport.PG_NAME_MAX_BYTES)
        )) {
            statement.setString(1, graphName);
            try (var resultSet = statement.executeQuery()) {
                var labels = new LinkedHashSet<String>();
                while (resultSet.next()) {
                    labels.add(resultSet.getString("name"));
                }
                return labels;
            }
        }
    }

    private void executeIgnoringAlreadyExists(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            if (!isAlreadyExists(exception)) {
                throw exception;
            }
            log.debug("Ignoring already-exists condition during Apache AGE bootstrap: {}", exception.getMessage());
        }
    }

    /** Mirrors upstream {@code execute(ignore_if_exists=True)}: these four SQLSTATEs are races, not failures. */
    private static boolean isAlreadyExists(SQLException exception) {
        var sqlState = exception.getSQLState();
        return "23505".equals(sqlState) || "42P07".equals(sqlState) || "42710".equals(sqlState) || "3F000".equals(sqlState);
    }

    private List<String> indexDdl() {
        // The schema is double-quoted because create_graph preserves case; see PostgresAgeGraphStore#qualifiedLabel.
        return List.of(
            """
            CREATE INDEX IF NOT EXISTS vertex_idx_node_id ON "%s"."_ag_label_vertex"
                (ag_catalog.agtype_access_operator(properties, '"entity_id"'::ag_catalog.agtype))""",
            """
            CREATE INDEX IF NOT EXISTS edge_sid_idx ON "%s"."_ag_label_edge" (start_id)""",
            """
            CREATE INDEX IF NOT EXISTS edge_eid_idx ON "%s"."_ag_label_edge" (end_id)""",
            """
            CREATE INDEX IF NOT EXISTS edge_seid_idx ON "%s"."_ag_label_edge" (start_id,end_id)""",
            """
            CREATE INDEX IF NOT EXISTS directed_p_idx ON "%s"."DIRECTED" (id)""",
            """
            CREATE INDEX IF NOT EXISTS directed_eid_idx ON "%s"."DIRECTED" (end_id)""",
            """
            CREATE INDEX IF NOT EXISTS directed_sid_idx ON "%s"."DIRECTED" (start_id)""",
            """
            CREATE INDEX IF NOT EXISTS directed_seid_idx ON "%s"."DIRECTED" (start_id,end_id)""",
            """
            CREATE INDEX IF NOT EXISTS entity_p_idx ON "%s"."base" (id)""",
            """
            CREATE INDEX IF NOT EXISTS entity_idx_node_id ON "%s"."base"
                (ag_catalog.agtype_access_operator(properties, '"entity_id"'::ag_catalog.agtype))""",
            """
            CREATE INDEX IF NOT EXISTS entity_node_id_gin_idx ON "%s"."base" using gin(properties)""",
            """
            ALTER TABLE "%s"."DIRECTED" CLUSTER ON directed_sid_idx"""
        ).stream().map(ddl -> ddl.formatted(graphName)).toList();
    }

    private VersionProbe readInstalledProbe(Connection connection) {
        try (var statement = connection.prepareStatement(INSTALLED_VERSION_SQL);
             var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return VersionProbe.ABSENT;
            }
            var raw = resultSet.getString(1);
            return raw == null ? VersionProbe.withoutVersion() : VersionProbe.readable(raw);
        } catch (SQLException exception) {
            log.warn("PostgreSQL, could not read the installed Apache AGE version: {}", exception.getMessage());
            return VersionProbe.withoutVersion();
        }
    }

    private VersionProbe readAvailableProbe(Connection connection) {
        try (var statement = connection.prepareStatement(AVAILABLE_VERSION_SQL);
             var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return VersionProbe.ABSENT;
            }
            // default_version is NULLABLE: a row naming 'age' without a version is present, not absent.
            var raw = resultSet.getString(1);
            return raw == null ? VersionProbe.withoutVersion() : VersionProbe.readable(raw);
        } catch (SQLException exception) {
            log.warn(
                "PostgreSQL, could not read the available Apache AGE version from pg_available_extensions: {}",
                exception.getMessage()
            );
            return VersionProbe.withoutVersion();
        }
    }

    static boolean allowUnsupportedVersion() {
        var value = System.getenv(ALLOW_UNSUPPORTED_VERSION_ENV);
        if (value == null) {
            value = System.getProperty(ALLOW_UNSUPPORTED_VERSION_ENV);
        }
        if (value == null) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "t", "on" -> true;
            default -> false;
        };
    }

    static GateDecision decide(VersionProbe installed, VersionProbe available, boolean override) {
        var installedVersion = versionOf(installed);
        var availableVersion = versionOf(available);
        var found = "installed=" + display(installed) + " available=" + display(available);
        var readable = new ArrayList<PostgresAgeSupport.AgeVersion>(2);
        if (installedVersion != null) {
            readable.add(installedVersion);
        }
        if (availableVersion != null) {
            readable.add(availableVersion);
        }

        // The higher of the two decides - a stale catalog must not mask newer binaries. A readable
        // version we know to be unsupported is reported before an unreadable sibling source, which
        // cannot make the situation safer.
        if (!readable.isEmpty()) {
            var highest = Collections.max(readable);
            if (highest.compareTo(FIRST_UNSUPPORTED_VERSION) >= 0) {
                if (override) {
                    return new GateDecision(true, true,
                        ("Apache AGE %s (%s) is known to break graph queries, but %s is set, so the version check "
                            + "is being skipped. A single graph request may take the whole PostgreSQL instance "
                            + "through crash recovery.")
                            .formatted(highest, found, ALLOW_UNSUPPORTED_VERSION_ENV));
                }
                return new GateDecision(false, false, unsupportedMessage(installedVersion, availableVersion, highest, found));
            }
        }

        // An unverified version is not a safe one: a source that is present but unreadable or
        // unparseable fails closed, exactly like upstream.
        var unverifiedSource = (installed.present() && installedVersion == null)
            || (available.present() && availableVersion == null);
        if (unverifiedSource) {
            if (override) {
                return new GateDecision(true, true,
                    ("Could not determine the Apache AGE version (%s), but %s is set, so the graph backend "
                        + "starts anyway.")
                        .formatted(found, ALLOW_UNSUPPORTED_VERSION_ENV));
            }
            return new GateDecision(false, false,
                ("Could not determine the Apache AGE version (%s). The version is required in order to reject "
                    + "releases that crash the PostgreSQL backend (https://github.com/apache/age/issues/2500), and "
                    + "an unverified version is not treated as a safe one. Set %s=true to start anyway at your own "
                    + "risk, or configure lightrag.storage.postgres.graph-backend=table, which needs no PostgreSQL "
                    + "extension.")
                    .formatted(found, ALLOW_UNSUPPORTED_VERSION_ENV));
        }

        if (!installed.present() && !available.present()) {
            return new GateDecision(true, false,
                "No Apache AGE version check was performed: neither pg_extension nor pg_available_extensions "
                    + "names 'age', so the extension is not on disk and not registered in this database.");
        }
        return new GateDecision(true, false, null);
    }

    private static String unsupportedMessage(
        PostgresAgeSupport.AgeVersion installed,
        PostgresAgeSupport.AgeVersion available,
        PostgresAgeSupport.AgeVersion highest,
        String found
    ) {
        var message = new StringBuilder(
            ("Apache AGE %s is not supported (%s): from %s onward, graph queries can terminate the PostgreSQL "
                + "backend with SIGSEGV and take the whole instance through crash recovery "
                + "(https://github.com/apache/age/issues/2500). Verified good: AGE 1.7.0. Pin AGE to a verified "
                + "version, set %s=true to run anyway at your own risk, or configure "
                + "lightrag.storage.postgres.graph-backend=table, which needs no PostgreSQL extension.")
                .formatted(highest, found, FIRST_UNSUPPORTED_VERSION, ALLOW_UNSUPPORTED_VERSION_ENV)
        );
        if (installed != null && installed.compareTo(FIRST_UNSUPPORTED_VERSION) >= 0
            && available != null && available.compareTo(FIRST_UNSUPPORTED_VERSION) < 0) {
            message.append(
                " Here pg_extension records the newer AGE script while age.control reports the older one, so the "
                    + "older library is what loads: AGE is then non-functional rather than crash-prone - writes "
                    + "succeed but every read fails with 'ag function does not exist'. Restore the newer binaries "
                    + "(this backend will then refuse them as unsupported), or DROP EXTENSION age CASCADE and "
                    + "recreate the graph."
            );
        }
        return message.toString();
    }

    private static PostgresAgeSupport.AgeVersion versionOf(VersionProbe probe) {
        if (!probe.present() || probe.unreadable()) {
            return null;
        }
        return PostgresAgeSupport.parseAgeVersion(probe.raw());
    }

    private static String display(VersionProbe probe) {
        if (!probe.present()) {
            return "absent";
        }
        if (probe.unreadable()) {
            return "<unreadable>";
        }
        return "'" + probe.raw() + "'";
    }

    /** One catalog lookup: {@code present=false} means the catalog answered "no such row". */
    record VersionProbe(boolean present, String raw, boolean unreadable) {
        static final VersionProbe ABSENT = new VersionProbe(false, null, false);

        static VersionProbe readable(String raw) {
            return new VersionProbe(true, raw, false);
        }

        static VersionProbe withoutVersion() {
            return new VersionProbe(true, null, true);
        }
    }

    record GateDecision(boolean proceed, boolean unverified, String message) {
    }
}
