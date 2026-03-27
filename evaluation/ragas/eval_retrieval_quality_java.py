#!/usr/bin/env python3
"""
Retrieval-only regression evaluation for lightrag-java.

This avoids live RAGAS scoring and measures retrieval hit rate / recall against
dataset-provided relevant document ids.
"""

import argparse
import asyncio
import csv
import json
import os
import shlex
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv


def _apply_env_fallbacks() -> None:
    _set_default_env("LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "LLM_BINDING_API_KEY", "OPENAI_API_KEY")
    _set_default_env("LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "LLM_BINDING_HOST", default="https://api.openai.com/v1/")
    _set_default_env("LIGHTRAG_JAVA_EVAL_CHAT_MODEL", "LLM_MODEL", default="gpt-4o-mini")
    _set_default_env(
        "LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY",
        "EMBEDDING_BINDING_API_KEY",
        "LIGHTRAG_JAVA_EVAL_CHAT_API_KEY",
        "OPENAI_API_KEY",
    )
    _set_default_env(
        "LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL",
        "EMBEDDING_BINDING_HOST",
        "LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL",
        default="https://api.openai.com/v1/",
    )
    _set_default_env(
        "LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL",
        "EMBEDDING_MODEL",
        default="text-embedding-3-small",
    )


def _set_default_env(target: str, *sources: str, default: Optional[str] = None) -> None:
    current = os.getenv(target)
    if current:
        return
    for source in sources:
        value = os.getenv(source)
        if value:
            os.environ[target] = value
            return
    if default is not None:
        os.environ[target] = default


def _normalize_contexts(raw_contexts: Any) -> List[Dict[str, str]]:
    if not isinstance(raw_contexts, list):
        return []
    normalized = []
    for context in raw_contexts:
        if isinstance(context, dict):
            normalized.append(
                {
                    "sourceId": str(context.get("sourceId", "")).strip(),
                    "text": str(context.get("text", "")).strip(),
                }
            )
        elif isinstance(context, str):
            normalized.append({"sourceId": "", "text": context.strip()})
    return normalized


def _normalize_batch_results(payload: Any) -> List[Dict[str, Any]]:
    raw_results = payload if isinstance(payload, list) else payload.get("results") if isinstance(payload, dict) else None
    if not isinstance(raw_results, list):
        raise SystemExit("Java batch runner returned an unexpected JSON shape")
    normalized = []
    for item in raw_results:
        if not isinstance(item, dict):
            raise SystemExit("Java batch runner returned a non-object result entry")
        copied = dict(item)
        copied["contexts"] = _normalize_contexts(item.get("contexts", []))
        normalized.append(copied)
    return normalized


def _document_id_from_source_id(source_id: str) -> str:
    if not source_id:
        return ""
    separator = source_id.find(":")
    return source_id[:separator] if separator >= 0 else source_id


async def _run_java_batch(
    project_dir: Path,
    dataset_path: Path,
    documents_dir: Path,
    run_label: str,
) -> List[Dict[str, Any]]:
    retrieval_only = os.getenv("LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY", "true").lower() == "true"
    app_args = " ".join(
        [
            f"--documents-dir {shlex.quote(str(documents_dir))}",
            f"--dataset {shlex.quote(str(dataset_path))}",
            f"--storage-profile {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE', 'in-memory'))}",
            f"--mode {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_QUERY_MODE', 'mix'))}",
            f"--top-k {shlex.quote(os.getenv('EVAL_QUERY_TOP_K', '10'))}",
            f"--chunk-top-k {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K', '10'))}",
            f"--max-hop {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_MAX_HOP', '2'))}",
            f"--path-top-k {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_PATH_TOP_K', '3'))}",
            f"--multi-hop-enabled {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED', 'true'))}",
            f"--retrieval-only {str(retrieval_only).lower()}",
            f"--run-label {shlex.quote(run_label)}",
        ]
    )
    command = f"./gradlew --no-daemon --quiet :lightrag-core:runRagasBatchEval --args={shlex.quote(app_args)}"
    completed = await asyncio.to_thread(
        subprocess.run,
        ["/bin/bash", "-lc", command],
        cwd=project_dir,
        env=os.environ.copy(),
        capture_output=True,
        text=True,
        check=True,
    )
    return _normalize_batch_results(json.loads(completed.stdout.strip()))


def _evaluate_retrieval(test_cases: List[Dict[str, Any]], batch_results: List[Dict[str, Any]]) -> Dict[str, Any]:
    if len(test_cases) != len(batch_results):
        raise SystemExit(f"Java batch runner returned {len(batch_results)} results for {len(test_cases)} test cases")

    evaluated = []
    for test_case, batch_result in zip(test_cases, batch_results, strict=True):
        expected_doc_ids = list(dict.fromkeys(test_case.get("relevant_doc_ids", [])))
        matched_doc_ids = []
        for context in batch_result.get("contexts", []):
            doc_id = _document_id_from_source_id(context.get("sourceId", ""))
            if doc_id and doc_id not in matched_doc_ids:
                matched_doc_ids.append(doc_id)
        expected_set = set(expected_doc_ids)
        matched_set = set(matched_doc_ids)
        overlap = expected_set & matched_set
        raw_hop_doc_ids = test_case.get("hop_doc_ids", [])
        if isinstance(raw_hop_doc_ids, list) and raw_hop_doc_ids:
            all_hops_hit = all(
                isinstance(hop_group, list)
                and any(str(doc_id) in matched_set for doc_id in hop_group)
                for hop_group in raw_hop_doc_ids
            )
        else:
            all_hops_hit = bool(expected_set) and expected_set.issubset(matched_set)
        evaluated.append(
            {
                "question": test_case["question"],
                "expected_doc_ids": expected_doc_ids,
                "matched_doc_ids": matched_doc_ids,
                "top1_hit": bool(matched_doc_ids) and matched_doc_ids[0] in expected_set,
                "top3_hit": any(doc_id in expected_set for doc_id in matched_doc_ids[:3]),
                "all_hops_hit": all_hops_hit,
                "recall": round(len(overlap) / len(expected_set), 4) if expected_set else 0.0,
            }
        )

    return {
        "results": evaluated,
        "summary": {
            "total_cases": len(evaluated),
            "top1_hit_rate": round(sum(1 for item in evaluated if item["top1_hit"]) / len(evaluated), 4) if evaluated else 0.0,
            "top3_hit_rate": round(sum(1 for item in evaluated if item["top3_hit"]) / len(evaluated), 4) if evaluated else 0.0,
            "all_hops_hit_rate": round(sum(1 for item in evaluated if item["all_hops_hit"]) / len(evaluated), 4) if evaluated else 0.0,
            "average_recall": round(sum(item["recall"] for item in evaluated) / len(evaluated), 4) if evaluated else 0.0,
        },
    }


def _compare_with_baseline(current_payload: Dict[str, Any], baseline_payload: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not baseline_payload:
        return None
    current_by_question = {item["question"]: item for item in current_payload["results"]}
    baseline_by_question = {item["question"]: item for item in baseline_payload.get("results", [])}
    shared_questions = sorted(set(current_by_question) & set(baseline_by_question))
    if not shared_questions:
        return {
            "shared_case_count": 0,
            "top1_hit_rate_delta": None,
            "top3_hit_rate_delta": None,
            "all_hops_hit_rate_delta": None,
            "average_recall_delta": None,
        }
    current_subset = [current_by_question[question] for question in shared_questions]
    baseline_subset = [baseline_by_question[question] for question in shared_questions]
    return {
        "shared_case_count": len(shared_questions),
        "top1_hit_rate_delta": round(_hit_rate(current_subset, "top1_hit") - _hit_rate(baseline_subset, "top1_hit"), 4),
        "top3_hit_rate_delta": round(_hit_rate(current_subset, "top3_hit") - _hit_rate(baseline_subset, "top3_hit"), 4),
        "all_hops_hit_rate_delta": round(_hit_rate(current_subset, "all_hops_hit") - _hit_rate(baseline_subset, "all_hops_hit"), 4),
        "average_recall_delta": round(_average_recall(current_subset) - _average_recall(baseline_subset), 4),
    }


def _hit_rate(results: List[Dict[str, Any]], key: str) -> float:
    return sum(1 for item in results if item.get(key)) / len(results) if results else 0.0


def _average_recall(results: List[Dict[str, Any]]) -> float:
    return sum(float(item.get("recall", 0.0)) for item in results) / len(results) if results else 0.0


def _load_baseline_payload(path: Path) -> Optional[Dict[str, Any]]:
    if not path.exists():
        return None
    return json.loads(path.read_text())


def _slugify(value: str) -> str:
    collapsed = "-".join(value.strip().lower().split())
    collapsed = "".join(char if char.isalnum() or char == "-" else "-" for char in collapsed)
    collapsed = collapsed.strip("-")
    return collapsed or "baseline"


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n")


def _write_csv(path: Path, results: List[Dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["question", "expected_doc_ids", "matched_doc_ids", "top1_hit", "top3_hit", "all_hops_hit", "recall"],
        )
        writer.writeheader()
        for result in results:
            writer.writerow(
                {
                    "question": result["question"],
                    "expected_doc_ids": "|".join(result["expected_doc_ids"]),
                    "matched_doc_ids": "|".join(result["matched_doc_ids"]),
                    "top1_hit": str(result["top1_hit"]).lower(),
                    "top3_hit": str(result["top3_hit"]).lower(),
                    "all_hops_hit": str(result["all_hops_hit"]).lower(),
                    "recall": f"{result['recall']:.4f}",
                }
            )


def _write_outputs(
    payload: Dict[str, Any],
    results_dir: Path,
    baselines_dir: Path,
    run_label: str,
    baseline_name: str,
    update_baseline: bool,
) -> None:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    safe_run_label = _slugify(run_label)
    safe_baseline_name = _slugify(baseline_name)
    _write_json(results_dir / f"results_{timestamp}.json", payload)
    _write_csv(results_dir / f"results_{timestamp}.csv", payload["results"])
    _write_json(results_dir / f"latest_{safe_run_label}.json", payload)
    _write_csv(results_dir / f"latest_{safe_run_label}.csv", payload["results"])
    if update_baseline:
        _write_json(baselines_dir / f"{safe_baseline_name}.json", payload)
        _write_csv(baselines_dir / f"{safe_baseline_name}.csv", payload["results"])


def _build_payload(
    dataset_path: Path,
    documents_dir: Path,
    run_label: str,
    baseline_name: str,
    retrieval_results: Dict[str, Any],
    comparison: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    return {
        "metadata": {
            "generated_at": datetime.now().isoformat(),
            "run_label": run_label,
            "baseline_name": baseline_name,
            "dataset_path": str(dataset_path),
            "documents_dir": str(documents_dir),
            "query_mode": os.getenv("LIGHTRAG_JAVA_EVAL_QUERY_MODE", "mix").upper(),
            "top_k": int(os.getenv("EVAL_QUERY_TOP_K", "10")),
            "chunk_top_k": int(os.getenv("LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K", "10")),
            "max_hop": int(os.getenv("LIGHTRAG_JAVA_EVAL_MAX_HOP", "2")),
            "path_top_k": int(os.getenv("LIGHTRAG_JAVA_EVAL_PATH_TOP_K", "3")),
            "multi_hop_enabled": os.getenv("LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED", "true").lower() == "true",
            "retrieval_only": os.getenv("LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY", "true").lower() == "true",
        },
        "summary": {
            **retrieval_results["summary"],
            "shared_case_count": comparison["shared_case_count"] if comparison else 0,
            "top1_hit_rate_delta": comparison["top1_hit_rate_delta"] if comparison else None,
            "top3_hit_rate_delta": comparison["top3_hit_rate_delta"] if comparison else None,
            "all_hops_hit_rate_delta": comparison["all_hops_hit_rate_delta"] if comparison else None,
            "average_recall_delta": comparison["average_recall_delta"] if comparison else None,
            "baseline_updated": False,
        },
        "results": retrieval_results["results"],
    }


async def _main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="evaluation/ragas/sample_dataset.json")
    parser.add_argument("--documents-dir", default="evaluation/ragas/sample_documents")
    parser.add_argument("--run-label", default="retrieval-baseline")
    parser.add_argument("--baseline-name", default="sample-retrieval")
    parser.add_argument("--update-baseline", action="store_true")
    args = parser.parse_args()

    project_dir = Path(__file__).resolve().parents[2]
    load_dotenv(dotenv_path=project_dir / "evaluation" / "ragas" / ".env", override=False)
    _apply_env_fallbacks()
    dataset_path = (project_dir / args.dataset).resolve() if not Path(args.dataset).is_absolute() else Path(args.dataset)
    documents_dir = (project_dir / args.documents_dir).resolve() if not Path(args.documents_dir).is_absolute() else Path(args.documents_dir)
    test_cases = json.loads(dataset_path.read_text()).get("test_cases", [])
    batch_results = await _run_java_batch(project_dir, dataset_path, documents_dir, args.run_label)
    retrieval_results = _evaluate_retrieval(test_cases, batch_results)
    results_dir = project_dir / "evaluation" / "ragas" / "results"
    baselines_dir = project_dir / "evaluation" / "ragas" / "baselines"
    baseline_payload = _load_baseline_payload(baselines_dir / f"{_slugify(args.baseline_name)}.json")
    comparison = _compare_with_baseline(retrieval_results, baseline_payload)
    payload = _build_payload(dataset_path, documents_dir, args.run_label, args.baseline_name, retrieval_results, comparison)
    payload["summary"]["baseline_updated"] = args.update_baseline
    _write_outputs(payload, results_dir, baselines_dir, args.run_label, args.baseline_name, args.update_baseline)
    print(json.dumps(payload["summary"], indent=2))


if __name__ == "__main__":
    asyncio.run(_main())
