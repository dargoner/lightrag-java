# Java Query Accuracy For Overview And Detail Questions

## Summary

This spec adds a minimal query-time accuracy improvement layer for `lightrag-java`.
The goal is to improve answer accuracy for two common question shapes without
introducing a new planner, new storage, or new graph layers.

Target question shapes:

- overview questions: "有哪些", "支持哪些", "分哪几类", "核心模块是什么"
- detail questions: "具体怎么做", "某个关系是否成立", "某个细节在哪里提到"

The chosen approach is:

1. classify queries into lightweight intents
2. estimate evidence strength from the retrieved context
3. inject different answer instructions for overview and detail questions

For overview questions, the system should produce:

- a short summary
- 2-5 grouped categories
- a forward-looking expansion guide

For detail questions, the system should prefer abstaining over guessing when
evidence is weak.

## Goals

- Improve accuracy for overview questions by making answers more structured and
  less likely to overclaim.
- Improve accuracy for detail questions by making unsupported answers more
  conservative.
- Keep the change small enough to fit the current query pipeline.
- Avoid public API churn for this first iteration.

## Non-Goals

- No planner or solver framework.
- No `summary/topic graph`.
- No new storage schema or task model.
- No graph materialization changes.
- No broad query architecture rewrite.
- No guarantee of full coverage for overview questions.

## Current Context

The repository already has:

- lightweight query intents: `FACT`, `RELATION`, `MULTI_HOP`
- rule-based intent classification
- multiple retrieval modes and multi-hop query support
- prompt shaping for multi-hop path-aware answers

Relevant files:

- `lightrag-core/src/main/java/io/github/lightrag/query/QueryIntent.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/RuleBasedQueryIntentClassifier.java`
- `lightrag-core/src/main/java/io/github/lightrag/query/QueryEngine.java`
- `lightrag-core/src/main/java/io/github/lightrag/synthesis/PathAwareAnswerSynthesizer.java`

The current weakness is that overview and detail questions still share nearly
the same answer contract. This causes two user-facing issues:

- overview answers can sound overly complete even when evidence is narrow
- detail answers can sound too certain when retrieval evidence is weak

## Options Considered

### Option A: Prompt-only patch

Add a few extra instructions for broad questions and keep everything else the
same.

Pros:

- smallest code change

Cons:

- too dependent on model behavior
- no internal evidence gate
- weak testability

### Option B: Minimal intent + evidence gate + prompt shaping

Add a lightweight `OVERVIEW` / `DETAIL` split, compute evidence strength from
the retrieved context, then inject different prompt constraints.

Pros:

- small implementation
- easy to test
- fits current architecture
- directly improves both overview and detail accuracy

Cons:

- no true reasoning planner
- only coarse evidence assessment

### Option C: Full planner / topic-layer design

Introduce planning operators, topic summaries, and a dedicated overview query
path.

Pros:

- highest long-term ceiling

Cons:

- clearly over-designed for the current goal
- larger surface area and regression risk

### Decision

Choose **Option B**.

It is the smallest change that can materially improve answer accuracy without
dragging the system into a new architecture.

## Design

### 1. Query Intent Model

Replace the current generic factual intent with a clearer split:

- `OVERVIEW`
- `DETAIL`
- `RELATION`
- `MULTI_HOP`

Intent rules stay rule-based in this iteration.

Classification order:

1. `MULTI_HOP`
2. `RELATION`
3. `OVERVIEW`
4. default to `DETAIL`

Rationale:

- `MULTI_HOP` and `RELATION` should keep their current precedence
- overview detection must not swallow relation or path questions
- defaulting to `DETAIL` keeps the fallback conservative

### 2. Overview Detection

`RuleBasedQueryIntentClassifier` will detect overview questions using a small
keyword set.

Chinese patterns:

- "有哪些"
- "哪几类"
- "分为哪些"
- "支持哪些"
- "主要模块"
- "核心能力"
- "总体上"

English patterns:

- `what types`
- `what categories`
- `what modules`
- `what capabilities`
- `supported`

The rule set must stay deliberately small. This iteration is not trying to
solve every linguistic variant.

### 3. Evidence Coverage Advisor

Add a new internal helper:

- `io.github.lightrag.query.EvidenceCoverageAdvisor`

Purpose:

- inspect the final retrieved `QueryContext`
- produce a simple confidence signal for prompt shaping

Proposed output:

