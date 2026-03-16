# Java LightRAG PG Neo4j RAGAS Evaluation Design

## Overview

This document defines the next evaluation step for `lightrag-java`: run the same upstream-style RAGAS evaluation against a PostgreSQL + Neo4j backed deployment profile instead of the current in-memory profile.

The current RAGAS harness already works for the Java SDK by:

- loading the upstream sample dataset and sample markdown documents
- using a Java CLI runner to return `answer + contexts`
- using the upstream-style Python RAGAS scoring pipeline

The remaining gap is that evaluation only exercises `InMemoryStorageProvider`, which is useful for algorithm-quality checks but does not reflect the production-oriented `PostgresNeo4jStorageProvider`.

## Goals

- Reuse the exact same sample dataset, sample documents, and RAGAS scoring script.
- Add a `postgres-neo4j-testcontainers` evaluation profile.
- Start one PostgreSQL container and one Neo4j container for the whole evaluation run.
- Ingest the sample documents once, then evaluate the entire dataset against that persistent provider instance.
- Keep in-memory evaluation intact as the default or alternate option.

## Non-Goals

- Do not introduce a permanent HTTP server.
- Do not require pre-provisioned PostgreSQL or Neo4j instances.
- Do not change the RAGAS metric set or sample dataset.
- Do not optimize runtime beyond the minimum needed to avoid per-query container startup.

## Design Options

### Option 1: Start Testcontainers once per query

Pros:

- simplest implementation shape

Cons:

- far too slow
- repeated ingest per question skews comparisons
- not representative of a long-lived production deployment

### Option 2: Start Testcontainers once per evaluation run

Pros:

- closest to production-like provider lifetime
- one ingest phase, many queries
- easy to compare with the current in-memory runner

Cons:

- needs a batch-oriented Java evaluation runner instead of the current single-query CLI

### Option 3: Add a long-lived Java evaluation server

Pros:

- most flexible for future benchmarks

Cons:

- much larger scope
- unnecessary for the current RAGAS workflow

## Recommendation

Adopt Option 2.

Add a batch evaluation runner on the Java side that:

- accepts `storage-profile`
- loads documents once
- creates a single `LightRag` instance
- runs all dataset questions sequentially
- returns `answer + contexts` for each case as JSON

Supported profiles:

- `in-memory`
- `postgres-neo4j-testcontainers`

For `postgres-neo4j-testcontainers`:

- start one `PostgreSQLContainer` using the same `pgvector/pgvector:pg16` image already used in tests
- start one `Neo4jContainer` using the same image family already used in tests
- build a `PostgresNeo4jStorageProvider`
- ingest sample docs once
- query all cases
- close provider and containers once at the end

## Python Evaluation Flow

The existing Python script should change from:

- one Java CLI subprocess per question

to:

- one Java batch runner subprocess per full evaluation run

This keeps the upstream RAGAS metric flow unchanged while making the provider lifecycle production-like.

## Testing Strategy

Required coverage:

- batch service loads docs once and returns all case results
- in-memory profile remains supported
- `postgres-neo4j-testcontainers` profile can be created and queried for at least one focused test
- Python script can target the new profile via CLI argument or env var

Verification should rely on focused Java tests plus one live evaluation run when Docker and model endpoints are available.
