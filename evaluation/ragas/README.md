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
  --documents-dir evaluation/ragas/sample_documents
```

Results are written to `evaluation/ragas/results/`.
