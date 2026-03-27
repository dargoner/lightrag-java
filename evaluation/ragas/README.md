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

Optional multi-hop query controls:

- `LIGHTRAG_JAVA_EVAL_MAX_HOP`
- `LIGHTRAG_JAVA_EVAL_PATH_TOP_K`
- `LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED`
- `LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY`

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

Prepare a public BEIR dataset in the same input shape:

```bash
python3 evaluation/ragas/prepare_beir_dataset.py \
  --dataset scifact \
  --max-cases 25
```

Then run the evaluator against the generated corpus:

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --dataset evaluation/ragas/datasets/beir/scifact/dataset.json \
  --documents-dir evaluation/ragas/datasets/beir/scifact/documents \
  --run-label beir-scifact-25 \
  --baseline-name beir-scifact
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

## Public benchmark datasets

`prepare_beir_dataset.py` converts public BEIR raw files into the same
`dataset.json + documents/*.md` layout used by the Java evaluator.

- Default dataset: `scifact`
- Default source: official BEIR `scifact.zip`
- Output: `evaluation/ragas/datasets/beir/<dataset>/`
- Default document export mode: only documents referenced by the selected qrels

Useful options:

- `--source-dir /path/to/beir/scifact` to reuse a local raw dataset
- `--split test` to choose the BEIR qrels split
- `--max-cases 25` to create a smaller, cheaper RAGAS run
- `--include-all-documents` to export the full BEIR corpus instead of only relevant documents

The generated `ground_truth` is built by concatenating relevant BEIR documents.
That makes it a practical public RAG regression set, but the final RAGAS score
is not directly comparable to official BEIR retrieval metrics such as nDCG.

Lightweight verification without live model calls:

```bash
python3 -m unittest evaluation/ragas/test_eval_rag_quality_java.py
python3 -m py_compile evaluation/ragas/eval_rag_quality_java.py
python3 -m unittest evaluation/ragas/test_prepare_beir_dataset.py
python3 -m py_compile evaluation/ragas/prepare_beir_dataset.py
```

## Retrieval-only baseline

For public benchmark or cheaper regression checks, use the retrieval-only runner.
It reuses the Java batch CLI, but scores retrieval against `relevant_doc_ids`
from `dataset.json` instead of calling RAGAS.

Create or refresh a local baseline:

```bash
python3 evaluation/ragas/eval_retrieval_quality_java.py \
  --dataset evaluation/ragas/sample_dataset.json \
  --documents-dir evaluation/ragas/sample_documents \
  --run-label sample-retrieval \
  --baseline-name sample-retrieval \
  --update-baseline
```

Notes:

- retrieval-only runs the Java batch CLI with `--retrieval-only true`, so query-time
  answer generation and automatic keyword extraction are skipped
- the Java retrieval-only CLI also skips live chat-based graph extraction, which keeps
  public benchmark runs cheap and stable for regression use
- for formal multi-hop benchmarks, set `LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY=false`;
  otherwise the Java runner uses empty graph extraction output and the graph hop chain
  will not be built
- `sample_dataset.json` is useful as a smoke run, but it does not contain
  `relevant_doc_ids`; use BEIR-derived datasets for meaningful retrieval metrics
- `evaluation/ragas/datasets/multihop/fusion-aide/` is a bundled multi-hop smoke
  dataset with `hop_doc_ids`, used to report `all_hops_hit_rate`

Run a public benchmark candidate:

```bash
python3 evaluation/ragas/eval_retrieval_quality_java.py \
  --dataset evaluation/ragas/datasets/beir/scifact/dataset.json \
  --documents-dir evaluation/ragas/datasets/beir/scifact/documents \
  --run-label beir-scifact-25-retrieval \
  --baseline-name beir-scifact-retrieval
```

Run the bundled multi-hop smoke benchmark:

```bash
LIGHTRAG_JAVA_EVAL_QUERY_MODE=mix \
LIGHTRAG_JAVA_EVAL_MAX_HOP=3 \
LIGHTRAG_JAVA_EVAL_PATH_TOP_K=5 \
LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED=true \
LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY=false \
python3 evaluation/ragas/eval_retrieval_quality_java.py \
  --dataset evaluation/ragas/datasets/multihop/fusion-aide/dataset.json \
  --documents-dir evaluation/ragas/datasets/multihop/fusion-aide/documents \
  --run-label fusion-aide-multihop \
  --baseline-name fusion-aide-multihop
```

Outputs follow the same pattern:

- `evaluation/ragas/results/latest_<run-label>.json|csv`
- `evaluation/ragas/baselines/<baseline-name>.json|csv`

Summary fields include:

- `top1_hit_rate`
- `top3_hit_rate`
- `all_hops_hit_rate`
- `average_recall`
- optional delta fields when a baseline already exists
