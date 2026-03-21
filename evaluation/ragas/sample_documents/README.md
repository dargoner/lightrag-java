# Sample Documents for Evaluation

These markdown files correspond to test questions in `../sample_dataset.json`.

## Usage

1. These files are the default Java evaluation corpus.
2. Run the bundled evaluator from the repository root:

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --documents-dir evaluation/ragas/sample_documents \
  --dataset evaluation/ragas/sample_dataset.json
```

3. Refresh the named baseline explicitly when you want to accept the current run:

```bash
python3 evaluation/ragas/eval_rag_quality_java.py \
  --documents-dir evaluation/ragas/sample_documents \
  --dataset evaluation/ragas/sample_dataset.json \
  --baseline-name sample-default \
  --update-baseline
```

## Files

- `01_lightrag_overview.md` - LightRAG framework and hallucination problem
- `02_rag_architecture.md` - RAG system components
- `03_lightrag_improvements.md` - LightRAG vs traditional RAG
- `04_supported_databases.md` - Vector database support
- `05_evaluation_and_deployment.md` - Metrics and deployment

## Note

Documents use clear entity-relationship patterns for LightRAG's default entity extraction prompts. For better results with your data, customize the Java-side chunking and retrieval settings through `LightRag.builder()` or Spring Boot properties.
