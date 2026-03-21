# Java RAGAS Evaluation

This directory adapts the upstream LightRAG RAGAS evaluation flow to `lightrag-java`.

It keeps the upstream-style dataset and RAGAS metrics, but replaces the Python server `/query` call with a local Java SDK runner.

## Required environment

Query execution model:

- `LIGHTRAG_JAVA_EVAL_CHAT_API_KEY`
- `LIGHTRAG_JAVA_EVAL_CHAT_MODEL`
- `LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL`

RAGAS evaluator model:

- `EVAL_LLM_BINDING_API_KEY`
- `EVAL_LLM_MODEL`
- `EVAL_EMBEDDING_MODEL`

Simplest setup:

- set only `OPENAI_API_KEY`
- keep the defaults from `.env.example`

Storage profile:

- `LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE=in-memory`
- `LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE=postgres-neo4j-testcontainers`

For the Testcontainers profile, Docker must be available locally.

Optional custom OpenAI-compatible endpoints:

- `LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL`
- `LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL`
- `EVAL_LLM_BINDING_HOST`
- `EVAL_EMBEDDING_BINDING_HOST`

## Python dependencies

Install:

```bash
python3 -m pip install -r evaluation/ragas/requirements.txt
```

If `pip` is not installed on your machine, install your system package first, for example on Debian/Ubuntu:

```bash
sudo apt install python3-pip python3-venv
```

## Run

```bash
python3 evaluation/ragas/eval_rag_quality_java.py
```

Optional:

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --dataset evaluation/ragas/sample_dataset.json \
  --documents-dir evaluation/ragas/sample_documents \
  --run-label candidate-rerank-4 \
  --baseline-name sample-default
```

Create or refresh the named baseline:

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --run-label baseline \
  --baseline-name sample-default \
  --update-baseline
```

Compatibility notes:

- the Python wrapper accepts both the legacy batch `list` payload and the new Java CLI envelope `{request, summary, results}`
- when the Java CLI returns structured context objects, the wrapper automatically converts them to the plain context strings expected by RAGAS
- when a baseline file exists, the wrapper prints average deltas and exits non-zero if the regression threshold is exceeded

Outputs:

- `evaluation/ragas/results/results_<timestamp>.json|csv`
- `evaluation/ragas/results/latest_<run-label>.json|csv`
- `evaluation/ragas/baselines/<baseline-name>.json|csv` when `--update-baseline` is used

Lightweight verification without live model calls:

```bash
python3 -m unittest evaluation/ragas/test_eval_rag_quality_java.py
python3 -m py_compile evaluation/ragas/eval_rag_quality_java.py
```
