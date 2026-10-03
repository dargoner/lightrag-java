#!/usr/bin/env python3
"""Build the Changzhou housing-fund (czgjj) evaluation set for the RAGAS gate.

Sources live in the GraphRAG-SDK worktree
(``.worktrees/changzhou-housing-fund-ontology/domain_profiles/changzhou_housing_fund``):

- ``seed/policies/*.md``            policy snapshots (the retrieval corpus)
- ``eval/golden_questions.json``    curated golden suite (100 cases)

Selection: cases whose ``expected_sources`` are all inside ``seed/policies`` and
that are not refusal probes -> 53 cases; the service-diagram PNG cases and the
two out-of-scope refusal cases are out of scope for a text-RAGAS run.

Ground truth assembly is deterministic: each ``required_facts`` entry
(``all_of`` / ``any_of`` / ``all_of_any`` keyword groups) is located in the
expected source files as a single line or as a window of consecutive non-empty
lines, after removing whitespace (the PDF-flattened tables break values across
lines). Cases whose facts exist only as table metadata without text labels, or
as an as-of-date inference, carry hand-written references in MANUAL_REFERENCES.
"""

import argparse
import json
import shutil
from pathlib import Path

PROJECT = "czgjj-policy-eval"

# Hand-written references for cases the deterministic matcher cannot cover.
# Why each override exists:
# - loan-rate-*: the rate table is a PDF-flattened layout whose 首套/第二次 and
#   percentage signs survive only as column metadata; the numbers themselves are
#   in the snapshot (2.10 / 2.525 / 2.60 / 3.075) and the 待核实 status lives in
#   the snapshot frontmatter.
# - future-policy-as-of-date: "尚未生效" is an as-of-2026-07-31 inference from
#   the snapshot's 发布日期/生效日期/采集时间 frontmatter, not body text.
MANUAL_REFERENCES = {
    "loan-rate-short-term": (
        "贷款期限1至5年：公积金贷款次数1次的年利率为2.10%，贷款次数2次的年利率为2.525%。"
        "该利率表资料状态为待核实：此利率自2025年5月8日起执行，此表仅供参考，贷款时以合同为准。"
    ),
    "loan-rate-long-term": (
        "贷款期限6至30年：公积金贷款次数1次的年利率为2.60%，贷款次数2次的年利率为3.075%。"
        "该利率表资料状态为待核实：此利率自2025年5月8日起执行，此表仅供参考，贷款时以合同为准。"
    ),
    "future-policy-as-of-date": (
        "该政策（2026年住房公积金提取使用业务调整，发布日2026年7月9日）自2026年8月1日起施行；"
        "截至2026年7月31日尚未生效。生效后支持代际互助提取还贷：借款人及其配偶账户余额不足的，"
        "可以提取其直系亲属（父母、子女）的住房公积金用于偿还住房贷款。"
    ),
}

WHITESPACE = " \t\r\n\u3000"


def normalize(text: str) -> str:
    return "".join(char for char in text if char not in WHITESPACE)


def fact_groups(case: dict) -> list[tuple[str, list, str]]:
    groups = []
    for fact in case.get("required_facts", []):
        if "all_of" in fact:
            groups.append(("all_of", fact["all_of"], fact["id"]))
        elif "any_of" in fact:
            groups.append(("any_of", fact["any_of"], fact["id"]))
        elif "all_of_any" in fact:
            groups.append(("all_of_any", fact["all_of_any"], fact["id"]))
    return groups


def fact_hits(text: str, kind: str, tokens) -> bool:
    normalized = normalize(text)
    if kind == "all_of":
        return all(normalize(token) in normalized for token in tokens)
    if kind == "any_of":
        return any(normalize(token) in normalized for token in tokens)
    return all(any(normalize(token) in normalized for token in group) for group in tokens)


def locate_fact(documents: dict[str, list[str]], sources: list[str], kind: str, tokens):
    for source in sources:
        lines = documents[source]
        for line in lines:
            if fact_hits(line, kind, tokens):
                return line
        for window in range(2, 7):
            for start in range(len(lines) - window + 1):
                joined = " ".join(lines[start : start + window])
                if fact_hits(joined, kind, tokens):
                    return joined
    return None


def build_reference(case: dict, documents: dict[str, list[str]]) -> tuple[str, list[str]]:
    missing = []
    fragments = []
    for kind, tokens, fact_id in fact_groups(case):
        fragment = locate_fact(documents, case["expected_sources"], kind, tokens)
        if fragment is None:
            missing.append(fact_id)
        else:
            fragments.append(fragment)
    reference = " ".join(dict.fromkeys(fragments))
    return reference, missing


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile-dir", required=True,
                        help="changzhou_housing_fund domain profile directory")
    parser.add_argument("--out-dataset", default=None)
    parser.add_argument("--out-documents", default=None)
    args = parser.parse_args()

    script_dir = Path(__file__).resolve().parent
    out_dataset = Path(args.out_dataset) if args.out_dataset else script_dir / "gjj_dataset.json"
    out_documents = Path(args.out_documents) if args.out_documents else script_dir / "gjj_documents"

    profile = Path(args.profile_dir).resolve()
    policy_dir = profile / "seed" / "policies"
    golden_path = profile / "eval" / "golden_questions.json"
    if not policy_dir.is_dir() or not golden_path.is_file():
        raise SystemExit(f"profile layout not found under {profile}")

    policy_names = sorted(path.name for path in policy_dir.glob("*.md"))
    documents = {
        name: [line.strip() for line in (policy_dir / name).read_text(encoding="utf-8").splitlines() if line.strip()]
        for name in policy_names
    }

    golden = json.loads(golden_path.read_text(encoding="utf-8"))
    cases = [
        case for case in golden["cases"]
        if set(case.get("expected_sources", [])) <= set(policy_names) and not case.get("should_refuse")
    ]

    out_documents.mkdir(parents=True, exist_ok=True)
    for name in policy_names:
        shutil.copyfile(policy_dir / name, out_documents / name)

    test_cases = []
    unresolved = []
    override_used = []
    for case in cases:
        case_id = case["id"]
        if case_id in MANUAL_REFERENCES:
            reference = MANUAL_REFERENCES[case_id]
            override_used.append(case_id)
        else:
            reference, missing = build_reference(case, documents)
            if missing:
                unresolved.append((case_id, missing))
                continue
        if not reference.strip():
            unresolved.append((case_id, ["<empty reference>"]))
            continue
        test_cases.append({
            "question": case["question"],
            "ground_truth": reference,
            "project": PROJECT,
            "case_id": case_id,
            "category": case["category"],
            "expected_sources": case["expected_sources"],
        })

    if unresolved:
        for case_id, missing in unresolved:
            print(f"UNRESOLVED {case_id}: {missing}")
        raise SystemExit(f"{len(unresolved)} cases could not be assembled")

    out_dataset.write_text(
        json.dumps({"suite": golden["suite"], "test_cases": test_cases}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"documents: {len(policy_names)} -> {out_documents}")
    print(f"test cases: {len(test_cases)} (auto {len(test_cases) - len(override_used)}, manual {len(override_used)})")
    print(f"manual references: {', '.join(override_used)}")
    print(f"dataset -> {out_dataset}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
