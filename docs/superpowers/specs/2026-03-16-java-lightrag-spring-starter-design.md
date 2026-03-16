# Java LightRAG Spring Starter Design

## Overview

This document defines the next delivery-shape step for `lightrag-java`: add a Spring Boot starter and a minimal demo application so teams can integrate LightRAG in a conventional Spring stack without manually wiring every dependency.

The current repository is a single-module SDK. It already contains the core query, storage, and evaluation logic, but there is no Spring-native configuration layer, auto-configuration, or runnable web demo.

## Goals

- Add a Spring Boot starter module that auto-configures `LightRag`.
- Add a minimal demo Spring Boot application that exposes ingest and query endpoints.
- Support the most common deployment profiles first:
  - OpenAI-compatible chat model
  - OpenAI-compatible embedding model
  - `in-memory`
  - `postgres`
  - `postgres-neo4j`
- Keep the core SDK module reusable and framework-agnostic.

## Non-Goals

- Do not implement Maven Central publishing in this phase.
- Do not add every existing SDK capability as an HTTP endpoint in the demo.
- Do not introduce a full Spring abstraction over every SPI in the core SDK.
- Do not add evaluation endpoints to the demo.

## Architectural Options

### Option 1: Put Spring dependencies into the existing root module

Pros:

- simplest short-term file count

Cons:

- pollutes the core SDK with framework dependencies
- makes non-Spring consumers pay for Spring transitively

### Option 2: Add separate `starter` and `demo` modules

Pros:

- preserves clean boundaries
- keeps the core SDK framework-neutral
- aligns with normal Spring Boot integration expectations

Cons:

- requires converting the build to multi-module Gradle

### Option 3: Demo only, no starter

Pros:

- less initial work

Cons:

- weak integration story for actual users
- duplicated wiring in every consumer app

## Recommendation

Adopt Option 2.

Create three modules:

- `lightrag-core`
  - current SDK code
- `lightrag-spring-boot-starter`
  - configuration properties
  - auto-configuration
  - default bean wiring
- `lightrag-spring-boot-demo`
  - runnable example app
  - minimal REST endpoints

## Starter Scope

### Configuration Properties

Add properties for:

- chat model
  - base URL
  - model name
  - API key
- embedding model
  - base URL
  - model name
  - API key
- optional rerank model
  - deferred or omitted in first cut
- storage profile
  - `in-memory`
  - `postgres`
  - `postgres-neo4j`
- storage credentials
  - PostgreSQL JDBC URL, username, password, schema, vector dimensions, table prefix
  - Neo4j Bolt URL, username, password, database

### Auto-Configuration

The starter should auto-register:

- `ChatModel`
- `EmbeddingModel`
- optional `StorageProvider`
- `LightRag`

The simplest first cut can support OpenAI-compatible chat + embedding only. That is enough to make the starter useful and keeps the design honest.

## Demo Scope

The demo should expose:

- `POST /documents/ingest`
- `POST /query`

Request DTOs should be minimal wrappers around the existing SDK concepts, not a brand new API model.

The demo is primarily for:

- proving the starter wiring
- showing property configuration
- giving users a copyable sample project

## Build Layout

Convert the repository to a multi-module Gradle build:

- root: shared dependency and plugin management
- `lightrag-core`
- `lightrag-spring-boot-starter`
- `lightrag-spring-boot-demo`

The current root source set moves into `lightrag-core`.

## Testing Strategy

Required coverage:

- starter auto-configures `LightRag` for `in-memory`
- starter can build `postgres` and `postgres-neo4j` storage beans from properties
- demo application starts with Spring context
- demo ingest + query endpoints work in a focused integration test

## Delivery

This phase is successful when:

- a Spring Boot user can add the starter dependency
- configure properties in `application.yml`
- inject and use `LightRag`
- run the demo app locally as a reference integration
