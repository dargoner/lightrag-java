# Java LightRAG Relational Default Table Prefix Design

## Goal

Align the default relational storage naming convention so that LightRAG-managed relational tables use the `lightrag_` prefix by default.

This change applies to:

- PostgreSQL default table prefix
- MySQL default table prefix
- relational initialization scripts / DDL paths that rely on the default prefix
- starter defaults, README examples, and tests that assert the default naming contract

This change does **not** apply to:

- Milvus collection naming
- Neo4j labels / relationship names
- explicit user-provided custom prefixes
- automatic migration from old default prefixes

## Scope

### In Scope

- Change default relational `tablePrefix` values to `lightrag_`
- Ensure relational initialization scripts and generated DDL use `lightrag_` when callers rely on defaults
- Update Spring Boot starter default property values
- Update README and README_zh relational examples
- Update tests that assert or depend on default relational prefixes

### Out of Scope

- Changing Milvus `collectionPrefix`
- Renaming already-created database tables
- Adding compatibility migration logic for historical default prefixes such as `rag_` or `km_rag_`
- Removing the ability to override table prefixes explicitly

## Current State

The repository currently has inconsistent default relational prefixes:

- PostgreSQL starter default uses `km_rag_`
- MySQL starter default uses `rag_`
- many tests and examples still assume `rag_`

This creates three problems:

1. Default behavior is inconsistent across relational backends.
2. Documentation and tests no longer reflect a single naming contract.
3. Initialization logic is harder to reason about because the expected default prefix depends on backend and code path.

## Desired Outcome

After this change:

- relational backends default to `lightrag_`
- initialization scripts and generated relational object names match that default
- docs and tests reflect the same default contract
- callers can still set a custom prefix explicitly, and explicit overrides continue to win

## Recommended Approach

Use a narrow default-alignment change:

1. Change only the relational default prefix values.
2. Keep all existing configuration knobs.
3. Update initialization paths, examples, and tests to match the new default.

This is the lowest-risk path because it preserves the current architecture while removing naming inconsistency.

## Architecture Impact

### Runtime Defaults

Update the default relational storage prefix values exposed by configuration objects:

- PostgreSQL default `tablePrefix` becomes `lightrag_`
- MySQL default `tablePrefix` becomes `lightrag_`

Milvus `collectionPrefix` remains unchanged.

### Relational Initialization / DDL

All relational initialization paths must remain prefix-driven rather than embedding historical defaults.

The implementation must verify that:

- table creation statements use the configured prefix
- index names use the configured prefix where applicable
- foreign key references point to prefixed table names consistently
- provider bootstrap logic and any SQL templates do not hard-code `rag_` or `km_rag_` as relational defaults

If a test or runtime path currently passes an explicit `rag_` prefix to verify override behavior, that path can remain unchanged. Only default-behavior expectations should move to `lightrag_`.

### Documentation

Update relational examples only:

- PostgreSQL `table-prefix` examples use `lightrag_`
- MySQL `table-prefix` examples use `lightrag_`

Do not change Milvus examples in this task.

### Tests

Tests should be split conceptually into two groups:

- default-value tests: update expected defaults to `lightrag_`
- explicit-override tests: keep custom values such as `rag_` when the test is validating configurability rather than defaults

This prevents losing coverage for custom-prefix support.

## Behavior Rules

1. When the user does not configure a relational `tablePrefix`, the system uses `lightrag_`.
2. When the user explicitly configures a relational `tablePrefix`, that explicit value is used unchanged.
3. Milvus collection naming behavior remains exactly as it is today.
4. Existing databases created with old default prefixes are not migrated automatically by this change.

## Risks

### Risk 1: Hidden Hard-Coded Prefixes

Some relational initialization paths may rely on hard-coded names rather than the prefix abstraction.

Mitigation:

- search for `rag_` and `km_rag_` across relational modules
- update only relational-default assumptions
- run relational provider/store tests after the change

### Risk 2: Accidental Milvus Drift

Because `rag_` also appears in Milvus defaults and tests, a broad search/replace could incorrectly rename collection defaults.

Mitigation:

- constrain code changes to relational defaults and relational docs/tests only
- leave Milvus configuration and tests untouched unless they are explicitly relational cross-store assertions

### Risk 3: Test Intent Confusion

Some tests may use `rag_` as a custom override, not as a default assertion.

Mitigation:

- inspect each failing test before changing it
- preserve explicit-override tests
- only update tests whose purpose is default contract validation

## Verification Strategy

### Red Phase

Add or update tests so they fail under the old defaults:

- starter property tests for PostgreSQL/MySQL default prefix expectations
- relational provider/store tests that verify default-initialized table names

### Green Phase

Run the smallest relevant set first:

- `LightRagAutoConfigurationTest`
- PostgreSQL storage/provider tests affected by default prefix assumptions
- MySQL storage/provider tests affected by default prefix assumptions

Then expand if needed to the broader relational suite if failures reveal shared initialization assumptions.

## Implementation Notes

- Preserve public configurability.
- Do not introduce migration code.
- Do not change Milvus collection naming.
- Prefer fixing prefix sources rather than patching downstream expectations in many places.

## Expected Deliverables

- updated relational default configuration values
- updated relational initialization / DDL usage where needed
- updated docs examples for relational backends
- updated tests reflecting the new default contract

