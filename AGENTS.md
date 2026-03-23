# Repository Guidelines

## Project Structure & Module Organization

This repository is a Gradle multi-module Java 17 project.

- `lightrag-core`: framework-neutral SDK, storage adapters, query/indexing pipeline, and most tests under `src/test/java`.
- `lightrag-spring-boot-starter`: Spring Boot auto-configuration for `LightRag`.
- `lightrag-spring-boot-demo`: minimal REST demo app with `application.yml`.
- `evaluation/ragas`: Python-based evaluation scripts, sample datasets, and documents.
- `docs/superpowers`: design specs and implementation plans; use it for design-heavy changes, not runtime code.

Keep production code in `src/main/java` and mirror package structure in `src/test/java`.

## Build, Test, and Development Commands

- `./gradlew build`: compile all modules and run the full test suite.
- `./gradlew test`: run all JVM tests without packaging.
- `./gradlew :lightrag-core:test`: run core SDK tests only.
- `./gradlew :lightrag-spring-boot-demo:bootRun`: start the demo app locally.
- `./gradlew :lightrag-core:runRagasBatchEval`: run the Java-side RAGAS batch evaluation entry point.

Use module-scoped commands while iterating to keep feedback fast.

## Coding Style & Naming Conventions

Follow the existing Java style in the repository:

- 4-space indentation, no tabs.
- `UpperCamelCase` for classes, `lowerCamelCase` for methods/fields, `UPPER_SNAKE_CASE` for constants.
- Keep packages under `io.github.lightragjava.<area>`.
- Prefer small, focused classes and fluent builder usage consistent with `LightRagBuilder`.

No dedicated formatter or linter is configured in Gradle today, so match surrounding code closely and keep imports/order clean.

## Testing Guidelines

Tests use JUnit 5, AssertJ, Spring Boot Test, and Testcontainers.

- Name test classes `*Test`.
- Use descriptive test methods such as `returnsStreamingAnswerAndRetrievalMetadataWhenStreamIsEnabled`.
- Add or update tests for every behavior change in the affected module.

For storage integration tests, expect Docker-backed Testcontainers when PostgreSQL or Neo4j is involved.

## Commit & Pull Request Guidelines

Recent history follows Conventional Commit prefixes like `feat:`, `fix:`, and `docs:`. Continue that pattern and keep subjects imperative and concise.

Pull requests should include:

- a short summary of the change,
- the affected modules,
- test commands you ran,
- any required environment variables or external services,
- screenshots or sample requests only when changing the demo app or docs-facing behavior.
