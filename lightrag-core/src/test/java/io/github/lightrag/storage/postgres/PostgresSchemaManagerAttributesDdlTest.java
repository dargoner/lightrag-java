package io.github.lightrag.storage.postgres;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the attribute-column DDL without a live PostgreSQL: fresh schemas declare the column, and
 * the upgrade path is a replay-safe pair of {@code ALTER TABLE IF EXISTS ... ADD COLUMN IF NOT
 * EXISTS} statements, so bootstrapping an old schema any number of times converges.
 */
class PostgresSchemaManagerAttributesDdlTest {
    private static final PostgresStorageConfig CONFIG = new PostgresStorageConfig(
        "jdbc:postgresql://localhost:5432/lightrag",
        "lightrag",
        "secret",
        "rag",
        3,
        "lr_",
        PostgresVectorDialect.PGVECTOR
    );

    @Test
    void attributesColumnStatementsAreIdempotentForEntitiesAndRelations() {
        var statements = PostgresSchemaManager.attributesColumnStatements(CONFIG);

        assertThat(statements).hasSize(2);
        assertThat(statements.get(0))
            .contains("ALTER TABLE IF EXISTS \"rag\".\"lr_entities\"")
            .contains("ADD COLUMN IF NOT EXISTS attributes TEXT NOT NULL DEFAULT '{}'");
        assertThat(statements.get(1))
            .contains("ALTER TABLE IF EXISTS \"rag\".\"lr_relations\"")
            .contains("ADD COLUMN IF NOT EXISTS attributes TEXT NOT NULL DEFAULT '{}'");
        assertThat(PostgresSchemaManager.attributesColumnStatements(CONFIG))
            .as("the migration must be replayable without schema state")
            .isEqualTo(statements);
    }

    @Test
    void ensureAttributesColumnExecutesBothReplaySafeStatementsOnEveryBootstrap() throws Exception {
        var executed = new ArrayList<String>();
        var statement = recordingStatement(executed);
        var manager = new PostgresSchemaManager(new UnusedDataSource(), CONFIG);
        var ensure = PostgresSchemaManager.class.getDeclaredMethod(
            "ensureAttributesColumn", Statement.class);
        ensure.setAccessible(true);

        ensure.invoke(manager, statement);
        ensure.invoke(manager, statement);

        assertThat(executed).hasSize(4);
        assertThat(executed).allSatisfy(sql -> assertThat(sql)
            .contains("ALTER TABLE IF EXISTS")
            .contains("ADD COLUMN IF NOT EXISTS attributes TEXT NOT NULL DEFAULT '{}'"));
    }

    @Test
    void freshEntityAndRelationTablesDeclareTheAttributesColumn() throws Exception {
        var manager = new PostgresSchemaManager(new UnusedDataSource(), CONFIG);
        var versionThree = PostgresSchemaManager.class.getDeclaredMethod("versionThreeStatements");
        versionThree.setAccessible(true);

        @SuppressWarnings("unchecked")
        var statements = (List<String>) versionThree.invoke(manager);

        var entitiesDdl = statements.stream()
            .filter(sql -> sql.contains("\"lr_entities\""))
            .findFirst()
            .orElseThrow();
        var relationsDdl = statements.stream()
            .filter(sql -> sql.contains("\"lr_relations\""))
            .findFirst()
            .orElseThrow();
        assertThat(entitiesDdl).contains("attributes TEXT NOT NULL DEFAULT '{}'");
        assertThat(relationsDdl).contains("attributes TEXT NOT NULL DEFAULT '{}'");
    }

    private static Statement recordingStatement(List<String> executed) {
        return (Statement) Proxy.newProxyInstance(
            PostgresSchemaManagerAttributesDdlTest.class.getClassLoader(),
            new Class<?>[] {Statement.class},
            (proxy, method, arguments) -> {
                if ("execute".equals(method.getName()) && arguments != null) {
                    executed.add((String) arguments[0]);
                    return true;
                }
                return null;
            }
        );
    }

    /** Only the identity of this source matters: the DDL under test never opens a connection. */
    private static final class UnusedDataSource implements DataSource {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("not used");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
