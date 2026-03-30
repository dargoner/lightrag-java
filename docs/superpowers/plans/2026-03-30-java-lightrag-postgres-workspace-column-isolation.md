# PostgreSQL Workspace Column Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 PostgreSQL workspace 隔离从“按表前缀分表”改成“共享表 + `workspace_id` 列隔离”，并保持 `LightRag` 显式 `workspaceId` 运行时 API 不变。

**Architecture:** `WorkspaceStorageProvider` 继续按 `workspaceId` 返回 scoped provider，但 PostgreSQL scoped provider 不再派生 workspace-specific table prefix，而是在各个 store 内自动带入固定的 `workspaceId`。Schema 统一为单套共享表，`restore` 与查询/写入全都以 `workspace_id` 为边界。

**Tech Stack:** Java 17, Gradle, JUnit 5, Testcontainers PostgreSQL, pgvector

---

### Task 1: 先定义共享表 schema 的预期行为

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: 写共享表 schema 的 failing test**

```java
@Test
@DisplayName("bootstraps shared workspace tables with workspace_id keys")
void bootstrapsSharedWorkspaceTablesWithWorkspaceColumns() throws SQLException {
    PostgreSQLContainer<?> container = newPostgresContainer();
    container.start();

    PostgresStorageConfig config = new PostgresStorageConfig(
        container.getJdbcUrl(),
        container.getUsername(),
        container.getPassword(),
        "lightrag",
        3,
        "rag_"
    );

    try (container; PostgresStorageProvider ignored = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
        try (var connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password())) {
            assertThat(existingTables(connection, config.schema())).containsExactlyInAnyOrder(
                "rag_documents",
                "rag_chunks",
                "rag_document_status",
                "rag_entities",
                "rag_entity_aliases",
                "rag_entity_chunks",
                "rag_relations",
                "rag_relation_chunks",
                "rag_schema_version",
                "rag_vectors"
            );
            assertThat(columnNames(connection, config, "documents")).contains("workspace_id");
            assertThat(primaryKeyColumns(connection, config, "documents")).containsExactly("workspace_id", "id");
            assertThat(primaryKeyColumns(connection, config, "vectors"))
                .containsExactly("workspace_id", "namespace", "vector_id");
        }
    }
}
```

- [ ] **Step 2: 运行单测确认先红**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.bootstrapsSharedWorkspaceTablesWithWorkspaceColumns"`

Expected: FAIL，提示缺少 `workspace_id` 列或主键仍是旧结构。

- [ ] **Step 3: 实现最小 schema 变更**

```java
CREATE TABLE IF NOT EXISTS %s (
    workspace_id TEXT NOT NULL,
    id TEXT NOT NULL,
    ...
    PRIMARY KEY (workspace_id, id)
)
```

- [ ] **Step 4: 重新运行单测确认转绿**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.bootstrapsSharedWorkspaceTablesWithWorkspaceColumns"`

Expected: PASS

### Task 2: 定义双 workspace 数据隔离行为

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: 写双 workspace 读写隔离 failing test**

```java
@Test
void isolatesAllStoresByWorkspaceIdInsideSharedTables() {
    // alpha 和 beta 使用同一套表
    // 各自写 document/chunk/entity/relation/vector/status
    // 各自读取只能看到本 workspace 的数据
}
```

- [ ] **Step 2: 跑测试确认先红**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.isolatesAllStoresByWorkspaceIdInsideSharedTables"`

Expected: FAIL，提示读写串 workspace 或 provider 仍依赖独立表。

- [ ] **Step 3: 给 provider/store 注入固定 workspaceId，并在 SQL 中带入条件**

```java
final class PostgresDocumentStore implements DocumentStore {
    private final String workspaceId;
}
```

- [ ] **Step 4: 重新跑测试确认转绿**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.isolatesAllStoresByWorkspaceIdInsideSharedTables"`

Expected: PASS

### Task 3: 收敛 restore 和锁语义到 workspace 边界

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/postgres/PostgresAdvisoryLockManager.java`

- [ ] **Step 1: 写 restore 只影响当前 workspace 的 failing test**

```java
@Test
void restoreOnlyReplacesCurrentWorkspaceRows() {
    // alpha restore 后 beta 数据仍存在
}
```

- [ ] **Step 2: 跑测试确认先红**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.restoreOnlyReplacesCurrentWorkspaceRows"`

Expected: FAIL，提示 restore 清掉了其他 workspace 或无法按 workspace 删除。

- [ ] **Step 3: 把 restore 从 TRUNCATE 改为按 workspace 删除，并把锁 key 绑定 workspaceId**

```java
DELETE FROM %s WHERE workspace_id = ?
```

- [ ] **Step 4: 重新跑测试确认转绿**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.restoreOnlyReplacesCurrentWorkspaceRows"`

Expected: PASS

### Task 4: 收口 Spring workspace provider 的装配方式

**Files:**
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/SpringWorkspaceStorageProvider.java`

- [ ] **Step 1: 写 starter 装配 failing test**

```java
@Test
void postgresWorkspaceProviderUsesSharedTableConfigForDifferentWorkspaces() {
    // alpha/beta 解析出的 provider 不同，但底层 postgres config 不再派生 workspace-specific table prefix
}
```

- [ ] **Step 2: 跑测试确认先红**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.postgresWorkspaceProviderUsesSharedTableConfigForDifferentWorkspaces"`

Expected: FAIL，提示仍然生成不同 table prefix。

- [ ] **Step 3: 移除 workspace-specific table prefix 派生**

```java
private PostgresStorageConfig postgresConfig() {
    return new PostgresStorageConfig(..., configuredBasePrefix);
}
```

- [ ] **Step 4: 重新跑测试确认转绿**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.postgresWorkspaceProviderUsesSharedTableConfigForDifferentWorkspaces"`

Expected: PASS

### Task 5: 跑回归测试并收口文档说明

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README 中 PostgreSQL workspace 隔离说明**

```md
- PostgreSQL uses one shared table set per configured prefix
- rows are isolated by workspace_id
```

- [ ] **Step 2: 跑核心回归**

Run: `./gradlew :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightragjava.api.LightRagWorkspaceTest"`

Expected: PASS

- [ ] **Step 3: 跑 starter 回归**

Run: `./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest"`

Expected: PASS
