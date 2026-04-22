# Relational Default Table Prefix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align PostgreSQL and MySQL relational default table prefixes to `lightrag_`, while keeping Milvus collection naming unchanged.

**Architecture:** Keep the existing prefix-driven relational storage design. Update only the relational default prefix sources, then align tests and docs that assert default behavior. Preserve explicit custom-prefix override paths and avoid any compatibility migration logic.

**Tech Stack:** Java 17, Gradle multi-module build, Spring Boot starter, JUnit 5, Testcontainers

---

### Task 1: Lock the default-contract tests

**Files:**
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`

- [ ] **Step 1: Update starter default-prefix assertion to the target contract**

```java
assertThat(properties.getStorage().getPostgres().getTablePrefix()).isEqualTo("lightrag_");
assertThat(properties.getStorage().getMysql().getTablePrefix()).isEqualTo("lightrag_");
```

- [ ] **Step 2: Update PostgreSQL default bootstrap expectations**

```java
assertThat(existingTables(connection, config.schema())).containsExactlyInAnyOrder(
    "lightrag_documents",
    "lightrag_chunks",
    "lightrag_document_status",
    "lightrag_document_graph_snapshots",
    "lightrag_chunk_graph_snapshots",
    "lightrag_document_graph_journals",
    "lightrag_chunk_graph_journals",
    "lightrag_entities",
    "lightrag_entity_aliases",
    "lightrag_entity_chunks",
    "lightrag_relations",
    "lightrag_schema_version",
    "lightrag_task",
    "lightrag_task_document",
    "lightrag_task_stage",
    "lightrag_vectors"
);
```

- [ ] **Step 3: Run the narrow red-phase tests**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest" --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest"`

Expected: FAIL because runtime defaults still expose `km_rag_` / `rag_`.

### Task 2: Change relational default prefix sources

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/postgres/PostgresStorageProviderTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlStoresTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

- [ ] **Step 1: Update starter relational defaults**

```java
private String tablePrefix = "lightrag_";
```

- [ ] **Step 2: Update test helpers that represent default relational configs**

```java
return new PostgresStorageConfig(
    POSTGRES.getJdbcUrl(),
    POSTGRES.getUsername(),
    POSTGRES.getPassword(),
    schema,
    vectorDimensions,
    "lightrag_"
);
```

```java
return new MySqlStorageConfig(
    MYSQL.getJdbcUrl(),
    MYSQL.getUsername(),
    MYSQL.getPassword(),
    "lightrag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_"
);
```

- [ ] **Step 3: Keep explicit override tests unchanged**

```java
properties.getStorage().getPostgres().setTablePrefix("rag_");
properties.getStorage().getMysql().setTablePrefix("rag_");
properties.getStorage().getMilvus().setCollectionPrefix("rag_");
```

- [ ] **Step 4: Re-run the same targeted tests**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest" --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest"`

Expected: PASS for the updated default-contract checks.

### Task 3: Align relational docs and collateral

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: Update relational table-prefix examples only**

```yaml
table-prefix: lightrag_
```

- [ ] **Step 2: Keep Milvus collection-prefix examples unchanged**

```yaml
collection-prefix: rag_
```

- [ ] **Step 3: Review the diff to ensure only relational examples moved**

Run: `git diff -- README.md README_zh.md`

Expected: only relational `table-prefix` examples change to `lightrag_`.

### Task 4: Verify MySQL relational paths still honor defaults and overrides

**Files:**
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlStoresTest.java`
- Modify: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlStoresTest.java`
- Test: `lightrag-core/src/test/java/io/github/lightrag/storage/mysql/MySqlMilvusNeo4jStorageProviderTest.java`

- [ ] **Step 1: Keep long-prefix and explicit-override tests on custom values**

```java
"rag_" + "a".repeat(32) + "_"
```

- [ ] **Step 2: Move only default helper config builders to `lightrag_`**

```java
private static MySqlStorageConfig startedConfig(MySQLContainer<?> container) {
    container.start();
    return new MySqlStorageConfig(
        container.getJdbcUrl(),
        container.getUsername(),
        container.getPassword(),
        "lightrag_"
    );
}
```

- [ ] **Step 3: Run MySQL regression coverage**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-core:test --tests "io.github.lightrag.storage.mysql.MySqlStoresTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest"`

Expected: PASS with Milvus collection behavior unchanged.

### Task 5: Final verification

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-java-lightrag-relational-default-table-prefix.md`

- [ ] **Step 1: Re-run the full targeted verification set**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest" :lightrag-core:test --tests "io.github.lightrag.storage.postgres.PostgresStorageProviderTest" --tests "io.github.lightrag.storage.mysql.MySqlStoresTest" --tests "io.github.lightrag.storage.mysql.MySqlMilvusNeo4jStorageProviderTest"`

Expected: BUILD SUCCESSFUL with all selected tests green.

- [ ] **Step 2: Sanity-check scope boundaries**

Run: `rg -n 'km_rag_|table-prefix: .*rag_|collection-prefix: lightrag_' lightrag-spring-boot-starter lightrag-core README.md README_zh.md`

Expected:
- no relational starter default remains `km_rag_`
- no relational README example remains `table-prefix: rag_`
- no Milvus example is changed to `collection-prefix: lightrag_`

- [ ] **Step 3: Mark execution notes**

```markdown
- Verification command outputs captured in terminal session
- Scope preserved: relational defaults only
- No migration or Milvus default change introduced
```
