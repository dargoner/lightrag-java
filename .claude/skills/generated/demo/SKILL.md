---
name: demo
description: "Skill for the Demo area of lightrag-java. 108 symbols across 18 files."
---

# Demo

108 symbols | 18 files | Cohesion: 70%

## When to Use

- Working with code in `lightrag-spring-boot-demo/`
- Understanding how LightRagProperties, of, streaming work
- Modifying demo-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | getJob, listJobs, cancel, retry, requireJob (+26) |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/UploadControllerTest.java` | uploadsMultipleFilesAndAllowsAsyncOverride, uploadsDocxAsRawSourceAndKeepsBusinessDefaults, surfacesParserFailureWhenSyncUploadThrows, rejectsWhitespaceOnlyFileContent, rejectsUnsupportedFileExtension (+9) |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/UploadedDocumentMapper.java` | toSources, toSource, normalizeFilename, validateSupportedExtension, readBytes (+7) |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/StreamingQueryControllerTest.java` | lightRagProperties, fallsBackToDefaultWorkspaceWhenHeaderMissing, streamsBufferedFallbackAsAnswerEvent, streamsContextFallbackAsAnswerEvent, dispatch (+5) |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/DemoApplicationTest.java` | ingestsDocumentsAndAnswersQuery, uploadsFileAndAnswersQuery, uploadsDocxAndAnswersQueryThroughRawSourcePath, ingestsDocumentsAndStreamsQuery, awaitJobSuccess (+3) |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/DocumentStatusControllerTest.java` | jobsEndpointReturnsPaginatedNewestFirstWithTimelineFields, failedJobExposesErrorMessageInDetailAndListResponses, cancelPendingJobAndRetryCreatesNewAttempt, retryFailedJobCreatesNewAttemptAndRejectsSucceededTransitions, isolatesJobsStatusesAndControlsAcrossWorkspaces (+2) |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | listJobs, getJobStatus, cancelJob, toResponse |
| `lightrag-spring-boot-demo/src/test/java/io/github/lightrag/demo/DocumentControllerTest.java` | lightRagProperties, submitsIngestJobForResolvedWorkspace, fallsBackToDefaultWorkspaceWhenHeaderMissing, rejectsEmptyDocumentsPayload |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/QueryRequestMapper.java` | toStreamingRequest, validate, requirePositive, requireNonBlank |
| `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/QueryStreamService.java` | stream, cancel, closeResult |

## Entry Points

Start here when exploring this area:

- **`LightRagProperties`** (Class) — `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java:9`
- **`of`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/CloseableIterator.java:29`
- **`streaming`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java:29`
- **`close`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java:37`
- **`close`** (Method) — `lightrag-core/src/main/java/io/github/lightrag/model/CloseableIterator.java:20`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `LightRagProperties` | Class | `lightrag-spring-boot-starter/src/main/java/io/github/lightrag/spring/boot/LightRagProperties.java` | 9 |
| `of` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/CloseableIterator.java` | 29 |
| `streaming` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java` | 29 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/api/QueryResult.java` | 37 |
| `close` | Method | `lightrag-core/src/main/java/io/github/lightrag/model/CloseableIterator.java` | 20 |
| `UploadedDocumentMapper` | Class | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/UploadedDocumentMapper.java` | 18 |
| `listJobs` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | 41 |
| `getJobStatus` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | 57 |
| `cancelJob` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | 65 |
| `toResponse` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/DocumentStatusController.java` | 105 |
| `getJob` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 58 |
| `listJobs` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 65 |
| `cancel` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 84 |
| `retry` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 88 |
| `requireJob` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 203 |
| `shouldRetryDocument` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 210 |
| `jobId` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 328 |
| `workspaceId` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 332 |
| `hasRawSources` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 348 |
| `attempt` | Method | `lightrag-spring-boot-demo/src/main/java/io/github/lightrag/demo/IngestJobService.java` | 356 |

## Execution Flows

| Flow | Type | Steps |
|------|------|-------|
| `Ingest → ResolveScope` | cross_community | 7 |
| `Ingest → ResolveProvider` | cross_community | 7 |
| `Ingest → MarkStarted` | cross_community | 6 |
| `Ingest → HasRawSources` | cross_community | 6 |
| `Ingest → WorkspaceId` | cross_community | 6 |
| `Ingest → RequireNonBlank` | cross_community | 5 |
| `Ingest → JobState` | cross_community | 5 |
| `Ingest → AttachFuture` | cross_community | 5 |
| `Upload → Equals` | cross_community | 5 |
| `Upload → NormalizeContentType` | cross_community | 5 |

## Connected Areas

| Area | Connections |
|------|-------------|
| Api | 23 calls |
| Indexing | 15 calls |
| Query | 6 calls |
| Boot | 4 calls |
| Task | 3 calls |
| Postgres | 1 calls |

## How to Explore

1. `gitnexus_context({name: "LightRagProperties"})` — see callers and callees
2. `gitnexus_query({query: "demo"})` — find related execution flows
3. Read key files listed above for implementation details
