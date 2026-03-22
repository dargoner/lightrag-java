#!/usr/bin/env python3
"""
Prepare a public BEIR dataset for the lightrag-java RAGAS workflow.

The prepared output matches the repository's existing evaluator inputs:
- dataset.json with test_cases[].question and ground_truth
- documents/*.md corpus files
"""

import argparse
import csv
import json
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple


SUPPORTED_DOWNLOADS = {
    "scifact": "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/scifact.zip",
}


def prepare_beir_dataset(
    dataset_name: str,
    split: str,
    source_dir: Optional[Path],
    output_dir: Path,
    max_cases: Optional[int],
    include_all_documents: bool,
) -> Dict[str, object]:
    if source_dir is None:
        with tempfile.TemporaryDirectory(prefix=f"beir-{dataset_name}-") as temp_dir:
            prepared_source_dir = _download_dataset(dataset_name, Path(temp_dir))
            return _prepare_from_source(
                dataset_name=dataset_name,
                split=split,
                source_dir=prepared_source_dir,
                output_dir=output_dir,
                max_cases=max_cases,
                include_all_documents=include_all_documents,
            )
    return _prepare_from_source(
        dataset_name=dataset_name,
        split=split,
        source_dir=source_dir,
        output_dir=output_dir,
        max_cases=max_cases,
        include_all_documents=include_all_documents,
    )


def _prepare_from_source(
    dataset_name: str,
    split: str,
    source_dir: Path,
    output_dir: Path,
    max_cases: Optional[int],
    include_all_documents: bool,
) -> Dict[str, object]:
    corpus = _load_jsonl_records(source_dir / "corpus.jsonl")
    queries = _load_jsonl_records(source_dir / "queries.jsonl")
    qrels = _load_qrels(source_dir / "qrels" / f"{split}.tsv")

    payload = _build_dataset_payload(
        corpus=corpus,
        queries=queries,
        qrels=qrels,
        dataset_name=dataset_name,
        split=split,
        max_cases=max_cases,
    )
    selected_doc_ids = _selected_doc_ids(payload, corpus, include_all_documents)
    documents_dir = output_dir / "documents"
    dataset_path = output_dir / "dataset.json"
    output_dir.mkdir(parents=True, exist_ok=True)
    documents_dir.mkdir(parents=True, exist_ok=True)

    dataset_path.write_text(json.dumps(payload, indent=2) + "\n")
    for existing in documents_dir.glob("*.md"):
        existing.unlink()
    for doc_id in sorted(selected_doc_ids):
        document = corpus[doc_id]
        (documents_dir / f"{doc_id}.md").write_text(_render_document(document))

    return {
        "dataset_path": str(dataset_path),
        "documents_dir": str(documents_dir),
        "case_count": len(payload["test_cases"]),
        "document_count": len(selected_doc_ids),
        "source_dataset": f"beir/{dataset_name}",
        "split": split,
    }


def _selected_doc_ids(
    payload: Dict[str, object],
    corpus: Dict[str, Dict[str, str]],
    include_all_documents: bool,
) -> List[str]:
    if include_all_documents:
        return sorted(corpus)
    selected = set()
    for case in payload["test_cases"]:
        selected.update(case["relevant_doc_ids"])
    return sorted(selected)


def _download_dataset(dataset_name: str, temp_dir: Path) -> Path:
    if dataset_name not in SUPPORTED_DOWNLOADS:
        raise SystemExit(
            f"Unsupported dataset '{dataset_name}'. Supported datasets: {', '.join(sorted(SUPPORTED_DOWNLOADS))}"
        )
    zip_path = temp_dir / f"{dataset_name}.zip"
    urllib.request.urlretrieve(SUPPORTED_DOWNLOADS[dataset_name], zip_path)
    with zipfile.ZipFile(zip_path) as archive:
        archive.extractall(temp_dir)
    candidate = temp_dir / dataset_name
    return candidate if candidate.exists() else temp_dir


def _build_dataset_payload(
    corpus: Dict[str, Dict[str, str]],
    queries: Dict[str, Dict[str, str]],
    qrels: Dict[str, List[Tuple[str, int]]],
    dataset_name: str,
    split: str,
    max_cases: Optional[int],
) -> Dict[str, object]:
    cases = []
    for query_id in sorted(qrels):
        query = queries.get(query_id)
        if query is None:
            continue
        relevant_docs = [
            doc_id
            for doc_id, score in sorted(qrels[query_id], key=lambda item: (-item[1], item[0]))
            if score > 0 and doc_id in corpus
        ]
        if not relevant_docs:
            continue
        ground_truth = "\n\n".join(_ground_truth_sections(doc_id, corpus[doc_id]) for doc_id in relevant_docs)
        cases.append(
            {
                "question": query["text"],
                "ground_truth": ground_truth,
                "query_id": query_id,
                "relevant_doc_ids": relevant_docs,
                "source_dataset": f"beir/{dataset_name}",
                "split": split,
            }
        )
        if max_cases is not None and len(cases) >= max_cases:
            break
    return {"test_cases": cases}


def _ground_truth_sections(doc_id: str, document: Dict[str, str]) -> str:
    title = document["title"] or doc_id
    text = document["text"]
    return f"Document: {title}\n{text}".strip()


def _render_document(document: Dict[str, str]) -> str:
    title = document["title"] or "Untitled"
    text = document["text"].strip()
    return f"# {title}\n\n{text}\n"


def _load_jsonl_records(path: Path) -> Dict[str, Dict[str, str]]:
    records = {}
    with path.open() as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line:
                continue
            item = json.loads(line)
            records[item["_id"]] = {
                "title": str(item.get("title", "")).strip(),
                "text": str(item.get("text", "")).strip(),
            }
    return records


def _load_qrels(path: Path) -> Dict[str, List[Tuple[str, int]]]:
    qrels: Dict[str, List[Tuple[str, int]]] = {}
    with path.open() as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        for row in reader:
            query_id = str(row.get("query-id", "")).strip()
            doc_id = str(row.get("corpus-id", "")).strip()
            score_raw = str(row.get("score", "0")).strip()
            if not query_id or not doc_id:
                continue
            try:
                score = int(score_raw)
            except ValueError:
                continue
            qrels.setdefault(query_id, []).append((doc_id, score))
    return qrels


def _default_output_dir(project_root: Path, dataset_name: str) -> Path:
    return project_root / "evaluation" / "ragas" / "datasets" / "beir" / dataset_name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="scifact")
    parser.add_argument("--split", default="test")
    parser.add_argument("--source-dir")
    parser.add_argument("--output-dir")
    parser.add_argument("--max-cases", type=int)
    parser.add_argument("--include-all-documents", action="store_true")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[2]
    source_dir = Path(args.source_dir).resolve() if args.source_dir else None
    output_dir = Path(args.output_dir).resolve() if args.output_dir else _default_output_dir(project_root, args.dataset)

    result = prepare_beir_dataset(
        dataset_name=args.dataset,
        split=args.split,
        source_dir=source_dir,
        output_dir=output_dir,
        max_cases=args.max_cases,
        include_all_documents=args.include_all_documents,
    )
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
