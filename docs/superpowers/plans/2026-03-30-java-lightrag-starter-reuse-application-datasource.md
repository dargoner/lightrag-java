# Java LightRAG Starter Reuse Application DataSource Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Spring Boot starter 在 PostgreSQL 模式下优先复用主应用 `DataSource`，并在缺失时回退到现有独立 JDBC 配置。

**Architecture:** 扩展 `PostgresStorageProvider` 支持外部 `DataSource` 构造路径，并在 starter 的默认 `StorageProvider` 与 `WorkspaceStorageProvider` 装配中优先注入应用 `DataSource`。资源关闭按所有权区分，外部 `DataSource` 不由 LightRAG 关闭。

**Tech Stack:** Java 17, Spring Boot auto-configuration, JUnit 5, Gradle

---

### Task 1: 先定义 starter 复用应用 DataSource 的行为

**Files:**
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightragjava/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: 写应用 DataSource 优先复用的 failing test**

```java
@Test
void reusesApplicationDataSourceForPostgresStorageWhenAvailable() {
    // 提供一个 DataSource Bean
    // starter 选择 POSTGRES
    // 断言 LightRAG 的 postgres provider 使用的是这个 DataSource
}
```

- [ ] **Step 2: 跑测试确认先红**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.reusesApplicationDataSourceForPostgresStorageWhenAvailable"`

Expected: FAIL，提示 starter 仍然自己创建连接池或未使用应用 `DataSource`。

- [ ] **Step 3: 写 workspace provider 同样复用 DataSource 的 failing test**

```java
@Test
void reusesApplicationDataSourceForWorkspaceScopedPostgresProviders() {
    // alpha/beta workspace provider 都来自同一个应用 DataSource
}
```

- [ ] **Step 4: 跑测试确认先红**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.reusesApplicationDataSourceForWorkspaceScopedPostgresProviders"`

Expected: FAIL，提示 workspace provider 仍走独立 JDBC 配置。

### Task 2: 扩展 PostgresStorageProvider 支持外部 DataSource

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightragjava/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/postgres/PostgresStorageProvider.java`

- [ ] **Step 1: 写外部 DataSource 构造可用的 failing test**

```java
@Test
void supportsExternalDataSourceWithoutCreatingOwnedPools() {
    // 传入外部 DataSource
    // 基础读写成功
}
```

- [ ] **Step 2: 写 close 不关闭外部 DataSource 的 failing test**

```java
@Test
void doesNotCloseExternalDataSourceOnProviderClose() {
    // provider.close() 后，外部 DataSource 仍可获取连接
}
```

- [ ] **Step 3: 跑测试确认先红**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.supportsExternalDataSourceWithoutCreatingOwnedPools" --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.doesNotCloseExternalDataSourceOnProviderClose"`

Expected: FAIL，提示缺少 `DataSource` 构造路径或资源关闭边界不对。

- [ ] **Step 4: 实现最小构造扩展与资源所有权控制**

```java
public PostgresStorageProvider(
    DataSource dataSource,
    PostgresStorageConfig config,
    SnapshotStore snapshotStore,
    String workspaceId
) { ... }
```

- [ ] **Step 5: 跑测试确认转绿**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.supportsExternalDataSourceWithoutCreatingOwnedPools" --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest.doesNotCloseExternalDataSourceOnProviderClose"`

Expected: PASS

### Task 3: 改 starter 默认装配和 workspace 装配

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagAutoConfiguration.java`
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/SpringWorkspaceStorageProvider.java`

- [ ] **Step 1: 在自动配置里注入可选 DataSource 并优先复用**

```java
StorageProvider storageProvider(
    LightRagProperties properties,
    SnapshotStore snapshotStore,
    ObjectProvider<DataSource> dataSourceProvider
) { ... }
```

- [ ] **Step 2: 在 workspace provider 里传递可选 DataSource**

```java
return new SpringWorkspaceStorageProvider(storageProvider, dataSourceProvider, snapshotStore, properties);
```

- [ ] **Step 3: 让 POSTGRES / POSTGRES_NEO4J 两条路径都优先使用应用 DataSource**

```java
var dataSource = applicationDataSourceProvider.getIfAvailable();
```

- [ ] **Step 4: 跑 starter 定向测试确认转绿**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.reusesApplicationDataSourceForPostgresStorageWhenAvailable" --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest.reusesApplicationDataSourceForWorkspaceScopedPostgresProviders"`

Expected: PASS

### Task 4: 回归 PostgreSQL 与 mixed provider 行为

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/storage/neo4j/PostgresNeo4jStorageProvider.java`

- [ ] **Step 1: 若 mixed provider 缺少外部 DataSource 入口，补齐最小适配**

```java
new PostgresStorageProvider(applicationDataSource, postgresConfig, snapshotStore, workspaceId)
```

- [ ] **Step 2: 跑 core 回归**

Run: `./gradlew --console=plain --no-daemon :lightrag-core:test --tests "io.github.lightragjava.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProviderTest" --tests "io.github.lightragjava.api.LightRagWorkspaceTest"`

Expected: PASS

- [ ] **Step 3: 跑 starter 回归**

Run: `./gradlew --console=plain --no-daemon :lightrag-spring-boot-starter:test --tests "io.github.lightragjava.spring.boot.LightRagAutoConfigurationTest"`

Expected: PASS
