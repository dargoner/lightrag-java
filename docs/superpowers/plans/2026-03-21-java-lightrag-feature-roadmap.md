# Java LightRAG Feature Roadmap Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `lightrag-java` P0 能力基础上，规划后续功能推进顺序，把仓库从“可演示的 SDK + demo service”推进到“可接入、可管理、可运营”的 P1-P3 产品形态。

**Architecture:** 保持 `lightrag-core` 继续承担检索、存储、图谱和评测核心能力；把服务协议、任务编排、租户隔离收敛到 `lightrag-spring-boot-demo` 与后续管理端；把路线图拆成若干独立子计划，避免单个大计划跨越过多子系统。

**Tech Stack:** Java 17, Spring Boot 3.3, Gradle, JUnit 5, MockMvc, Testcontainers, PostgreSQL, Neo4j

---

## Current Baseline

- 已具备 SDK 级查询能力：`NAIVE/LOCAL/GLOBAL/HYBRID/MIX`、`stream`、`userPrompt`、`conversationHistory`、`includeReferences`、`rerank`
- 已具备 P0 服务接口：`/documents/ingest`、文档状态/删除、`/query`、图管理接口
- 已具备持久化选项：`in-memory`、`PostgresStorageProvider`、`PostgresNeo4jStorageProvider`
- 当前主要缺口不在核心算法，而在 **服务协议补齐、工作区隔离、批量导入、产品外壳、运维能力**

## File Structure

- Create: `docs/superpowers/plans/2026-03-21-java-lightrag-feature-roadmap.md`
- Planned: `docs/superpowers/plans/2026-03-21-java-lightrag-streaming-http.md`
- Planned: `docs/superpowers/plans/2026-03-21-java-lightrag-file-ingest.md`
- Planned: `docs/superpowers/plans/2026-03-21-java-lightrag-workspace-isolation.md`
- Planned: `docs/superpowers/plans/2026-03-21-java-lightrag-retrieval-quality.md`
- Planned: `docs/superpowers/plans/2026-03-21-java-lightrag-admin-console.md`
- Planned modules:
  - `lightrag-core`
  - `lightrag-spring-boot-starter`
  - `lightrag-spring-boot-demo`
  - `README.md`

## Scope Guardrails

- 本路线图只定义 **后续功能阶段和拆分顺序**，不在本文件里直接塞入跨多月的大实施细节
- 每个阶段落地前，都应再单独生成一份子计划文档
- 优先做“能显著提高可接入性”的能力，不先做 UI 包装

## Phase Overview

```text
P1 服务协议补齐
  -> P1.5 工作区/租户隔离
    -> P2 检索质量与 pipeline 可控性
      -> P3 管理端与运维外壳
```

### Task 1: P1 服务协议补齐

**Files:**
- Plan: `docs/superpowers/plans/2026-03-21-java-lightrag-streaming-http.md`
- Plan: `docs/superpowers/plans/2026-03-21-java-lightrag-file-ingest.md`
- Modify later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/QueryController.java`
- Modify later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/DocumentController.java`
- Create later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/StreamingQueryController.java`
- Create later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/UploadController.java`

- [ ] **Step 1: 先拆分流式查询子计划**

目标：
- 把 SDK 已支持的 `stream` 能力暴露为 HTTP 端点
- 明确使用 `SSE`、`NDJSON` 或 chunked plain text 的协议边界
- 保持 buffered `/query` 与 streaming 端点职责分离

- [ ] **Step 2: 再拆分文件导入子计划**

目标：
- 支持单文件上传、批量上传、目录扫描三类入口
- 与现有异步 ingest job 模型复用，不重新造任务系统
- 优先支持文本、Markdown、PDF 等最常见输入源

- [ ] **Step 3: 补充任务管理最小闭环**

目标：
- job 取消、失败详情、分页查询、重试入口
- 保持 controller 只负责请求映射和错误翻译

- [ ] **Step 4: 完成 P1 验收**

