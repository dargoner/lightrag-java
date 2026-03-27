#!/usr/bin/env python3
"""
Upstream-style RAGAS evaluation for lightrag-java.

This keeps the upstream dataset + metrics approach, but replaces the LightRAG
server HTTP call with a local Java SDK query runner.
"""

import argparse
import asyncio
import csv
import hashlib
import json
import math
import os
import shlex
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from dotenv import load_dotenv

try:
    from datasets import Dataset
    from ragas import evaluate
    from ragas.metrics import AnswerRelevancy, ContextPrecision, ContextRecall, Faithfulness
    from ragas.llms import LangchainLLMWrapper
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
except ImportError as exc:
    raise SystemExit(
        "Missing evaluation dependencies. Install them with:\n"
        "  python3 -m pip install -r evaluation/ragas/requirements.txt\n"
        f"Original error: {exc}"
    )


def _is_nan(value: Any) -> bool:
    return isinstance(value, float) and math.isnan(value)


def _normalize_contexts(raw_contexts: Any) -> List[str]:
    if not isinstance(raw_contexts, list):
        return []
    normalized = []
    for context in raw_contexts:
        if isinstance(context, str):
            text = context.strip()
        elif isinstance(context, dict):
            text = str(context.get("text", "")).strip()
        else:
            text = ""
        if text:
            normalized.append(text)
    return normalized


def _normalize_batch_results(payload: Any) -> List[Dict[str, Any]]:
    raw_results = payload if isinstance(payload, list) else payload.get("results") if isinstance(payload, dict) else None
    if not isinstance(raw_results, list):
        raise SystemExit("Java batch runner returned an unexpected JSON shape")

    normalized_results = []
    for item in raw_results:
        if not isinstance(item, dict):
            raise SystemExit("Java batch runner returned a non-object result entry")
        normalized_item = dict(item)
        normalized_item["contexts"] = _normalize_contexts(item.get("contexts", []))
        normalized_results.append(normalized_item)
    return normalized_results


