---
name: ragas
description: "Skill for the Ragas area of lightrag-java. 70 symbols across 6 files."
---

# Ragas

70 symbols | 6 files | Cohesion: 96%

## When to Use

- Working with code in `evaluation/`
- Understanding how load_module, load_module, load_module work
- Modifying ragas-related functionality

## Key Files

| File | Symbols |
|------|---------|
| `evaluation/ragas/eval_rag_quality_java.py` | _is_nan, _normalize_contexts, _normalize_batch_results, generate_rag_responses, evaluate_single_case (+15) |
| `evaluation/ragas/eval_retrieval_quality_java.py` | _apply_env_fallbacks, _set_default_env, _normalize_contexts, _normalize_batch_results, _document_id_from_source_id (+12) |
| `evaluation/ragas/prepare_beir_dataset.py` | _prepare_from_source, _selected_doc_ids, _build_dataset_payload, _ground_truth_sections, _render_document (+6) |
| `evaluation/ragas/test_eval_rag_quality_java.py` | from_dict, load_module, test_keeps_legacy_batch_list_shape, test_unwraps_batch_envelope_and_structured_contexts, test_slugify_normalizes_labels (+3) |
| `evaluation/ragas/test_eval_retrieval_quality_java.py` | load_module, test_normalizes_source_ids_to_document_ids, test_evaluates_hit_rates_and_recall, test_compare_with_baseline_reports_delta, test_env_fallbacks_map_legacy_binding_keys (+3) |
| `evaluation/ragas/test_prepare_beir_dataset.py` | load_module, test_converts_beir_raw_files_to_ragas_dataset_and_documents, test_ignores_non_positive_and_missing_qrels, test_respects_max_cases_with_stable_query_order, test_can_export_only_relevant_documents_for_selected_cases (+1) |

## Entry Points

Start here when exploring this area:

- **`load_module`** (Function) — `evaluation/ragas/test_eval_retrieval_quality_java.py:13`
- **`load_module`** (Function) — `evaluation/ragas/test_eval_rag_quality_java.py:11`
- **`load_module`** (Function) — `evaluation/ragas/test_prepare_beir_dataset.py:10`
- **`prepare_beir_dataset`** (Function) — `evaluation/ragas/prepare_beir_dataset.py:24`
- **`main`** (Function) — `evaluation/ragas/prepare_beir_dataset.py:207`

## Key Symbols

| Symbol | Type | File | Line |
|--------|------|------|------|
| `load_module` | Function | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 13 |
| `load_module` | Function | `evaluation/ragas/test_eval_rag_quality_java.py` | 11 |
| `load_module` | Function | `evaluation/ragas/test_prepare_beir_dataset.py` | 10 |
| `prepare_beir_dataset` | Function | `evaluation/ragas/prepare_beir_dataset.py` | 24 |
| `main` | Function | `evaluation/ragas/prepare_beir_dataset.py` | 207 |
| `generate_rag_responses` | Method | `evaluation/ragas/eval_rag_quality_java.py` | 110 |
| `evaluate_single_case` | Method | `evaluation/ragas/eval_rag_quality_java.py` | 139 |
| `run` | Method | `evaluation/ragas/eval_rag_quality_java.py` | 173 |
| `from_dict` | Method | `evaluation/ragas/test_eval_rag_quality_java.py` | 19 |
| `test_normalizes_source_ids_to_document_ids` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 22 |
| `test_evaluates_hit_rates_and_recall` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 29 |
| `test_compare_with_baseline_reports_delta` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 56 |
| `test_env_fallbacks_map_legacy_binding_keys` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 82 |
| `test_writes_latest_and_baseline_payloads` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 108 |
| `test_run_java_batch_enables_retrieval_only_mode` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 153 |
| `test_build_payload_includes_multi_hop_metadata_and_delta` | Method | `evaluation/ragas/test_eval_retrieval_quality_java.py` | 184 |
| `test_keeps_legacy_batch_list_shape` | Method | `evaluation/ragas/test_eval_rag_quality_java.py` | 58 |
| `test_unwraps_batch_envelope_and_structured_contexts` | Method | `evaluation/ragas/test_eval_rag_quality_java.py` | 78 |
| `test_slugify_normalizes_labels` | Method | `evaluation/ragas/test_eval_rag_quality_java.py` | 119 |
| `test_compare_with_baseline_reports_average_delta` | Method | `evaluation/ragas/test_eval_rag_quality_java.py` | 125 |

## How to Explore

1. `gitnexus_context({name: "load_module"})` — see callers and callees
2. `gitnexus_query({query: "ragas"})` — find related execution flows
3. Read key files listed above for implementation details
