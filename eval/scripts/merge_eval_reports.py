#!/usr/bin/env python
"""Merge ArchiveMind Java eval output with RAGAS scores and emit governance reports."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any


DEFAULT_OUTPUT_DIR = Path("eval/outputs")


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")


def safe_get(mapping: dict[str, Any], *keys: str, default: Any = None) -> Any:
    current: Any = mapping
    for key in keys:
        if not isinstance(current, dict):
            return default
        current = current.get(key)
    return default if current is None else current


def load_yaml_like(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    data: dict[str, Any] = {}
    stack: list[tuple[int, dict[str, Any]]] = [(0, data)]
    with path.open("r", encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            indent = len(line) - len(line.lstrip(" "))
            while len(stack) > 1 and indent < stack[-1][0]:
                stack.pop()
            if ":" not in stripped:
                continue
            key, value = stripped.split(":", 1)
            key = key.strip()
            value = value.strip()
            current = stack[-1][1]
            if value == "":
                next_node: dict[str, Any] = {}
                current[key] = next_node
                stack.append((indent + 2, next_node))
            else:
                if value.lower() in {"true", "false"}:
                    current[key] = value.lower() == "true"
                else:
                    try:
                        if "." in value:
                            current[key] = float(value)
                        else:
                            current[key] = int(value)
                    except ValueError:
                        current[key] = value
    return data


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_file_sha256(path: Path) -> str:
    return file_sha256(path) if path.exists() else ""


def flatten_answer_check(row: dict[str, Any]) -> dict[str, Any]:
    answer_check = row.get("answerCheck") or {}
    if not isinstance(answer_check, dict):
        answer_check = {}
    return {
        "answerGenerated": bool(answer_check.get("answerGenerated")),
        "mustContainPass": bool(answer_check.get("mustContainPass")),
        "mustNotContainPass": bool(answer_check.get("mustNotContainPass")),
        "sourceReferencePass": bool(answer_check.get("sourceReferencePass")),
        "refusalPass": bool(answer_check.get("refusalPass")),
    }


def flatten_retrieval(row: dict[str, Any]) -> dict[str, Any]:
    retrieval = row.get("retrieval") or {}
    if not isinstance(retrieval, dict):
        retrieval = {}
    return {
        "hitAt1": bool(retrieval.get("hitAt1")),
        "hitAt3": bool(retrieval.get("hitAt3")),
        "hitAt5": bool(retrieval.get("hitAt5")),
        "recallAt5": float(retrieval.get("recallAt5") or 0.0),
        "recallAt10": float(retrieval.get("recallAt10") or 0.0),
        "precisionAt5": float(retrieval.get("precisionAt5") or 0.0),
        "precisionAt10": float(retrieval.get("precisionAt10") or 0.0),
        "mrr": float(retrieval.get("mrr") or 0.0),
        "sourceHitAt5": bool(retrieval.get("sourceHitAt5")),
        "permissionLeak": bool(retrieval.get("permissionLeak")),
        "noAnswerFalsePositive": bool(retrieval.get("noAnswerFalsePositive")),
        "firstRelevantRank": int(retrieval.get("firstRelevantRank") or -1),
        "matchedGoldCountAt10": int(retrieval.get("matchedGoldCountAt10") or 0),
    }


def flatten_ingestion(row: dict[str, Any]) -> dict[str, Any]:
    ingestion = row.get("ingestion") or {}
    if not isinstance(ingestion, dict):
        ingestion = {}
    return {
        "parsedSuccessCount": int(ingestion.get("parsedSuccessCount") or 0),
        "documentVectorCount": int(ingestion.get("documentVectorCount") or 0),
        "esDocumentCount": int(ingestion.get("esDocumentCount") or 0),
        "dbEsConsistent": bool(ingestion.get("dbEsConsistent")),
        "contextualizedContentRate": float(ingestion.get("contextualizedContentRate") or 0.0),
        "structureMetadataRate": float(ingestion.get("structureMetadataRate") or 0.0),
        "permissionMetadataRate": float(ingestion.get("permissionMetadataRate") or 0.0),
    }


def ragas_metric_presence(ragas_row: dict[str, Any]) -> tuple[int, int]:
    metric_names = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
    present = 0
    for metric in metric_names:
        if isinstance(ragas_row.get(metric), (int, float)):
            present += 1
    return present, len(metric_names)


def ragas_status(ragas_row: dict[str, Any]) -> str:
    present, total = ragas_metric_presence(ragas_row)
    if present == 0:
        return "MISSING"
    if present < total:
        return "PARTIAL"
    return "FULL"


def load_ragas_scores(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    payload = read_json(path)
    if isinstance(payload, dict):
        rows = payload.get("rows")
        if isinstance(rows, list):
            payload = rows
    result: dict[str, dict[str, Any]] = {}
    if isinstance(payload, list):
        for item in payload:
            if not isinstance(item, dict):
                continue
            case_id = item.get("case_id")
            if isinstance(case_id, str) and case_id:
                result[case_id] = item
    return result


def load_eval_profile(path: Path) -> dict[str, Any]:
    payload = load_yaml_like(path)
    runner = payload.get("eval", {}).get("runner", {}) if isinstance(payload, dict) else {}
    if not isinstance(runner, dict):
        runner = {}
    return runner


def classify_badcase(
    row: dict[str, Any],
    ragas_row: dict[str, Any],
    thresholds: dict[str, float],
    ragas_available: bool,
) -> list[str]:
    labels: list[str] = []
    retrieval = flatten_retrieval(row)
    answer_check = flatten_answer_check(row)
    ingestion = flatten_ingestion(row)

    if retrieval["permissionLeak"]:
        labels.append("PERMISSION_BAD")
    if not ingestion["dbEsConsistent"] and ingestion["documentVectorCount"] > 0:
        labels.append("INDEX_CONSISTENCY_BAD")
    if row.get("expectedAnswerable", True) and not retrieval["hitAt5"]:
        labels.append("RETRIEVAL_BAD")

    status = ragas_status(ragas_row) if ragas_available else "MISSING"
    if status == "MISSING":
        labels.append("RAGAS_SCORE_MISSING")
    elif status == "PARTIAL":
        labels.append("RAGAS_SCORE_PARTIAL")

    if ragas_available and status != "MISSING":
        context_recall = ragas_row.get("context_recall")
        if isinstance(context_recall, (int, float)) and context_recall < thresholds["context_recall"]:
            labels.append("CONTEXT_RECALL_BAD")

        context_precision = ragas_row.get("context_precision")
        if isinstance(context_precision, (int, float)) and context_precision < thresholds["context_precision"]:
            labels.append("CONTEXT_PRECISION_BAD")

        faithfulness = ragas_row.get("faithfulness")
        answer_relevancy = ragas_row.get("answer_relevancy")
        if isinstance(faithfulness, (int, float)) and faithfulness < thresholds["faithfulness"]:
            labels.append("GENERATION_BAD")
        if isinstance(answer_relevancy, (int, float)) and answer_relevancy < thresholds["answer_relevancy"]:
            labels.append("GENERATION_BAD")

    if not answer_check["mustContainPass"] or not answer_check["mustNotContainPass"] or not answer_check["sourceReferencePass"] or not answer_check["refusalPass"]:
        labels.append("RULE_BAD")

    if not labels:
        labels.append("PASS")
    deduped: list[str] = []
    for label in labels:
        if label not in deduped:
            deduped.append(label)
    return deduped


def primary_label(labels: list[str]) -> str:
    priority = [
        "PERMISSION_BAD",
        "INDEX_CONSISTENCY_BAD",
        "RETRIEVAL_BAD",
        "CONTEXT_RECALL_BAD",
        "CONTEXT_PRECISION_BAD",
        "GENERATION_BAD",
        "RULE_BAD",
        "RAGAS_SCORE_MISSING",
        "PASS",
    ]
    for label in priority:
        if label in labels:
            return label
    return labels[0] if labels else "UNKNOWN"


def format_bool(value: Any) -> str:
    return "true" if bool(value) else "false"


def main() -> None:
    parser = argparse.ArgumentParser(description="Merge ArchiveMind eval artifacts.")
    parser.add_argument("--eval-result", default="eval/outputs/eval_result.json")
    parser.add_argument("--ragas-scores", default="eval/outputs/ragas_scores.json")
    parser.add_argument("--eval-profile", default="eval/config/eval-profile.yml")
    parser.add_argument("--output-dir", default="eval/outputs")
    parser.add_argument("--summary-output", default="eval/outputs/eval_summary_merged.json")
    parser.add_argument("--badcase-output", default="eval/outputs/badcase_analysis.md")
    parser.add_argument("--manifest-output", default="eval/outputs/eval_manifest.json")
    args = parser.parse_args()

    eval_result_path = Path(args.eval_result)
    ragas_scores_path = Path(args.ragas_scores)
    eval_profile_path = Path(args.eval_profile)
    cases_path = Path("eval/cases/rag_eval_cases.json")
    ragas_env_path = Path("eval/config/ragas.env")
    merge_script_path = Path("eval/scripts/merge_eval_reports.py")
    ragas_script_path = Path("eval/scripts/run_ragas_eval.py")
    output_dir = Path(args.output_dir)
    summary_path = Path(args.summary_output)
    badcase_path = Path(args.badcase_output)
    manifest_path = Path(args.manifest_output)

    if not eval_result_path.exists():
        raise SystemExit(f"Missing eval result: {eval_result_path}")

    eval_rows = read_json(eval_result_path)
    if not isinstance(eval_rows, list):
        raise SystemExit(f"Expected JSON array in {eval_result_path}")

    ragas_available = ragas_scores_path.exists()
    ragas_map = load_ragas_scores(ragas_scores_path)
    profile = load_eval_profile(eval_profile_path)

    thresholds = {
        "hit_at5": float(profile.get("hit-at5-threshold", 0.85)),
        "recall_at10": float(profile.get("recall-at10-threshold", 0.80)),
        "mrr": float(profile.get("mrr-threshold", 0.65)),
        "faithfulness": float(profile.get("faithfulness-threshold", 0.90)),
        "answer_relevancy": float(profile.get("answer-relevancy-threshold", 0.95)),
        "context_precision": float(profile.get("context-precision-threshold", 0.85)),
        "context_recall": float(profile.get("context-recall-threshold", 0.90)),
    }

    merged_rows: list[dict[str, Any]] = []
    badcase_rows: list[dict[str, Any]] = []
    for row in eval_rows:
        if not isinstance(row, dict):
            continue
        case_id = row.get("caseId")
        ragas_row = ragas_map.get(case_id, {}) if isinstance(case_id, str) else {}
        retrieval = flatten_retrieval(row)
        ingestion = flatten_ingestion(row)
        answer_check = flatten_answer_check(row)

        merged_row = {
            "runId": row.get("runId"),
            "caseId": case_id,
            "caseType": row.get("caseType"),
            "question": row.get("question"),
            "expectedAnswerable": row.get("expectedAnswerable"),
            "retrieval": retrieval,
            "ingestion": ingestion,
            "answerCheck": answer_check,
            "ragas": {
                "faithfulness": ragas_row.get("faithfulness"),
                "answer_relevancy": ragas_row.get("answer_relevancy"),
                "context_precision": ragas_row.get("context_precision"),
                "context_recall": ragas_row.get("context_recall"),
                "failed": bool(ragas_row.get("errors")),
                "status": ragas_status(ragas_row) if ragas_available else "MISSING",
            },
        }
        labels = classify_badcase(row, ragas_row, thresholds, ragas_available)
        merged_row["badcaseLabels"] = labels
        merged_row["primaryBadcaseLabel"] = primary_label(labels)
        merged_rows.append(merged_row)

        if merged_row["primaryBadcaseLabel"] != "PASS":
            badcase_rows.append(
                {
                    "caseId": case_id,
                    "question": row.get("question"),
                    "labels": labels,
                    "primaryLabel": merged_row["primaryBadcaseLabel"],
                    "hitAt5": retrieval["hitAt5"],
                    "recallAt10": retrieval["recallAt10"],
                    "mrr": retrieval["mrr"],
                    "permissionLeak": retrieval["permissionLeak"],
                    "dbEsConsistent": ingestion["dbEsConsistent"],
                    "mustContainPass": answer_check["mustContainPass"],
                    "mustNotContainPass": answer_check["mustNotContainPass"],
                    "sourceReferencePass": answer_check["sourceReferencePass"],
                    "refusalPass": answer_check["refusalPass"],
                    "faithfulness": ragas_row.get("faithfulness"),
                    "answer_relevancy": ragas_row.get("answer_relevancy"),
                    "context_precision": ragas_row.get("context_precision"),
                    "context_recall": ragas_row.get("context_recall"),
                }
            )

    total = len(merged_rows)
    hit_at5 = sum(1 for row in merged_rows if row["retrieval"]["hitAt5"])
    recall_at10 = sum(1 for row in merged_rows if row["retrieval"]["recallAt10"] >= thresholds["recall_at10"])
    mrr_avg = sum(row["retrieval"]["mrr"] for row in merged_rows) / total if total else 0.0
    permission_leak = sum(1 for row in merged_rows if row["retrieval"]["permissionLeak"])
    db_es_bad = sum(1 for row in merged_rows if not row["ingestion"]["dbEsConsistent"] and row["ingestion"]["documentVectorCount"] > 0)
    must_contain_bad = sum(1 for row in merged_rows if not row["answerCheck"]["mustContainPass"])
    must_not_contain_bad = sum(1 for row in merged_rows if not row["answerCheck"]["mustNotContainPass"])
    source_ref_bad = sum(1 for row in merged_rows if not row["answerCheck"]["sourceReferencePass"])
    refusal_bad = sum(1 for row in merged_rows if not row["answerCheck"]["refusalPass"])

    ragas_rows = [ragas_map.get(row.get("caseId"), {}) for row in eval_rows if isinstance(row, dict)]
    ragas_failed = sum(1 for row in ragas_rows if row.get("errors"))
    ragas_missing = sum(1 for row in ragas_rows if ragas_status(row) == "MISSING")
    ragas_partial = sum(1 for row in ragas_rows if ragas_status(row) == "PARTIAL")
    ragas_full = sum(1 for row in ragas_rows if ragas_status(row) == "FULL")
    ragas_metrics = {
        "faithfulness": [row.get("faithfulness") for row in ragas_rows if isinstance(row.get("faithfulness"), (int, float))],
        "answer_relevancy": [row.get("answer_relevancy") for row in ragas_rows if isinstance(row.get("answer_relevancy"), (int, float))],
        "context_precision": [row.get("context_precision") for row in ragas_rows if isinstance(row.get("context_precision"), (int, float))],
        "context_recall": [row.get("context_recall") for row in ragas_rows if isinstance(row.get("context_recall"), (int, float))],
    }

    def avg(values: list[float]) -> float:
        return sum(values) / len(values) if values else 0.0

    hit_at5_rate = hit_at5 / total if total else 0.0
    recall_at10_avg = sum(row["retrieval"]["recallAt10"] for row in merged_rows) / total if total else 0.0
    summary = {
        "runId": merged_rows[0]["runId"] if merged_rows else None,
        "total": total,
        "errors": sum(1 for row in eval_rows if isinstance(row, dict) and row.get("errorMessage")),
        "withAnswer": sum(1 for row in merged_rows if row["answerCheck"]["answerGenerated"]),
        "hitAt1": sum(1 for row in merged_rows if row["retrieval"]["hitAt1"]),
        "hitAt3": sum(1 for row in merged_rows if row["retrieval"]["hitAt3"]),
        "hitAt5": hit_at5,
        "hitAt5Rate": hit_at5_rate,
        "recallAt10": recall_at10_avg,
        "mrr": mrr_avg,
        "permissionLeak": permission_leak,
        "dbEsBad": db_es_bad,
        "mustContainBad": must_contain_bad,
        "mustNotContainBad": must_not_contain_bad,
        "sourceRefBad": source_ref_bad,
        "refusalBad": refusal_bad,
        "ragasAvailable": ragas_available,
        "ragasRows": len(ragas_rows),
        "ragasFailedRows": ragas_failed,
        "ragasMissingRows": ragas_missing,
        "ragasPartialRows": ragas_partial,
        "ragasFullRows": ragas_full,
        "ragas": {
            "faithfulness": {"avg": avg(ragas_metrics["faithfulness"]), "min": min(ragas_metrics["faithfulness"]) if ragas_metrics["faithfulness"] else None, "count": len(ragas_metrics["faithfulness"])},
            "answer_relevancy": {"avg": avg(ragas_metrics["answer_relevancy"]), "min": min(ragas_metrics["answer_relevancy"]) if ragas_metrics["answer_relevancy"] else None, "count": len(ragas_metrics["answer_relevancy"])},
            "context_precision": {"avg": avg(ragas_metrics["context_precision"]), "min": min(ragas_metrics["context_precision"]) if ragas_metrics["context_precision"] else None, "count": len(ragas_metrics["context_precision"])},
            "context_recall": {"avg": avg(ragas_metrics["context_recall"]), "min": min(ragas_metrics["context_recall"]) if ragas_metrics["context_recall"] else None, "count": len(ragas_metrics["context_recall"])},
        },
        "thresholds": thresholds,
        "gate": {
            "hitAt5Pass": hit_at5_rate >= thresholds["hit_at5"],
            "recallAt10Pass": recall_at10_avg >= thresholds["recall_at10"],
            "mrrPass": mrr_avg >= thresholds["mrr"],
            "permissionLeakPass": permission_leak == 0,
            "dbEsPass": db_es_bad == 0,
            "mustContainPass": must_contain_bad == 0,
            "refusalPass": refusal_bad == 0,
        },
    }

    manifest = {
        "runId": summary["runId"],
        "caseSetVersion": profile.get("case-set-version", ""),
        "corpusVersion": profile.get("corpus-version", ""),
        "configVersion": profile.get("config-version", ""),
        "judgeModelVersion": profile.get("judge-model-version", ""),
        "outputDir": str(output_dir.as_posix()),
        "files": {
            "evalResult": str(eval_result_path.as_posix()),
            "ragasScores": str(ragas_scores_path.as_posix()),
            "summary": str(summary_path.as_posix()),
            "badcaseAnalysis": str(badcase_path.as_posix()),
            "evalProfile": str(eval_profile_path.as_posix()),
            "cases": str(cases_path.as_posix()),
            "ragasEnv": str(ragas_env_path.as_posix()),
            "mergeScript": str(merge_script_path.as_posix()),
            "ragasScript": str(ragas_script_path.as_posix()),
        },
        "hashes": {
            "evalResultSha256": file_sha256(eval_result_path),
            "ragasScoresSha256": file_sha256(ragas_scores_path) if ragas_scores_path.exists() else "",
            "evalProfileSha256": safe_file_sha256(eval_profile_path),
            "casesSha256": safe_file_sha256(cases_path),
            "ragasEnvSha256": safe_file_sha256(ragas_env_path),
            "mergeScriptSha256": safe_file_sha256(merge_script_path),
            "ragasScriptSha256": safe_file_sha256(ragas_script_path),
        },
    }

    write_json(summary_path, summary)
    write_json(manifest_path, manifest)

    lines = [
        "# ArchiveMind RAG 第三期 BadCase 分析",
        "",
        "## 指标总览",
        "",
        "| 指标 | 当前值 |",
        "| --- | ---: |",
        f"| Case 数 | {summary['total']} |",
        f"| 错误 Case | {summary['errors']} |",
        f"| Hit@5 | {summary['hitAt5Rate']:.4f} |",
        f"| Recall@10 | {summary['recallAt10']:.4f} |",
        f"| MRR | {summary['mrr']:.4f} |",
        f"| Permission Leak | {summary['permissionLeak']} |",
        f"| DB/ES Inconsistent | {summary['dbEsBad']} |",
        f"| mustContainBad | {summary['mustContainBad']} |",
        f"| refusalBad | {summary['refusalBad']} |",
        f"| RAGAS Available | {summary['ragasAvailable']} |",
        f"| RAGAS failedRows | {summary['ragasFailedRows']} |",
        f"| RAGAS missingRows | {summary['ragasMissingRows']} |",
        f"| RAGAS partialRows | {summary['ragasPartialRows']} |",
        f"| RAGAS fullRows | {summary['ragasFullRows']} |",
        "",
        "## BadCase 明细",
        "",
        "| Case | 主标签 | 标签集合 | Hit@5 | Recall@10 | MRR | 规则失败 | RAGAS 状态 | RAGAS |",
        "| --- | --- | --- | ---: | ---: | ---: | --- | --- | --- |",
    ]
    for item in badcase_rows:
        ragas_bits = []
        for key in ("faithfulness", "answer_relevancy", "context_precision", "context_recall"):
            value = item.get(key)
            if isinstance(value, (int, float)):
                ragas_bits.append(f"{key}={value:.4f}")
        ragas_status_value = "MISSING"
        if item.get("faithfulness") is not None or item.get("answer_relevancy") is not None or item.get("context_precision") is not None or item.get("context_recall") is not None:
            present = sum(1 for key in ("faithfulness", "answer_relevancy", "context_precision", "context_recall") if item.get(key) is not None)
            ragas_status_value = "FULL" if present == 4 else "PARTIAL"
        lines.append(
            f"| {item.get('caseId', '')} | {item.get('primaryLabel', '')} | {', '.join(item.get('labels', []))} | "
            f"{format_bool(item.get('hitAt5'))} | {item.get('recallAt10', 0.0):.4f} | {item.get('mrr', 0.0):.4f} | "
            f"{'/' .join([k for k in ('mustContainPass', 'mustNotContainPass', 'sourceReferencePass', 'refusalPass') if not item.get(k, True)]) or 'PASS'} | "
            f"{ragas_status_value} | {'; '.join(ragas_bits) or 'N/A'} |"
        )

    lines.extend(
        [
            "",
            "## 归因规则",
            "",
            "- `PERMISSION_BAD`：权限泄漏。",
            "- `INDEX_CONSISTENCY_BAD`：DB/ES 不一致。",
            "- `RETRIEVAL_BAD`：可回答 case 未命中 Hit@5。",
            "- `CONTEXT_RECALL_BAD`：RAGAS context_recall 低于阈值。",
            "- `CONTEXT_PRECISION_BAD`：RAGAS context_precision 低于阈值。",
            "- `GENERATION_BAD`：RAGAS faithfulness 或 answer_relevancy 低于阈值。",
            "- `RULE_BAD`：mustContain / mustNotContain / sourceReference / refusal 任一失败。",
            "",
            "## 结论",
            "",
            f"- 通过门禁：`{summary['gate']['hitAt5Pass'] and summary['gate']['recallAt10Pass'] and summary['gate']['mrrPass'] and summary['gate']['permissionLeakPass'] and summary['gate']['dbEsPass']}`",
            f"- RAGAS 是否完整：`{summary['ragasAvailable'] and summary['ragasFullRows'] == summary['total']}`",
        ]
    )
    badcase_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"Wrote merged summary to {summary_path}")
    print(f"Wrote badcase analysis to {badcase_path}")
    print(f"Wrote manifest to {manifest_path}")


if __name__ == "__main__":
    main()