class JavaRagasEvaluator:
    def __init__(self, dataset_path: Path, documents_dir: Path, project_dir: Path):
        load_dotenv(dotenv_path=project_dir / "evaluation" / "ragas" / ".env", override=False)
        self.dataset_path = dataset_path
        self.documents_dir = documents_dir
        self.project_dir = project_dir
        self.results_dir = project_dir / "evaluation" / "ragas" / "results"
        self.baselines_dir = project_dir / "evaluation" / "ragas" / "baselines"
        self.results_dir.mkdir(parents=True, exist_ok=True)
        self.baselines_dir.mkdir(parents=True, exist_ok=True)
        self.test_cases = json.loads(dataset_path.read_text()).get("test_cases", [])
        self.eval_llm = self._build_eval_llm()
        self.eval_embeddings = self._build_eval_embeddings()

    def _build_eval_llm(self):
        llm = ChatOpenAI(
            model=os.getenv("EVAL_LLM_MODEL", "gpt-4o-mini"),
            api_key=_required_env("EVAL_LLM_BINDING_API_KEY", "OPENAI_API_KEY"),
            base_url=os.getenv("EVAL_LLM_BINDING_HOST", "https://api.openai.com/v1/"),
            max_retries=int(os.getenv("EVAL_LLM_MAX_RETRIES", "5")),
            request_timeout=int(os.getenv("EVAL_LLM_TIMEOUT", "180")),
        )
        return LangchainLLMWrapper(langchain_llm=llm, bypass_n=True)

    def _build_eval_embeddings(self):
        return OpenAIEmbeddings(
            model=os.getenv("EVAL_EMBEDDING_MODEL", "text-embedding-3-large"),
            api_key=_required_env(
                "EVAL_EMBEDDING_BINDING_API_KEY",
                "EVAL_LLM_BINDING_API_KEY",
                "OPENAI_API_KEY",
            ),
            base_url=os.getenv(
                "EVAL_EMBEDDING_BINDING_HOST",
                os.getenv("EVAL_LLM_BINDING_HOST", "https://api.openai.com/v1/"),
            ),
        )

    async def generate_rag_responses(self) -> List[Dict[str, Any]]:
        run_label = os.getenv("LIGHTRAG_JAVA_EVAL_RUN_LABEL", "baseline")
        app_args = " ".join(
            [
                f"--documents-dir {shlex.quote(str(self.documents_dir))}",
                f"--dataset {shlex.quote(str(self.dataset_path))}",
                f"--storage-profile {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE', 'in-memory'))}",
                f"--mode {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_QUERY_MODE', 'mix'))}",
                f"--top-k {shlex.quote(os.getenv('EVAL_QUERY_TOP_K', '10'))}",
                f"--chunk-top-k {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K', '10'))}",
                f"--max-hop {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_MAX_HOP', '2'))}",
                f"--path-top-k {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_PATH_TOP_K', '3'))}",
                f"--multi-hop-enabled {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED', 'true'))}",
                f"--retrieval-only {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY', 'false'))}",
                f"--run-label {shlex.quote(run_label)}",
            ]
        )
        command = f"./gradlew --no-daemon --quiet :lightrag-core:runRagasBatchEval --args={shlex.quote(app_args)}"
        completed = await asyncio.to_thread(
            subprocess.run,
            ["/bin/bash", "-lc", command],
            cwd=self.project_dir,
            env=os.environ.copy(),
            capture_output=True,
            text=True,
            check=True,
        )
        return _normalize_batch_results(json.loads(completed.stdout.strip()))

    async def evaluate_single_case(self, idx: int, test_case: Dict[str, str], rag_response: Dict[str, Any]) -> Dict[str, Any]:
        question = test_case["question"]
        ground_truth = test_case["ground_truth"]
        dataset = Dataset.from_dict(
            {
                "question": [question],
                "answer": [rag_response["answer"]],
                "contexts": [rag_response["contexts"]],
                "ground_truth": [ground_truth],
            }
        )
        eval_results = evaluate(
            dataset=dataset,
            metrics=[Faithfulness(), AnswerRelevancy(), ContextRecall(), ContextPrecision()],
            llm=self.eval_llm,
            embeddings=self.eval_embeddings,
        )
        row = eval_results.to_pandas().iloc[0]
        metrics = {
            "faithfulness": float(row.get("faithfulness", 0)),
            "answer_relevance": float(row.get("answer_relevancy", 0)),
            "context_recall": float(row.get("context_recall", 0)),
            "context_precision": float(row.get("context_precision", 0)),
        }
        valid_metrics = [value for value in metrics.values() if not _is_nan(value)]
        return {
            "test_number": idx,
            "question": question,
            "ground_truth": ground_truth,
            "metrics": metrics,
            "ragas_score": round(sum(valid_metrics) / len(valid_metrics), 4) if valid_metrics else 0,
            "timestamp": datetime.now().isoformat(),
        }

    async def run(
        self,
        run_label: str,
        baseline_name: str,
        update_baseline: bool,
        max_average_regression: float,
    ):
        rag_responses = await self.generate_rag_responses()
        if len(rag_responses) != len(self.test_cases):
            raise SystemExit(
                f"Java batch runner returned {len(rag_responses)} results for {len(self.test_cases)} test cases"
            )
        results = []
        for index, (test_case, rag_response) in enumerate(zip(self.test_cases, rag_responses, strict=True), start=1):
            results.append(await self.evaluate_single_case(index, test_case, rag_response))
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        safe_run_label = _slugify(run_label)
        json_path = self.results_dir / f"results_{timestamp}.json"
        csv_path = self.results_dir / f"results_{timestamp}.csv"
        latest_json_path = self.results_dir / f"latest_{safe_run_label}.json"
        latest_csv_path = self.results_dir / f"latest_{safe_run_label}.csv"
        baseline_json_path = self.baselines_dir / f"{_slugify(baseline_name)}.json"
        baseline_csv_path = self.baselines_dir / f"{_slugify(baseline_name)}.csv"

        baseline_payload = _load_baseline_payload(baseline_json_path)
        comparison = _compare_with_baseline(results, baseline_payload)
        payload = {
            "metadata": _build_run_metadata(
                run_label=run_label,
                baseline_name=baseline_name,
                dataset_path=self.dataset_path,
                documents_dir=self.documents_dir,
            ),
            "summary": _build_summary(
                results=results,
                comparison=comparison,
                max_average_regression=max_average_regression,
                baseline_updated=update_baseline,
            ),
            "results": results,
        }

        _write_json(json_path, payload)
        _write_json(latest_json_path, payload)
        _write_csv(csv_path, results)
        _write_csv(latest_csv_path, results)

        if update_baseline:
            _write_json(baseline_json_path, payload)
            _write_csv(baseline_csv_path, results)

        if payload["summary"]["regressed"]:
            raise SystemExit(
                "Average RAGAS score regressed by "
                f"{payload['summary']['average_delta']:.4f}, threshold={max_average_regression:.4f}"
            )

        return payload, json_path, csv_path, latest_json_path, baseline_json_path