验收标准：
- SDK 和 HTTP 层的 `stream` 语义一致
- 文件导入不再依赖手工构造 JSON `Document`
- demo service 对外可以覆盖最基础的“接入 -> 导入 -> 查询”链路

### Task 2: P1.5 工作区与租户隔离

**Files:**
- Plan: `docs/superpowers/plans/2026-03-21-java-lightrag-workspace-isolation.md`
- Modify later: `lightrag-spring-boot-starter/src/main/java/io/github/lightragjava/spring/boot/LightRagProperties.java`
- Modify later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/**`
- Modify later: `README.md`

- [ ] **Step 1: 先定义 workspace 模型**

目标：
- 明确 workspace、knowledge base、storage prefix 三者关系
- 先支持服务端隔离，不急着做复杂权限系统

- [ ] **Step 2: 定义按 workspace 的请求边界**

目标：
- 导入、查询、图管理、状态查询都显式绑定 workspace
- 避免全局单实例把所有数据写进同一逻辑空间

- [ ] **Step 3: 定义 starter 配置扩展**

目标：
- 支持默认 workspace 策略
- 支持基于请求头、路径参数或上下文路由到不同存储前缀

- [ ] **Step 4: 完成 P1.5 验收**

验收标准：
- 多个 workspace 之间文档、图谱、查询结果互不污染
- 服务端具备继续做管理端的基础数据模型

### Task 3: P2 检索质量与 pipeline 可控性

**Files:**
- Plan: `docs/superpowers/plans/2026-03-21-java-lightrag-retrieval-quality.md`
- Modify later: `lightrag-core/src/main/java/io/github/lightragjava/**`
- Modify later: `lightrag-core/src/test/java/io/github/lightragjava/**`
- Modify later: `README.md`

- [ ] **Step 1: 先盘点质量瓶颈**

目标：
- 抽取质量、实体合并、chunk 策略、rerank、提示模板
- 用已有 RAGAS 和集成测试结果建立对照基线

- [ ] **Step 2: 拆出 pipeline 配置面**

目标：
- chunking、extraction、merge、rerank 可配置
- 不把核心链路写死在一个大 builder 里

- [ ] **Step 3: 引入质量回归基线**

目标：
- 每次改动能看到 retrieval 质量变化，而不是只看单测是否通过
- 为后续“模型适配”“提示优化”提供可验证数据

- [ ] **Step 4: 完成 P2 验收**

验收标准：
- 核心检索链路具备参数化能力
- 至少有一组固定评测数据可做回归比较

### Task 4: P3 管理端与产品外壳

**Files:**
- Plan: `docs/superpowers/plans/2026-03-21-java-lightrag-admin-console.md`
- Create later: `frontend/**` 或独立 admin 模块
- Modify later: `lightrag-spring-boot-demo/src/main/java/io/github/lightragjava/demo/**`
- Modify later: `README.md`

- [ ] **Step 1: 明确管理端最小范围**

目标：
- 文档列表、job 状态、workspace 管理、基础图谱查看
- 不一开始就做复杂编辑器和 BI 面板

- [ ] **Step 2: 增加运维能力**

目标：
- 限流、审计、健康检查、基本指标
- 为部署模板和生产环境接入做准备

- [ ] **Step 3: 增加部署文档与模板**

目标：
- Docker Compose 优先
- 再补充 Kubernetes/Helm

- [ ] **Step 4: 完成 P3 验收**

验收标准：
- 服务不再只靠 curl/JSON 演示
- 最低限度具备“管理、观察、排障”的产品外壳

## Priority Order

1. `streaming-http`
2. `file-ingest`
3. `workspace-isolation`
4. `retrieval-quality`
5. `admin-console`

## Recommended Next Move

- 先立刻写 `streaming-http` 子计划
- 然后写 `file-ingest` 子计划
- 两者完成后再进入 `workspace-isolation`

## Acceptance Criteria

- 路线图阶段边界清晰，不再把多个独立子系统塞进一个实现计划
- 下一步功能推进顺序明确：先协议补齐，再隔离，再质量，再产品外壳
- 后续执行者可以直接从本文件挑出下一个子计划目标继续推进
