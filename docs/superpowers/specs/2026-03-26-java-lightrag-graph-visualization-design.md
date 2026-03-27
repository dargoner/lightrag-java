# Java LightRAG Graph Visualization Design

## Overview

This document defines a first graph-visualization phase for `lightrag-java`, aligned with the upstream LightRAG server direction of knowledge-graph exploration and graph visualization.

The current Java repository already exposes graph-management write APIs in the Spring Boot demo:

- create entity
- edit entity
- merge entities
- delete entity
- create relation
- edit relation
- delete relation

What it does not expose yet is the read path needed to explore the graph visually:

- entity search for graph navigation
- neighborhood/subgraph reads centered on a chosen entity
- a browser UI for graph exploration

This phase should add the minimum end-to-end capability needed to browse, inspect, and edit the knowledge graph from the demo application while keeping `lightrag-core` as the business-logic boundary.

## Goals

- Add public read-oriented graph APIs to `lightrag-core`.
- Expose graph exploration HTTP endpoints from `lightrag-spring-boot-demo`.
- Add an embedded graph UI page to the demo application.
- Support node-centered graph exploration instead of requiring a full-graph load.
- Reuse the existing graph write APIs for create, edit, merge, and delete actions from the UI, with one targeted extension for precise relation deletion by `relationType`.
- Keep the design compatible with current storage providers: in-memory, PostgreSQL, and PostgreSQL + Neo4j.

## Non-Goals

- Do not build a separate frontend application in this phase.
- Do not make full-graph rendering the primary interaction model.
- Do not add advanced graph analytics, path finding, or Cypher-like query APIs.
- Do not redesign existing entity/relation write semantics.
- Do not introduce background jobs, subscriptions, or collaborative live updates.
- Do not attempt parity with every upstream Web UI feature in this phase.

## Upstream Reference Direction

The upstream LightRAG project README describes LightRAG Server with:

- a Web UI
- knowledge graph exploration
- graph visualization
- node query and subgraph filtering workflows

This Java phase should align with the same product direction while remaining pragmatic about the current repository shape:

- keep the SDK core independent from HTTP/UI concerns
- prefer entity-centered subgraph exploration over eager full-graph rendering
- treat graph editing as an adjacent capability available inside the graph UI

## Current Repository Gap

### Existing Capabilities

The repository already has:

- `LightRag.createEntity(...)`
- `LightRag.editEntity(...)`
- `LightRag.mergeEntities(...)`
- `LightRag.createRelation(...)`
- `LightRag.editRelation(...)`
- `LightRag.deleteByEntity(...)`
- `LightRag.deleteByRelation(...)`
- demo HTTP write endpoints under `/graph/*`
- storage-layer graph reads through `GraphStore.allEntities()`, `GraphStore.allRelations()`, and `GraphStore.findRelations(entityId)`

### Missing Capabilities

The repository currently lacks:

- a public `LightRag` graph read surface
- demo HTTP read endpoints for graph exploration
- any graph visualization page or static assets

## Approach Options

### Recommended: Core Read API + Demo Read API + Embedded Graph UI

Add minimal read APIs in `lightrag-core`, expose them from the demo, and render an embedded graph page from the same Spring Boot app.

Benefits:

- preserves `LightRag` as the core boundary instead of coupling controllers to storage internals
- delivers an end-to-end usable feature quickly
- fits the current demo module and avoids introducing a frontend build pipeline
- keeps future migration to a dedicated frontend possible

Trade-offs:

- the initial UI should stay intentionally lightweight
- browser-side state management will remain simpler than a dedicated SPA

### Alternative: Demo Directly Reads `GraphStore`

Expose read endpoints in the demo by reaching into the storage provider and using `graphStore()` directly.

Benefits:

- smallest short-term code change

Trade-offs:

- leaks storage concerns into HTTP code
- makes future traversal and lookup rules harder to evolve safely
- weakens the SDK abstraction

### Alternative: Full Frontend App First

Build a dedicated frontend application and a larger graph API surface immediately.

Benefits:

- higher UI ceiling
- cleaner long-term frontend structure

Trade-offs:

- much larger initial scope
- slower time to usable feature
- unnecessary for the first graph-visualization phase

## Recommended Design

Adopt a two-part implementation:

1. Add read-oriented graph APIs to `lightrag-core`.
2. Add demo HTTP read endpoints plus one embedded graph UI page.

Write operations remain on the existing graph-management endpoints.

This creates a clear architecture:

```text
[Graph UI]
    ↓ HTTP
[Demo Graph Read Controller]
    ↓
[LightRag graph read API]
    ↓
[GraphStore]
```

Write operations continue to flow through the existing controller:

```text
[Graph UI]
    ↓ HTTP
[Existing GraphController]
    ↓
[LightRag graph management API]
```

## Core API Shape

Add public synchronous graph read APIs to `LightRag`.

Recommended methods:

- `listGraphEntities()`
- `searchGraphEntities(String query, int limit)`
- `getEntityNeighborhood(String entityName, int depth, int limit)`

