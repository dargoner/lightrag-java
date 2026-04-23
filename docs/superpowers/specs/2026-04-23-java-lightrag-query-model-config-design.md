# Java LightRAG Query Model Config Design

## Goal

Add a Spring Boot configuration-based way to provide a dedicated query model for query answering and automatic keyword extraction.

This change should let users configure:

- `lightrag.query-model.base-url`
- `lightrag.query-model.model`
- `lightrag.query-model.api-key`
- `lightrag.query-model.timeout`

without requiring them to manually declare a `@Bean("queryModel")`.

## Scope

### In Scope

- add a new `query-model` configuration section in starter properties
- auto-create a `queryModel` bean from that configuration when present
- keep existing `queryModel` bean override behavior intact
- ensure query execution and automatic keyword extraction use the configured query model
- add/update starter tests for binding and auto-configuration fallback behavior

### Out of Scope

- adding config-based `extraction-model` or `summary-model`
- changing core `LightRagConfig` model fallback semantics
- changing embedding model configuration
- changing non-Spring Boot builder APIs

## Current State

The Java SDK core already supports model separation:

- `chatModel`
- `queryModel`
- `extractionModel`
- `summaryModel`

The Spring Boot starter also already supports named bean injection for:

- `chatModel`
- `queryModel`
- `extractionModel`
- `summaryModel`

However, only `chat` and `embedding` have property-driven auto-created model beans. Query model separation currently requires users to write a custom Spring bean named `queryModel`.

## Desired Outcome

After this change:

- users can configure a dedicated query model using `lightrag.query-model.*`
- the starter auto-registers a `queryModel` bean when that config is present
- `LightRag` uses that bean for query answering and keyword extraction
- if no query-model config exists, behavior still falls back to `chatModel`
- if the user explicitly provides a `queryModel` bean, that explicit bean still wins

## Recommended Approach

Use a narrow starter-only enhancement:

1. Add `queryModel` properties beside existing `chat` and `embedding` properties.
2. Add an auto-configured `@Bean("queryModel")` guarded by presence of `lightrag.query-model.base-url`.
3. Keep the existing `LightRagAutoConfiguration.lightRag(...)` wiring unchanged except for consuming the now-property-backed `queryModel` bean when available.

This keeps the change focused, preserves existing extension points, and avoids introducing new core abstractions.

## Architecture Impact

### Properties Layer

Add a new field to `LightRagProperties`:

- `private final ModelProperties queryModel = new ModelProperties();`

with getter:

- `getQueryModel()`

The naming should bind naturally from Spring Boot kebab-case:

- `queryModel` field ↔ `lightrag.query-model.*`

### Auto-Configuration Layer

Add a dedicated bean factory:

- bean name: `queryModel`
- type: `ChatModel`
- implementation: `OpenAiCompatibleChatModel`

Activation rule:

- create only when `lightrag.query-model.base-url` is set
- back off if a bean named `queryModel` already exists

This preserves current priority order:

1. explicit user-defined `queryModel` bean
2. auto-configured `queryModel` bean from `lightrag.query-model.*`
3. fallback to default `chatModel`

### Runtime Behavior

No core runtime behavior change is needed.

`LightRagConfig.queryModel()` already falls back to `chatModel`, and `LightRag` already routes query answering through `config.queryModel()`. Automatic keyword extraction also runs inside the query flow, so it follows the same model selection.

## Behavior Rules

1. If `lightrag.query-model.base-url` is absent, the starter must not auto-create a `queryModel` bean.
2. If `lightrag.query-model.*` is configured, the starter must auto-create a `queryModel` bean.
3. If the application already provides its own `queryModel` bean, starter auto-configuration must back off.
4. Query answering and automatic keyword extraction must use the resolved `queryModel`.
5. Default chat behavior must remain unchanged when the new config is not used.

## Risks

### Risk 1: Ambiguous Bean Resolution

If the new auto-configured bean is not properly named or guarded, it could interfere with existing custom model wiring.

Mitigation:

- register it explicitly as `queryModel`
- use `@ConditionalOnMissingBean(name = "queryModel")`
- keep existing default chat model resolution logic unchanged

### Risk 2: Partial Configuration Produces Confusing Errors

If users set only one query-model property, bean creation may fail in a less obvious place.

Mitigation:

- require the same fields as `chatModel`
- use the existing `requireValue(...)` validation pattern
- gate bean creation on `base-url` presence so absence still falls back cleanly

### Risk 3: Hidden Regression in Query Fallback

A starter change could accidentally stop query flows from falling back to `chatModel`.

Mitigation:

- keep existing fallback tests
- add a focused property-driven query model test

## Testing Strategy

### Red Phase

Add/update starter tests that fail before implementation:

- properties binding test for `lightrag.query-model.timeout`
- auto-configuration test showing configured `query-model.*` changes `config.queryModel()`
- fallback test confirming no query-model config still uses default chat model

### Green Phase

Run focused starter tests first:

- `LightRagAutoConfigurationTest`

No broader module test expansion is needed unless the starter tests reveal shared wiring failures.

## Documentation

This change can be shipped without README updates if we want to keep scope tight.

If desired later, we can separately add a starter YAML example for `lightrag.query-model.*`, but that is not required for this implementation.

## Expected Deliverables

- starter property support for `lightrag.query-model.*`
- starter auto-configured `queryModel` bean
- tests covering binding, override, and fallback behavior