```java
public record EvidenceCoverage(
    ConfidenceLevel level,
    boolean overviewGuidanceEnabled
) {}

public enum ConfidenceLevel {
    HIGH_CONFIDENCE,
    PARTIAL_COVERAGE,
    INSUFFICIENT_EVIDENCE
}
```

This helper remains internal and stateless.

Initial rules should stay simple:

- overview:
  - very few matched chunks or very few distinct sources -> `PARTIAL_COVERAGE`
  - otherwise -> `HIGH_CONFIDENCE`
- detail:
  - little to no grounded chunk/entity/relation support -> `INSUFFICIENT_EVIDENCE`
  - otherwise -> `HIGH_CONFIDENCE`
- relation:
  - no usable relation/entity grounding -> `INSUFFICIENT_EVIDENCE`
- multi-hop:
  - continue to rely on existing path-aware conservatism

This signal is advisory, not authoritative. It influences answer wording, not
retrieval flow control.

### 4. QueryEngine Prompt Shaping

`QueryEngine` will use both `QueryIntent` and `EvidenceCoverage`.

For `OVERVIEW` questions, the prompt must instruct the model to:

- start with a short high-level summary
- organize the answer into 2-5 grouped categories
- only include categories grounded in the provided context
- end with a natural expansion guide such as:
  - can continue by module
  - can continue by workflow
  - can continue by implementation detail
- avoid absolute wording like:
  - 全部
  - 完整
  - 所有
  - fully covered
  - exhaustive

For `DETAIL` questions, the prompt must instruct the model to:

- state a direct answer only when supported by the context
- explicitly refuse to guess when evidence is insufficient
- avoid turning weak associations into hard facts

For this iteration:

- do not change retrieval modes
- do not add a new `OverviewQueryStrategy`
- do not modify `QueryResult`

### 5. Output Behavior

Expected overview answer shape:

1. one-sentence summary
2. grouped bullets or short sections
3. one-sentence expansion guide

Expected detail answer shape:

- direct answer when grounded
- explicit uncertainty when not grounded

The system should not output the literal wording:

- "基于当前命中结果"
- "可能未覆盖全部"

Instead, weak overview evidence should surface as softer phrasing and a
follow-up guide, not as a blunt disclaimer.

## Data Flow

### Overview Question

1. classify as `OVERVIEW`
2. retrieve with existing strategy, defaulting to current `MIX` behavior
3. compute evidence coverage
4. inject overview-specific instructions
5. generate summary + category grouping + expansion guide

### Detail Question

1. classify as `DETAIL`
2. retrieve with existing mode
3. compute evidence coverage
4. inject detail-specific accuracy instructions
5. generate either:
   - grounded direct answer
   - explicit abstention

## Failure Handling

### Intent misclassification

Fallback behavior:

- default to `DETAIL`
- never fail the query because intent classification is imperfect

### Weak evidence heuristics

If the advisor is imperfect, the impact should be limited to answer wording.
It must not break retrieval or streaming behavior.

### Overview under-coverage

The answer should still provide useful summary structure, but wording must stay
non-absolute and end with an invitation to drill down further.

### Detail under-coverage

The answer must explicitly avoid guessing. This is the most important safety
constraint in the design.

## Testing

### Unit Tests

Add tests for:

- `RuleBasedQueryIntentClassifierTest.classifiesOverviewQueries`
- `RuleBasedQueryIntentClassifierTest.classifiesDetailQueries`
- `EvidenceCoverageAdvisorTest.detectsPartialCoverageForOverview`
- `EvidenceCoverageAdvisorTest.detectsInsufficientEvidenceForDetail`

### Query Behavior Tests

Add or extend `QueryEngine` tests to assert:

- overview answers contain summary-style structure
- overview answers contain an expansion guide
- overview answers do not contain the banned disclaimer phrases
- detail answers abstain when evidence is weak

### Regression Scope

Verify that:

- relation queries still work
- multi-hop path-aware answers still work
- standard detail answers remain unchanged when evidence is strong

## Acceptance Criteria

This iteration is successful when all of the following are true:

- overview questions read like summary + grouping + guidance rather than raw
  chunk stitching
- overview answers avoid absolute completeness language
- detail questions abstain instead of guessing when evidence is weak
- no public SDK API changes are required
- existing relation and multi-hop behavior remains intact

## Future Work

If this iteration proves valuable, the next candidates are:

- lightweight overview-specific retrieval tuning
- structured coverage metadata in `QueryResult`
- summary/topic graph as a separate phase
- broader domain-aware intent classification

These are intentionally deferred to keep the current change minimal.
