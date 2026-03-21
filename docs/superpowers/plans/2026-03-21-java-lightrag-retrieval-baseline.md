# Retrieval Baseline Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有 RAGAS 评测链路补成可重复、可落盘、可对比的 retrieval regression baseline。

**Architecture:** Java CLI 输出结构化 request/result 元数据，Python 评测脚本负责跑分、落地 `baseline.json/csv` 与 `latest.json/csv`，并生成与基线的差异摘要。文档层提供固定命令入口，避免继续依赖一次性手工执行。

**Tech Stack:** Java 17, Gradle, Python 3, RAGAS, JUnit 5, pytest

---

### Task 1: 固定批量评测输出

**Files:**
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationCli.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasBatchEvaluationService.java`
- Modify: `lightrag-core/src/main/java/io/github/lightragjava/evaluation/RagasEvaluationCli.java`
- Test: `lightrag-core/src/test/java/io/github/lightragjava/evaluation/RagasCliOutputTest.java`

- [ ] **Step 1: 先写 CLI 输出结构测试**
- [ ] **Step 2: 在 batch 输出中加入 request metadata、run label、ground truth、references**
- [ ] **Step 3: 保持单次 query CLI 输出结构与 batch 形状一致**
- [ ] **Step 4: 跑 `:lightrag-core:test --tests ...RagasCliOutputTest`**

### Task 2: 增加基线落盘与对比

**Files:**
- Modify: `evaluation/ragas/eval_rag_quality_java.py`
- Create: `evaluation/ragas/test_eval_rag_quality_java.py`
- Modify: `evaluation/ragas/README.md`

- [ ] **Step 1: 为 Python 评测脚本补结果标准化测试**
- [ ] **Step 2: 支持 `run label`、latest 结果输出与 baseline 元数据落盘**
- [ ] **Step 3: 支持存在 baseline 时输出平均分差异摘要**
- [ ] **Step 4: 跑 `python3 -m pytest evaluation/ragas/test_eval_rag_quality_java.py`**

### Task 3: README 入口统一

**Files:**
- Modify: `README.md`
- Modify: `README_zh.md`

- [ ] **Step 1: 补充固定 dataset/documents 默认值**
- [ ] **Step 2: 补充 baseline 命令、输出目录与环境变量说明**
- [ ] **Step 3: 运行 `./gradlew :lightrag-core:test`**
