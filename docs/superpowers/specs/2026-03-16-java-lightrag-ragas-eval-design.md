# Java LightRAG RAGAS Evaluation Design

## Overview

This document defines a practical way to run the upstream LightRAG RAGAS evaluation flow against `lightrag-java`.

The upstream evaluation assumes a running LightRAG HTTP server. The Java SDK repository currently has no server module, so the closest aligned approach is:

- keep the upstream-style sample dataset and sample documents
- keep the RAGAS metric pipeline
- replace the server `/query` call with a local Java SDK query runner

## Goals

- Reuse upstream evaluation concepts and metrics.
- Make evaluation runnable directly from the Java SDK repository.
- Avoid adding a full HTTP server just for evaluation.
- Keep setup simple enough that a user can fill environment variables and run it.

## Non-Goals

- Do not add a production API server in this phase.
- Do not reimplement RAGAS metrics in Java.
- Do not add benchmark-specific retrieval metrics such as `Recall@k` in this phase.

## Architecture

### Java side

- Add `RagasEvaluationService` to:
  - load markdown files from a directory
  - ingest them into an in-memory `LightRag`
  - run one query
  - return `answer + contexts`

- Add `RagasEvaluationCli` to:
  - build OpenAI-compatible chat and embedding adapters from environment variables
  - invoke the service
  - print JSON to stdout

- Add a Gradle `runRagasQuery` task for the CLI.

### Python side

- Add `evaluation/ragas/eval_rag_quality_java.py`
- Reuse the upstream RAGAS metric approach:
  - `Faithfulness`
  - `Answer Relevance`
  - `Context Recall`
  - `Context Precision`

- Replace upstream HTTP generation with a subprocess call to the Java CLI.

## Environment Model

The evaluation needs two model groups:

- Java SDK query execution models
- RAGAS evaluator models

The simplest supported setup is one `OPENAI_API_KEY` with default OpenAI-compatible URLs and default models.

## Testing Strategy

- Add a focused Java test for `RagasEvaluationService`
- Verify the Python script parses
- Verify Gradle still builds and tests the Java side

## User Experience

The repository should include:

- sample dataset
- sample documents
- `.env.example`
- `requirements.txt`
- `README.md` for evaluation usage