def _required_env(*keys: str) -> str:
    for key in keys:
        value = os.getenv(key)
        if value:
            return value
    raise SystemExit(f"Missing required environment variable. Checked: {', '.join(keys)}")


async def _main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--dataset",
        default="evaluation/ragas/sample_dataset.json",
    )
    parser.add_argument(
        "--documents-dir",
        default="evaluation/ragas/sample_documents",
    )
    parser.add_argument(
        "--run-label",
        default=os.getenv("LIGHTRAG_JAVA_EVAL_RUN_LABEL", "baseline"),
    )
    parser.add_argument(
        "--baseline-name",
        default="sample-default",
    )
    parser.add_argument(
        "--update-baseline",
        action="store_true",
    )
    parser.add_argument(
        "--max-average-regression",
        type=float,
        default=0.02,
    )
    args = parser.parse_args()
    project_dir = Path(__file__).resolve().parents[2]
    os.environ["LIGHTRAG_JAVA_EVAL_RUN_LABEL"] = args.run_label
    evaluator = JavaRagasEvaluator(
        dataset_path=(project_dir / args.dataset).resolve(),
        documents_dir=(project_dir / args.documents_dir).resolve(),
        project_dir=project_dir,
    )
    payload, json_path, csv_path, latest_json_path, baseline_json_path = await evaluator.run(
        run_label=args.run_label,
        baseline_name=args.baseline_name,
        update_baseline=args.update_baseline,
        max_average_regression=args.max_average_regression,
    )
    print(f"Average RAGAS score: {payload['summary']['average_ragas_score']:.4f}")
    if payload["summary"]["baseline_average_ragas_score"] is not None:
        print(f"Baseline average RAGAS score: {payload['summary']['baseline_average_ragas_score']:.4f}")
        print(f"Average delta: {payload['summary']['average_delta']:.4f}")
    print(f"JSON results: {json_path}")
    print(f"CSV results: {csv_path}")
    print(f"Latest JSON: {latest_json_path}")
    print(f"Baseline JSON: {baseline_json_path}")

def _slugify(value: str) -> str:
    stripped = value.strip().lower()
    normalized = "".join(char if char.isalnum() else "-" for char in stripped)
    collapsed = "-".join(part for part in normalized.split("-") if part)
    return collapsed or "baseline"


def _dataset_digest(dataset_path: Path) -> str:
    return hashlib.sha256(dataset_path.read_bytes()).hexdigest()[:12]


def _build_run_metadata(
    run_label: str,
    baseline_name: str,
    dataset_path: Path,
    documents_dir: Path,
) -> Dict[str, Any]:
    return {
        "generated_at": datetime.now().isoformat(),
        "run_label": run_label,
        "baseline_name": baseline_name,
        "dataset_path": str(dataset_path),
        "dataset_digest": _dataset_digest(dataset_path),
        "documents_dir": str(documents_dir),
        "storage_profile": os.getenv("LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE", "in-memory"),
        "query_mode": os.getenv("LIGHTRAG_JAVA_EVAL_QUERY_MODE", "mix").upper(),
        "top_k": int(os.getenv("EVAL_QUERY_TOP_K", "10")),
        "chunk_top_k": int(os.getenv("LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K", "10")),
        "max_hop": int(os.getenv("LIGHTRAG_JAVA_EVAL_MAX_HOP", "2")),
        "path_top_k": int(os.getenv("LIGHTRAG_JAVA_EVAL_PATH_TOP_K", "3")),
        "multi_hop_enabled": os.getenv("LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED", "true").lower() == "true",
        "retrieval_only": os.getenv("LIGHTRAG_JAVA_EVAL_RETRIEVAL_ONLY", "false").lower() == "true",
        "chat_model": os.getenv("LIGHTRAG_JAVA_EVAL_CHAT_MODEL", "gpt-4o-mini"),
        "embedding_model": os.getenv("LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"),
    }


