<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **lightrag-java** (12817 symbols, 43017 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/lightrag-java/context` | Codebase overview, check index freshness |
| `gitnexus://repo/lightrag-java/clusters` | All functional areas |
| `gitnexus://repo/lightrag-java/processes` | All execution flows |
| `gitnexus://repo/lightrag-java/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |
| Work in the Indexing area (941 symbols) | `.claude/skills/generated/indexing/SKILL.md` |
| Work in the Postgres area (709 symbols) | `.claude/skills/generated/postgres/SKILL.md` |
| Work in the Query area (583 symbols) | `.claude/skills/generated/query/SKILL.md` |
| Work in the Api area (548 symbols) | `.claude/skills/generated/api/SKILL.md` |
| Work in the Storage area (271 symbols) | `.claude/skills/generated/storage/SKILL.md` |
| Work in the Boot area (225 symbols) | `.claude/skills/generated/boot/SKILL.md` |
| Work in the Mysql area (186 symbols) | `.claude/skills/generated/mysql/SKILL.md` |
| Work in the Neo4j area (139 symbols) | `.claude/skills/generated/neo4j/SKILL.md` |
| Work in the Milvus area (114 symbols) | `.claude/skills/generated/milvus/SKILL.md` |
| Work in the Demo area (108 symbols) | `.claude/skills/generated/demo/SKILL.md` |
| Work in the Task area (85 symbols) | `.claude/skills/generated/task/SKILL.md` |
| Work in the Ragas area (70 symbols) | `.claude/skills/generated/ragas/SKILL.md` |
| Work in the Evaluation area (69 symbols) | `.claude/skills/generated/evaluation/SKILL.md` |
| Work in the Refinement area (58 symbols) | `.claude/skills/generated/refinement/SKILL.md` |
| Work in the Openai area (42 symbols) | `.claude/skills/generated/openai/SKILL.md` |
| Work in the Memory area (34 symbols) | `.claude/skills/generated/memory/SKILL.md` |
| Work in the Lightrag area (19 symbols) | `.claude/skills/generated/lightrag/SKILL.md` |
| Work in the Persistence area (15 symbols) | `.claude/skills/generated/persistence/SKILL.md` |
| Work in the Slides area (13 symbols) | `.claude/skills/generated/slides/SKILL.md` |
| Work in the Synthesis area (4 symbols) | `.claude/skills/generated/synthesis/SKILL.md` |

<!-- gitnexus:end -->