Optional debug-oriented method:

- `getGraphSnapshot(int limit)`

### Why These Methods

`listGraphEntities()` gives the demo a simple read surface for future admin views.

`searchGraphEntities(...)` supports the main graph UI search box and should resolve against:

- entity display names
- normalized names
- aliases

`getEntityNeighborhood(...)` is the key graph-visualization method. It should return a node-centered subgraph suitable for immediate rendering and should avoid forcing the UI to request the full graph.

`getGraphSnapshot(...)` is optional and should not become the default UI path. It is useful for small datasets, diagnostics, and tests.

## Core Return Types

Add a dedicated response record for graph visualization.

Recommended shape:

- `GraphView`
  - `String centerEntityName`
  - `List<GraphEntity> nodes`
  - `List<GraphRelation> edges`

Optional supporting record:

- `GraphSearchResult`
  - `String id`
  - `String name`
  - `String type`
  - `List<String> aliases`

### Why Not Expose Storage Records

The visualization layer should consume API-level records, not storage records, so that:

- storage implementations remain replaceable
- future traversal semantics can evolve without changing HTTP contracts
- graph read logic stays inside `lightrag-core`

## Graph Read Semantics

### Entity Search

`searchGraphEntities(query, limit)` should:

1. require a non-blank query
2. normalize case and surrounding whitespace
3. score exact name matches ahead of alias matches
4. score prefix matches ahead of contains matches
5. return stable ordering for ties
6. cap result size by `limit`

This method is for UI navigation, not relevance ranking.

### Neighborhood Read

`getEntityNeighborhood(entityName, depth, limit)` should:

1. resolve the center entity with the same lookup precedence as graph management: exact normalized name first, alias fallback second
2. treat a missing resolved entity as `NoSuchElementException`
3. treat ambiguous or invalid lookup input as `IllegalArgumentException`
3. require `depth >= 1`
4. support `depth=1` and `depth=2` in the first phase
5. traverse relations breadth-first from the center entity
6. collect a deduplicated node and edge set
7. treat `limit` as the maximum number of nodes in the returned graph, including the center node
8. stop traversal before adding a newly discovered node that would exceed the node limit
9. include only edges whose two endpoints are both present in the returned node set
8. return a render-ready `GraphView`

The first phase should not attempt provider-specific deep graph queries. It should assemble the neighborhood from the current `GraphStore` contract.

This keeps HTTP semantics stable:

- invalid input or ambiguous alias lookup -> `400`
- no exact-name or alias match for the requested entity -> `404`

### Snapshot Read

If `getGraphSnapshot(limit)` is implemented, it should:

- return a bounded graph for small-dataset browsing or diagnostics
- be clearly treated as optional debug support
- not be the primary graph UI entrypoint

## Demo HTTP API Shape

Add read endpoints under the existing `/graph` prefix.

Recommended endpoints:

- `GET /graph/ui`
- `GET /graph/entities/search?q=alice&limit=20`
- `GET /graph/neighborhood?entityName=alice&depth=1&limit=50`
- optional `GET /graph/snapshot?limit=100`

Keep the existing write endpoint family, with one targeted precision upgrade:

- `POST /graph/entities`
- `PUT /graph/entities`
- `POST /graph/entities/merge`
- `DELETE /graph/entities/{entityName}`
- `POST /graph/relations`
- `PUT /graph/relations`
- `DELETE /graph/relations?sourceEntityName=...&targetEntityName=...` with optional `relationType=...` for precise single-edge deletion

### Workspace Behavior

Graph read endpoints must use the same workspace resolution rules as the existing demo controllers.

Because the demo currently resolves workspace from a request header only, the graph page must bootstrap workspace explicitly on the client side:

- the page URL accepts `?workspaceId=<id>`
- the page JavaScript reads `workspaceId` from the query string
- every browser fetch to `/graph/**` attaches that value to the configured workspace header
- if `workspaceId` is omitted, the page relies on the server default workspace just like other demo endpoints

This avoids adding server-side templating in the first phase while keeping the page compatible with the current `WorkspaceResolver`.

## HTTP Response Shape

The graph UI should consume one stable JSON shape for subgraph rendering.

Recommended response:

```json
{
  "centerEntityName": "Alice",
  "nodes": [
    {
      "id": "entity-1",
      "name": "Alice",
      "type": "person",
      "description": "Researcher",
      "aliases": ["Al"],
      "sourceChunkIds": ["chunk-1"]
    }
  ],
  "edges": [
    {
      "id": "rel-1",
      "sourceEntityId": "entity-1",
      "targetEntityId": "entity-2",
      "type": "works_with",
      "description": "Cross-team collaboration",
      "weight": 0.8,
      "sourceChunkIds": ["chunk-2"]
    }
  ]
}
```

This shape intentionally reuses existing API-level entity and relation records so the detail panel and edit flows can share one contract.

## UI Design

Embed a lightweight graph page in `lightrag-spring-boot-demo`.

