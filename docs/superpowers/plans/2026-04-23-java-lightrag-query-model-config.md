# Query Model Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add property-driven Spring Boot support for a dedicated `queryModel` via `lightrag.query-model.*` while preserving existing fallback and custom-bean behavior.

**Architecture:** Extend starter configuration with a `queryModel` `ModelProperties` block, auto-create a named `queryModel` bean when `lightrag.query-model.base-url` is configured, and keep `LightRag` wiring unchanged so it consumes the resolved `queryModel` bean. Tests must cover property binding, auto-configured query model selection, and fallback to the default `chatModel`.

**Tech Stack:** Java 17, Spring Boot auto-configuration, JUnit 5, Gradle

---

### Task 1: Lock the new query-model contract with failing starter tests

**Files:**
- Modify: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: Add a property binding assertion for `lightrag.query-model.*`**

```java
assertThat(properties.getQueryModel().getBaseUrl()).isEqualTo("http://localhost:11435/v1/");
assertThat(properties.getQueryModel().getModel()).isEqualTo("qwen-query");
assertThat(properties.getQueryModel().getApiKey()).isEqualTo("query-key");
assertThat(properties.getQueryModel().getTimeout()).isEqualTo(Duration.ofSeconds(21));
```

- [ ] **Step 2: Add a focused auto-configuration test for property-backed query model selection**

```java
contextRunner
    .withPropertyValues(
        "lightrag.query-model.base-url=http://localhost:11435/v1/",
        "lightrag.query-model.model=qwen-query",
        "lightrag.query-model.api-key=query-key",
        "lightrag.query-model.timeout=PT21S"
    )
    .run(context -> {
        var lightRag = context.getBean(LightRag.class);
        var config = (io.github.lightrag.config.LightRagConfig) extractField(lightRag, "config");
        assertThat(config.queryModel()).isSameAs(context.getBean("queryModel", ChatModel.class));
        assertThat(config.queryModel()).isNotSameAs(context.getBean("chatModel", ChatModel.class));
    });
```

- [ ] **Step 3: Run the starter test class to verify RED**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest"`

Expected: FAIL because `LightRagProperties` does not yet expose `queryModel` and the starter does not auto-create a `queryModel` bean from properties.

### Task 2: Add `query-model` property binding

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: Add the new `queryModel` properties block**

```java
private final ModelProperties queryModel = new ModelProperties();

public ModelProperties getQueryModel() {
    return queryModel;
}
```

- [ ] **Step 2: Keep naming aligned with Spring Boot kebab-case binding**

```java
properties.getQueryModel().getBaseUrl()
```

This must bind from:

```properties
lightrag.query-model.base-url=http://localhost:11435/v1/
```

- [ ] **Step 3: Re-run the same starter test class**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest"`

Expected: still FAIL, but now because the starter still lacks the property-driven `queryModel` bean.

### Task 3: Auto-configure the property-backed `queryModel` bean

**Files:**
- Modify: `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagAutoConfiguration.java`
- Test: `lightrag-spring-boot-starter/src/test/java/io/github/lightrag/spring/boot/LightRagAutoConfigurationTest.java`

- [ ] **Step 1: Add a named `queryModel` bean factory**

```java
@Bean("queryModel")
@ConditionalOnProperty(prefix = "lightrag.query-model", name = "base-url")
@ConditionalOnMissingBean(name = "queryModel")
ChatModel queryModel(LightRagProperties properties) {
    var queryModel = properties.getQueryModel();
    return new OpenAiCompatibleChatModel(
        requireValue(queryModel.getBaseUrl(), "lightrag.query-model.base-url"),
        requireValue(queryModel.getModel(), "lightrag.query-model.model"),
        requireValue(queryModel.getApiKey(), "lightrag.query-model.api-key"),
        queryModel.getTimeout()
    );
}
```

- [ ] **Step 2: Preserve existing `lightRag(...)` wiring**

```java
var configuredQueryModel = queryModelProvider.getIfAvailable();
if (configuredQueryModel != null) {
    builder.queryModel(configuredQueryModel);
}
```

This should remain intact so the new property-driven bean enters through the same path as a user-defined `queryModel` bean.

- [ ] **Step 3: Keep explicit bean priority unchanged**

```java
@ConditionalOnMissingBean(name = "queryModel")
```

This ensures a user-declared `@Bean("queryModel")` still wins over the property-driven default.

- [ ] **Step 4: Re-run the starter test class to verify GREEN**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest"`

Expected: PASS with property binding, property-driven query model creation, and fallback behavior all green.

### Task 4: Final verification and scope check

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-java-lightrag-query-model-config.md`

- [ ] **Step 1: Run focused verification again**

Run: `GRADLE_USER_HOME=/tmp/gradle-user-home ./gradlew :lightrag-spring-boot-starter:test --tests "io.github.lightrag.spring.boot.LightRagAutoConfigurationTest"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm no accidental `extraction-model` or `summary-model` property work slipped in**

Run: `rg -n "query-model|extraction-model|summary-model" lightrag-spring-boot-starter/src/main/java lightrag-spring-boot-starter/src/test/java`

Expected:
- `query-model` appears in starter properties/tests
- `extraction-model` does not appear as a new property binding
- `summary-model` does not appear as a new property binding

- [ ] **Step 3: Record implementation notes**

```markdown
- `lightrag.query-model.*` now creates a named `queryModel` bean
- custom `@Bean("queryModel")` still overrides starter auto-configuration
- no changes were made to extraction or summary model property binding
```
