---
name: synthesis
description: "Skill for the Synthesis area of lightrag-java. 4 symbols across 2 files."
---

# Synthesis

4 symbols | 2 files | Cohesion: 73%

## When to Use

- Working with code in `lightrag-core/`
- Understanding how buildReasoningStagePrompt, buildFinalStagePrompt, appendBeforeContext work
- Modifying synthesis-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java` | buildReasoningStagePrompt, buildFinalStagePrompt, appendBeforeContext |
| `lightrag-core/src/test/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizerTest.java` | buildsReasoningAndFinalStagePrompts |

## Entry Points

Start here when exploring this area:

- **`buildReasoningStagePrompt`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java:62`
- **`buildFinalStagePrompt`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java:66`
- **`appendBeforeContext`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java:73`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `buildReasoningStagePrompt` | Method | `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java` | 62 |
| `buildFinalStagePrompt` | Method | `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java` | 66 |
| `appendBeforeContext` | Method | `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java` | 73 |
| `buildsReasoningAndFinalStagePrompts` | Method | `lightrag-core/src/test/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizerTest.java` | 79 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Query | 1 calls |

## How to Explore

1. `gitnexus_context({name: "buildReasoningStagePrompt"})` — see callers and callees
2. `gitnexus_query({query: "synthesis"})` — find related execution flows
3. Read key files listed above for implementation details
