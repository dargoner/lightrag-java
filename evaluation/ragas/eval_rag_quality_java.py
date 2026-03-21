#!/usr/bin/env python3
"""
Upstream-style RAGAS evaluation for lightrag-java.

This keeps the upstream dataset + metrics approach, but replaces the LightRAG
server HTTP call with a local Java SDK query runner.
"""

import argparse
import asyncio
import csv
import json
import math
import os
import shlex
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

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
        self.results_dir.mkdir(parents=True, exist_ok=True)
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
        app_args = " ".join(
            [
                f"--documents-dir {shlex.quote(str(self.documents_dir))}",
                f"--dataset {shlex.quote(str(self.dataset_path))}",
                f"--storage-profile {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_STORAGE_PROFILE', 'in-memory'))}",
                f"--mode {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_QUERY_MODE', 'mix'))}",
                f"--top-k {shlex.quote(os.getenv('EVAL_QUERY_TOP_K', '10'))}",
                f"--chunk-top-k {shlex.quote(os.getenv('LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K', '10'))}",
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
            "metrics": metrics,
            "ragas_score": round(sum(valid_metrics) / len(valid_metrics), 4) if valid_metrics else 0,
            "timestamp": datetime.now().isoformat(),
        }

    async def run(self):
        rag_responses = await self.generate_rag_responses()
        if len(rag_responses) != len(self.test_cases):
            raise SystemExit(
                f"Java batch runner returned {len(rag_responses)} results for {len(self.test_cases)} test cases"
            )
        results = []
        for index, (test_case, rag_response) in enumerate(zip(self.test_cases, rag_responses, strict=True), start=1):
            results.append(await self.evaluate_single_case(index, test_case, rag_response))
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        json_path = self.results_dir / f"results_{timestamp}.json"
        csv_path = self.results_dir / f"results_{timestamp}.csv"
        json_path.write_text(json.dumps(results, indent=2))
        with csv_path.open("w", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=["test_number", "question", "faithfulness", "answer_relevance", "context_recall", "context_precision", "ragas_score", "timestamp"])
            writer.writeheader()
            for result in results:
                writer.writerow(
                    {
                        "test_number": result["test_number"],
                        "question": result["question"],
                        "faithfulness": result["metrics"]["faithfulness"],
                        "answer_relevance": result["metrics"]["answer_relevance"],
                        "context_recall": result["metrics"]["context_recall"],
                        "context_precision": result["metrics"]["context_precision"],
                        "ragas_score": result["ragas_score"],
                        "timestamp": result["timestamp"],
                    }
                )
        return results, json_path, csv_path


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
    args = parser.parse_args()
    project_dir = Path(__file__).resolve().parents[2]
    evaluator = JavaRagasEvaluator(
        dataset_path=(project_dir / args.dataset).resolve(),
        documents_dir=(project_dir / args.documents_dir).resolve(),
        project_dir=project_dir,
    )
    results, json_path, csv_path = await evaluator.run()
    average = sum(result["ragas_score"] for result in results) / len(results) if results else 0
    print(f"Average RAGAS score: {average:.4f}")
    print(f"JSON results: {json_path}")
    print(f"CSV results: {csv_path}")


if __name__ == "__main__":
    asyncio.run(_main())