def _average_score(results: List[Dict[str, Any]]) -> float:
    if not results:
        return 0.0
    return round(sum(float(result.get("ragas_score", 0.0)) for result in results) / len(results), 4)


def _load_baseline_payload(path: Path) -> Optional[Dict[str, Any]]:
    if not path.exists():
        return None
    return json.loads(path.read_text())


def _compare_with_baseline(
    results: List[Dict[str, Any]],
    baseline_payload: Optional[Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    if not baseline_payload:
        return None

    baseline_results = baseline_payload.get("results", [])
    current_by_question = {item["question"]: item for item in results}
    baseline_by_question = {item["question"]: item for item in baseline_results}
    shared_questions = sorted(set(current_by_question) & set(baseline_by_question))
    if not shared_questions:
        return None

    per_case = []
    for question in shared_questions:
        current_score = float(current_by_question[question]["ragas_score"])
        baseline_score = float(baseline_by_question[question]["ragas_score"])
        per_case.append(
            {
                "question": question,
                "current_score": current_score,
                "baseline_score": baseline_score,
                "delta": round(current_score - baseline_score, 4),
            }
        )

    current_average = _average_score([current_by_question[question] for question in shared_questions])
    baseline_average = _average_score([baseline_by_question[question] for question in shared_questions])
    return {
        "current_average": current_average,
        "baseline_average": baseline_average,
        "average_delta": round(current_average - baseline_average, 4),
        "shared_case_count": len(shared_questions),
        "per_case": per_case,
    }


def _build_summary(
    results: List[Dict[str, Any]],
    comparison: Optional[Dict[str, Any]],
    max_average_regression: float,
    baseline_updated: bool,
) -> Dict[str, Any]:
    average_ragas_score = _average_score(results)
    baseline_average = comparison["baseline_average"] if comparison else None
    average_delta = comparison["average_delta"] if comparison else None
    regressed = bool(
        comparison
        and not baseline_updated
        and comparison["average_delta"] < 0
        and abs(comparison["average_delta"]) > max_average_regression
    )
    return {
        "total_cases": len(results),
        "average_ragas_score": average_ragas_score,
        "baseline_average_ragas_score": baseline_average,
        "average_delta": average_delta,
        "shared_case_count": comparison["shared_case_count"] if comparison else 0,
        "max_average_regression": max_average_regression,
        "baseline_updated": baseline_updated,
        "regressed": regressed,
    }


def _write_json(path: Path, payload: Dict[str, Any]) -> None:
    path.write_text(json.dumps(payload, indent=2))


def _write_csv(path: Path, results: List[Dict[str, Any]]) -> None:
    with path.open("w", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=[
                "test_number",
                "question",
                "ground_truth",
                "faithfulness",
                "answer_relevance",
                "context_recall",
                "context_precision",
                "ragas_score",
                "timestamp",
            ],
        )
        writer.writeheader()
        for result in results:
            writer.writerow(
                {
                    "test_number": result["test_number"],
                    "question": result["question"],
                    "ground_truth": result["ground_truth"],
                    "faithfulness": result["metrics"]["faithfulness"],
                    "answer_relevance": result["metrics"]["answer_relevance"],
                    "context_recall": result["metrics"]["context_recall"],
                    "context_precision": result["metrics"]["context_precision"],
                    "ragas_score": result["ragas_score"],
                    "timestamp": result["timestamp"],
                }
            )


if __name__ == "__main__":
    asyncio.run(_main())
