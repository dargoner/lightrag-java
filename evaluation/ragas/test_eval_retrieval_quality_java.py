import asyncio
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("eval_retrieval_quality_java.py")


def load_module():
    spec = importlib.util.spec_from_file_location("eval_retrieval_quality_java_under_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class RetrievalMetricsTest(unittest.TestCase):
    def test_normalizes_source_ids_to_document_ids(self):
        module = load_module()

        self.assertEqual(module._document_id_from_source_id("doc-1:chunk-2"), "doc-1")
        self.assertEqual(module._document_id_from_source_id("doc-1"), "doc-1")
        self.assertEqual(module._document_id_from_source_id(""), "")

    def test_evaluates_hit_rates_and_recall(self):
        module = load_module()

        results = module._evaluate_retrieval(
            test_cases=[
                {"question": "q1", "relevant_doc_ids": ["doc-2", "doc-1"], "hop_doc_ids": [["doc-2"], ["doc-1"]]},
                {"question": "q2", "relevant_doc_ids": ["doc-3"], "hop_doc_ids": [["doc-3"]]},
            ],
            batch_results=[
                {"contexts": [{"sourceId": "doc-2:chunk-1"}, {"sourceId": "doc-1:chunk-4"}]},
                {"contexts": [{"sourceId": "doc-8:chunk-1"}, {"sourceId": "doc-3:chunk-1"}]},
            ],
        )

        self.assertEqual(results["results"][0]["matched_doc_ids"], ["doc-2", "doc-1"])
        self.assertTrue(results["results"][0]["top1_hit"])
        self.assertTrue(results["results"][0]["top3_hit"])
        self.assertTrue(results["results"][0]["all_hops_hit"])
        self.assertAlmostEqual(results["results"][0]["recall"], 1.0, places=4)
        self.assertFalse(results["results"][1]["top1_hit"])
        self.assertTrue(results["results"][1]["top3_hit"])
        self.assertTrue(results["results"][1]["all_hops_hit"])
        self.assertAlmostEqual(results["summary"]["top1_hit_rate"], 0.5, places=4)
        self.assertAlmostEqual(results["summary"]["top3_hit_rate"], 1.0, places=4)
        self.assertAlmostEqual(results["summary"]["all_hops_hit_rate"], 1.0, places=4)
        self.assertAlmostEqual(results["summary"]["average_recall"], 1.0, places=4)

    def test_compare_with_baseline_reports_delta(self):
        module = load_module()

        comparison = module._compare_with_baseline(
            {
                "results": [
                    {"question": "q1", "top1_hit": True, "top3_hit": True, "all_hops_hit": True, "recall": 1.0},
                    {"question": "q2", "top1_hit": False, "top3_hit": True, "all_hops_hit": False, "recall": 0.5},
                ],
                "summary": {"top1_hit_rate": 0.5, "top3_hit_rate": 1.0, "all_hops_hit_rate": 0.5, "average_recall": 0.75},
            },
            {
                "results": [
                    {"question": "q1", "top1_hit": False, "top3_hit": True, "all_hops_hit": False, "recall": 0.5},
                    {"question": "q2", "top1_hit": False, "top3_hit": False, "all_hops_hit": False, "recall": 0.0},
                ],
                "summary": {"top1_hit_rate": 0.0, "top3_hit_rate": 0.5, "all_hops_hit_rate": 0.0, "average_recall": 0.25},
            },
        )

        self.assertEqual(comparison["shared_case_count"], 2)
        self.assertAlmostEqual(comparison["top1_hit_rate_delta"], 0.5, places=4)
        self.assertAlmostEqual(comparison["top3_hit_rate_delta"], 0.5, places=4)
        self.assertAlmostEqual(comparison["all_hops_hit_rate_delta"], 0.5, places=4)
        self.assertAlmostEqual(comparison["average_recall_delta"], 0.5, places=4)

    def test_env_fallbacks_map_legacy_binding_keys(self):
        module = load_module()

        with mock.patch.dict(
            os.environ,
            {
                "LLM_BINDING_API_KEY": "chat-key",
                "LLM_BINDING_HOST": "https://llm.example/v1/",
                "LLM_MODEL": "qwen-test",
                "EMBEDDING_BINDING_API_KEY": "embedding-key",
                "EMBEDDING_BINDING_HOST": "https://embedding.example/v1/",
                "EMBEDDING_MODEL": "bge-test",
            },
            clear=True,
        ):
            module._apply_env_fallbacks()

            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_CHAT_API_KEY"], "chat-key")
            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL"], "https://llm.example/v1/")
            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_CHAT_MODEL"], "qwen-test")
            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY"], "embedding-key")
            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL"], "https://embedding.example/v1/")
            self.assertEqual(os.environ["LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL"], "bge-test")


class RetrievalOutputTest(unittest.TestCase):
    def test_writes_latest_and_baseline_payloads(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            results_dir = root / "results"
            baselines_dir = root / "baselines"
            payload = {
                "metadata": {
                    "run_label": "candidate",
                    "baseline_name": "sample",
                    "max_hop": 3,
                    "path_top_k": 5,
                    "multi_hop_enabled": True,
                },
                "summary": {"top1_hit_rate": 0.5, "top3_hit_rate": 1.0, "all_hops_hit_rate": 1.0, "average_recall": 0.75},
                "results": [
                    {
                        "question": "q1",
                        "expected_doc_ids": ["doc-1"],
                        "matched_doc_ids": ["doc-1"],
                        "top1_hit": True,
                        "top3_hit": True,
                        "all_hops_hit": True,
                        "recall": 1.0,
                    }
                ],
            }

            module._write_outputs(
                payload=payload,
                results_dir=results_dir,
                baselines_dir=baselines_dir,
                run_label="candidate",
                baseline_name="sample",
                update_baseline=True,
            )

            latest_payload = json.loads((results_dir / "latest_candidate.json").read_text())
            baseline_payload = json.loads((baselines_dir / "sample.json").read_text())
            self.assertEqual(latest_payload["summary"]["top1_hit_rate"], 0.5)
            self.assertEqual(latest_payload["metadata"]["max_hop"], 3)
            self.assertEqual(baseline_payload["summary"]["average_recall"], 0.75)
            self.assertTrue((results_dir / "latest_candidate.csv").exists())
            self.assertTrue((baselines_dir / "sample.csv").exists())

    def test_run_java_batch_enables_retrieval_only_mode(self):
        module = load_module()
        completed = mock.Mock()
        completed.stdout = json.dumps({"results": []})

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            dataset = root / "dataset.json"
            documents = root / "documents"
            documents.mkdir()
            dataset.write_text(json.dumps({"test_cases": []}))

            with mock.patch.object(module.asyncio, "to_thread", new=mock.AsyncMock(return_value=completed)) as to_thread:
                with mock.patch.dict(
                    os.environ,
                    {
                        "LIGHTRAG_JAVA_EVAL_MAX_HOP": "4",
                        "LIGHTRAG_JAVA_EVAL_PATH_TOP_K": "6",
                        "LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED": "false",
                    },
                    clear=False,
                ):
                    results = asyncio.run(module._run_java_batch(root, dataset, documents, "candidate"))

        self.assertEqual(results, [])
        invoked = to_thread.await_args.args[1]
        self.assertIn("--retrieval-only true", invoked[2])
        self.assertIn("--max-hop 4", invoked[2])
        self.assertIn("--path-top-k 6", invoked[2])
        self.assertIn("--multi-hop-enabled false", invoked[2])

    def test_build_payload_includes_multi_hop_metadata_and_delta(self):
        module = load_module()

        with mock.patch.dict(
            os.environ,
            {
                "LIGHTRAG_JAVA_EVAL_QUERY_MODE": "mix",
                "EVAL_QUERY_TOP_K": "10",
                "LIGHTRAG_JAVA_EVAL_CHUNK_TOP_K": "12",
                "LIGHTRAG_JAVA_EVAL_MAX_HOP": "4",
                "LIGHTRAG_JAVA_EVAL_PATH_TOP_K": "6",
                "LIGHTRAG_JAVA_EVAL_MULTI_HOP_ENABLED": "false",
            },
            clear=False,
        ):
            payload = module._build_payload(
                dataset_path=Path("/tmp/dataset.json"),
                documents_dir=Path("/tmp/documents"),
                run_label="candidate",
                baseline_name="baseline",
                retrieval_results={
                    "summary": {
                        "total_cases": 1,
                        "top1_hit_rate": 1.0,
                        "top3_hit_rate": 1.0,
                        "all_hops_hit_rate": 1.0,
                        "average_recall": 1.0,
                    },
                    "results": [],
                },
                comparison={
                    "shared_case_count": 1,
                    "top1_hit_rate_delta": 0.1,
                    "top3_hit_rate_delta": 0.2,
                    "all_hops_hit_rate_delta": 0.3,
                    "average_recall_delta": 0.4,
                },
            )

        self.assertEqual(payload["metadata"]["max_hop"], 4)
        self.assertEqual(payload["metadata"]["path_top_k"], 6)
        self.assertFalse(payload["metadata"]["multi_hop_enabled"])
        self.assertAlmostEqual(payload["summary"]["all_hops_hit_rate_delta"], 0.3, places=4)


if __name__ == "__main__":
    unittest.main()
