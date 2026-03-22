import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("prepare_beir_dataset.py")


def load_module():
    spec = importlib.util.spec_from_file_location("prepare_beir_dataset_under_test", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class PrepareBeirDatasetTest(unittest.TestCase):
    def test_converts_beir_raw_files_to_ragas_dataset_and_documents(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "raw"
            output_dir = root / "prepared"
            self._write_fixture(source_dir)

            result = module.prepare_beir_dataset(
                dataset_name="scifact",
                split="test",
                source_dir=source_dir,
                output_dir=output_dir,
                max_cases=None,
                include_all_documents=True,
            )

            payload = json.loads((output_dir / "dataset.json").read_text())
            self.assertEqual(result["case_count"], 2)
            self.assertEqual(result["document_count"], 3)
            self.assertEqual(len(payload["test_cases"]), 2)
            self.assertEqual(payload["test_cases"][0]["query_id"], "q1")
            self.assertEqual(payload["test_cases"][0]["relevant_doc_ids"], ["doc-2", "doc-1"])
            self.assertIn("Doc One", payload["test_cases"][0]["ground_truth"])
            self.assertIn("Doc Two", payload["test_cases"][0]["ground_truth"])
            self.assertEqual(payload["test_cases"][0]["source_dataset"], "beir/scifact")
            self.assertTrue((output_dir / "documents" / "doc-1.md").exists())
            self.assertTrue((output_dir / "documents" / "doc-2.md").exists())
            self.assertTrue((output_dir / "documents" / "doc-3.md").exists())

        self.assertIn("dataset_path", result)
        self.assertIn("documents_dir", result)

    def test_ignores_non_positive_and_missing_qrels(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "raw"
            output_dir = root / "prepared"
            self._write_fixture(source_dir)
            qrels_path = source_dir / "qrels" / "test.tsv"
            qrels_path.write_text(
                "query-id\tcorpus-id\tscore\n"
                "q1\tdoc-1\t1\n"
                "q1\tdoc-missing\t1\n"
                "q2\tdoc-3\t0\n"
                "q-missing\tdoc-2\t1\n"
            )

            module.prepare_beir_dataset(
                dataset_name="scifact",
                split="test",
                source_dir=source_dir,
                output_dir=output_dir,
                max_cases=None,
                include_all_documents=True,
            )

            payload = json.loads((output_dir / "dataset.json").read_text())
            self.assertEqual(len(payload["test_cases"]), 1)
            self.assertEqual(payload["test_cases"][0]["query_id"], "q1")
            self.assertEqual(payload["test_cases"][0]["relevant_doc_ids"], ["doc-1"])

    def test_respects_max_cases_with_stable_query_order(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "raw"
            output_dir = root / "prepared"
            self._write_fixture(source_dir)

            module.prepare_beir_dataset(
                dataset_name="scifact",
                split="test",
                source_dir=source_dir,
                output_dir=output_dir,
                max_cases=1,
                include_all_documents=True,
            )

            payload = json.loads((output_dir / "dataset.json").read_text())
            self.assertEqual([case["query_id"] for case in payload["test_cases"]], ["q1"])

    def test_can_export_only_relevant_documents_for_selected_cases(self):
        module = load_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_dir = root / "raw"
            output_dir = root / "prepared"
            self._write_fixture(source_dir)

            result = module.prepare_beir_dataset(
                dataset_name="scifact",
                split="test",
                source_dir=source_dir,
                output_dir=output_dir,
                max_cases=1,
                include_all_documents=False,
            )

            documents = sorted(path.name for path in (output_dir / "documents").glob("*.md"))
            self.assertEqual(documents, ["doc-1.md", "doc-2.md"])
            self.assertEqual(result["document_count"], 2)

    @staticmethod
    def _write_fixture(source_dir: Path):
        (source_dir / "qrels").mkdir(parents=True)
        (source_dir / "corpus.jsonl").write_text(
            "\n".join(
                [
                    json.dumps({"_id": "doc-1", "title": "Doc One", "text": "Alpha evidence."}),
                    json.dumps({"_id": "doc-2", "title": "Doc Two", "text": "Beta evidence."}),
                    json.dumps({"_id": "doc-3", "title": "Doc Three", "text": "Gamma evidence."}),
                ]
            )
            + "\n"
        )
        (source_dir / "queries.jsonl").write_text(
            "\n".join(
                [
                    json.dumps({"_id": "q2", "text": "What supports gamma?"}),
                    json.dumps({"_id": "q1", "text": "What supports alpha and beta?"}),
                ]
            )
            + "\n"
        )
        (source_dir / "qrels" / "test.tsv").write_text(
            "query-id\tcorpus-id\tscore\n"
            "q1\tdoc-1\t1\n"
            "q1\tdoc-2\t2\n"
            "q2\tdoc-3\t1\n"
        )


if __name__ == "__main__":
    unittest.main()