Recommended route:

- `/graph/ui`

Recommended static asset approach:

- serve one HTML page with companion CSS and JavaScript from Spring Boot static resources
- avoid introducing a separate frontend toolchain in this phase

### Main Layout

The page should have three areas:

1. top search and action bar
2. central graph canvas
3. right-side detail and edit panel

### Primary User Flow

The main flow should be:

1. search for an entity
2. choose a result
3. load its one-hop neighborhood
4. click nodes and edges to inspect them
5. expand the current graph around a selected node
6. perform create/edit/merge/delete actions from the detail panel
7. refresh the current neighborhood after each write action

### Why Node-Centered Exploration

The graph page should not default to full-graph rendering because:

- large graphs become unreadable quickly
- browser performance degrades as node count grows
- the upstream product direction favors graph exploration and subgraph filtering

Node-centered exploration is the safer default and is compatible with both in-memory and durable backends.

## UI Interaction Details

### Search

The top bar should support:

- keyword search by entity name or alias
- selection from returned results
- a clear-graph action

### Canvas Behavior

The graph canvas should support:

- drag-and-drop nodes
- click node to select and show entity details
- click edge to select and show relation details
- highlight incident edges for the selected node
- expand one-hop or two-hop neighborhood from the selected node

### Detail Panel

When a node is selected, show:

- name
- type
- description
- aliases
- source chunk IDs

Available actions:

- edit entity
- delete entity
- merge entities
- expand neighborhood

When an edge is selected, show:

- relation type
- description
- weight
- source chunk IDs

Available actions:

- edit relation
- delete relation

When nothing is selected, show:

- create entity
- create relation

### Write Operation UX

The first phase should use lightweight forms or modal dialogs for:

- create entity
- edit entity
- merge entities
- create relation
- edit relation
- delete entity confirmation
- delete relation confirmation

For entity merge UX, the currently selected node is always the merge target. The form should let the user search and select one or more source entities to merge into that target. After a successful merge, the page should reload the target entity neighborhood and keep the target selected.

For relation deletion, the UI should use the precise delete form by sending `sourceEntityName`, `targetEntityName`, and `relationType` so deleting one selected edge cannot accidentally remove other relations between the same endpoints. When `relationType` is omitted, the existing broader endpoint behavior remains unchanged for backward compatibility.

After a successful write:

- prefer reloading the current neighborhood from the server
- avoid relying only on fragile local graph patching

This keeps client-side consistency simple after merge or delete operations.

## Error Handling

### Core And HTTP Rules

- blank search query -> `400`
- invalid `depth` or `limit` -> `400`
- missing entity -> `404`
- unexpected failures -> `500`

### UI Rules

- no search result -> inline empty-state message, not a hard error dialog
- object deleted concurrently -> show a small warning and refresh the current neighborhood
- write validation failure -> show the backend error directly in the form
- server failure -> keep the current canvas intact and show a retryable error message

## Testing Strategy

### Core Tests

Required coverage:

- `searchGraphEntities(...)` exact, prefix, alias, and contains matching behavior
- deterministic ordering of search results
- `getEntityNeighborhood(...)` for `depth=1`
- `getEntityNeighborhood(...)` for `depth=2`
- neighborhood deduplication
- missing entity handling
- parameter validation

### Demo HTTP Tests

Required coverage:

- `GET /graph/entities/search`
- `GET /graph/neighborhood`
- invalid parameter handling with `400`
- missing entity handling with `404`
- workspace header routing
- `GET /graph/ui` serving the page successfully

### UI-Oriented Verification

At minimum:

- page loads
- search calls the read API
- selected search result renders graph nodes
- expanding a node adds neighbors
- create/edit/delete actions refresh the current graph view

The first phase can keep browser verification lightweight if end-to-end browser tooling is not already present in the repository.

## File Impact

Expected new or modified areas:

- `lightrag-core/src/main/java/io/github/lightragjava/api/LightRag.java`
- new core API records for graph reads
- new internal core service for graph read assembly
- `lightrag-core/src/main/java/io/github/lightragjava/indexing/DeletionPipeline.java`
- `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphController.java`
- `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphReadController.java`
- `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/GraphPageController.java`
- new or updated demo tests for graph read endpoints
- `lightrag-spring-boot-demo/src/main/resources/static/...` for the graph page assets

## Open Questions Deferred

These are intentionally deferred beyond the first phase:

- advanced graph filtering beyond neighborhood depth and size
- graph layout persistence
- bulk graph editing
- workspace switcher UI
- dedicated frontend application
- provider-specific acceleration for deep graph traversal

## Recommendation

Proceed with the minimal upstream-aligned graph-visualization phase:

- add graph read APIs to `lightrag-core`
- expose entity search and neighborhood endpoints from the demo
- embed a lightweight graph exploration page in the demo
- keep graph editing inside the same page by reusing existing write endpoints

This provides a usable first graph UI without overextending the repository or breaking current module boundaries.
