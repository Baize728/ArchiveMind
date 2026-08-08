#!/usr/bin/env python
"""Run RAGAS 0.4.x metrics over ArchiveMind EvalRunner output.

Input is formatted JSON generated at eval/outputs/ragas_input.json.
The script intentionally stays outside the Spring Boot process so RAGAS and
judge-model dependencies do not affect the production service runtime.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
from pathlib import Path
from typing import Any


DEFAULT_METRICS = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")


def env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return int(value)
    except ValueError as exc:
        raise SystemExit(f"Invalid integer env var {name}={value!r}") from exc


def env_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        return float(value)
    except ValueError as exc:
        raise SystemExit(f"Invalid float env var {name}={value!r}") from exc


def load_env_file(path: Path) -> None:
    if not path.exists():
        return

    with path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            item = line.strip()
            if not item or item.startswith("#"):
                continue
            if item.startswith("export "):
                item = item[len("export "):].strip()
            if "=" not in item:
                raise SystemExit(f"Invalid env line {line_no} in {path}: expected KEY=VALUE")

            key, value = item.split("=", 1)
            key = key.strip()
            value = value.strip()
            if not key:
                raise SystemExit(f"Invalid env line {line_no} in {path}: empty key")
            if (value.startswith('"') and value.endswith('"')) or (value.startswith("'") and value.endswith("'")):
                value = value[1:-1]
            if value:
                os.environ.setdefault(key, value)


def read_rows(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as f:
        payload = json.load(f)
    if not isinstance(payload, list):
        raise SystemExit(f"Expected JSON array in {path}")

    rows: list[dict[str, Any]] = []
    for index, row in enumerate(payload, 1):
        if not isinstance(row, dict):
            raise SystemExit(f"Expected object at item {index} in {path}")
        if not row.get("response"):
            print(f"[WARN] item {index} has empty response; generation metrics will fail.")
        rows.append(row)
    return rows


def write_rows(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=2)
        f.write("\n")


def write_summary(path: Path, rows: list[dict[str, Any]]) -> None:
    def ragas_status(row: dict[str, Any]) -> str:
        metrics = ("faithfulness", "answer_relevancy", "context_precision", "context_recall")
        present = sum(1 for metric in metrics if isinstance(row.get(metric), (int, float)))
        if present == 0:
            return "MISSING"
        if present < len(metrics):
            return "PARTIAL"
        return "FULL"

    def metric_values(metric: str) -> list[float]:
        values: list[float] = []
        for row in rows:
            value = row.get(metric)
            if isinstance(value, (int, float)) and not math.isnan(float(value)):
                values.append(float(value))
        return values

    def metric_summary(metric: str) -> dict[str, Any]:
        values = metric_values(metric)
        return {
            "avg": sum(values) / len(values) if values else 0.0,
            "min": min(values) if values else None,
            "count": len(values),
        }

    payload = {
        "rows": len(rows),
        "failedRows": sum(1 for row in rows if row.get("errors")),
        "missingRows": sum(1 for row in rows if ragas_status(row) == "MISSING"),
        "partialRows": sum(1 for row in rows if ragas_status(row) == "PARTIAL"),
        "fullRows": sum(1 for row in rows if ragas_status(row) == "FULL"),
        "contextTruncatedRows": sum(1 for row in rows if row.get("context_truncated")),
        "faithfulness": metric_summary("faithfulness"),
        "answer_relevancy": metric_summary("answer_relevancy"),
        "context_precision": metric_summary("context_precision"),
        "context_recall": metric_summary("context_recall"),
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
        f.write("\n")


def parse_metrics(value: str) -> list[str]:
    requested = [item.strip() for item in value.split(",") if item.strip()]
    unknown = [item for item in requested if item not in DEFAULT_METRICS]
    if unknown:
        raise SystemExit(f"Unknown metric(s): {', '.join(unknown)}")
    return requested


def as_text_list(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str) and item.strip()]


def first_text(row: dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = row.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def score_value(value: Any) -> Any:
    if isinstance(value, float) and math.isnan(value):
        return None
    return value


def trim_contexts(contexts: list[str], args: argparse.Namespace) -> tuple[list[str], dict[str, Any]]:
    original_contexts = [context.strip() for context in contexts if context.strip()]
    original_count = len(original_contexts)
    original_chars = sum(len(context) for context in original_contexts)

    if args.max_contexts > 0:
        candidate_contexts = original_contexts[: args.max_contexts]
    else:
        candidate_contexts = original_contexts

    kept: list[str] = []
    kept_chars = 0
    for context in candidate_contexts:
        text = context
        if args.max_context_chars_per_item > 0 and len(text) > args.max_context_chars_per_item:
            text = text[: args.max_context_chars_per_item].rstrip()

        if args.max_context_chars > 0 and kept_chars + len(text) > args.max_context_chars:
            remaining = args.max_context_chars - kept_chars
            if remaining <= 0:
                break
            text = text[:remaining].rstrip()

        if not text:
            continue
        kept.append(text)
        kept_chars += len(text)

    stats = {
        "original_context_count": original_count,
        "original_context_chars": original_chars,
        "kept_context_count": len(kept),
        "kept_context_chars": kept_chars,
        "context_truncated": original_count != len(kept) or original_chars != kept_chars,
    }
    return kept, stats


def should_retry_exception(exc: Exception) -> bool:
    if isinstance(exc, asyncio.TimeoutError):
        return True
    message = f"{type(exc).__name__}: {exc}".lower()
    retry_tokens = (
        "timed out",
        "timeout",
        "connection error",
        "connection reset",
        "connection aborted",
        "temporarily unavailable",
        "rate limit",
        "429",
        "500",
        "502",
        "503",
        "504",
    )
    return any(token in message for token in retry_tokens)


async def score_metric_with_retry(
    call_factory: Any,
    args: argparse.Namespace,
) -> tuple[Any, int]:
    attempts = max(args.metric_retries, 0) + 1
    last_exc: Exception | None = None

    for attempt in range(1, attempts + 1):
        try:
            coroutine = call_factory()
            if args.metric_timeout_seconds > 0:
                return await asyncio.wait_for(coroutine, timeout=args.metric_timeout_seconds), attempt
            return await coroutine, attempt
        except Exception as exc:
            last_exc = exc
            if attempt >= attempts or not should_retry_exception(exc):
                break
            await asyncio.sleep(max(args.retry_backoff_seconds, 0.0) * attempt)

    assert last_exc is not None
    raise last_exc


def ensure_runtime_dependencies() -> None:
    try:
        import ragas
        from ragas.embeddings.base import BaseRagasEmbedding as _BaseRagasEmbedding
        from ragas.embeddings.base import embedding_factory as _embedding_factory
        from ragas.llms import llm_factory as _llm_factory
        from ragas.metrics.collections import (
            AnswerRelevancy as _AnswerRelevancy,
            ContextPrecisionWithReference as _ContextPrecisionWithReference,
            ContextRecall as _ContextRecall,
            Faithfulness as _Faithfulness,
        )
        from openai import AsyncOpenAI as _AsyncOpenAI
    except ImportError as exc:
        raise SystemExit(
            "Missing dependencies. Install with: "
            "python -m pip install -r eval/requirements-ragas.txt"
        ) from exc

    version = getattr(ragas, "__version__", "unknown")
    if not str(version).startswith("0.4."):
        print(f"[WARN] expected ragas 0.4.x, found {version}. "
              "Pin eval/requirements-ragas.txt before formal benchmarking.")

    globals().update(
        {
            "AsyncOpenAI": _AsyncOpenAI,
            "BaseRagasEmbedding": _BaseRagasEmbedding,
            "llm_factory": _llm_factory,
            "embedding_factory": _embedding_factory,
            "AnswerRelevancy": _AnswerRelevancy,
            "ContextPrecisionWithReference": _ContextPrecisionWithReference,
            "ContextRecall": _ContextRecall,
            "Faithfulness": _Faithfulness,
        }
    )


def build_openai_client(
    api_key: str | None,
    base_url: str | None,
    timeout_seconds: float,
    max_retries: int,
) -> Any:
    if not api_key:
        raise SystemExit(
            "Missing OPENAI_API_KEY. Set it in eval/config/ragas.env, "
            "as an environment variable, or pass --api-key."
        )

    client_kwargs: dict[str, Any] = {"api_key": api_key}
    if base_url:
        client_kwargs["base_url"] = base_url
    if timeout_seconds > 0:
        client_kwargs["timeout"] = timeout_seconds
    client_kwargs["max_retries"] = max(max_retries, 0)
    return AsyncOpenAI(**client_kwargs)


def fixed_dimension_embeddings(delegate: Any, dimension: int) -> Any:
    base_cls = globals()["BaseRagasEmbedding"]

    class FixedDimensionEmbeddings(base_cls):
        def __init__(self, wrapped: Any, fixed_dimension: int):
            self.wrapped = wrapped
            self.fixed_dimension = fixed_dimension

        def embed_text(self, text: str, **kwargs: Any) -> list[float]:
            kwargs.setdefault("dimensions", self.fixed_dimension)
            return self.wrapped.embed_text(text, **kwargs)

        async def aembed_text(self, text: str, **kwargs: Any) -> list[float]:
            kwargs.setdefault("dimensions", self.fixed_dimension)
            return await self.wrapped.aembed_text(text, **kwargs)

        def embed_texts(self, texts: list[str], **kwargs: Any) -> list[list[float]]:
            kwargs.setdefault("dimensions", self.fixed_dimension)
            return self.wrapped.embed_texts(texts, **kwargs)

        async def aembed_texts(self, texts: list[str], **kwargs: Any) -> list[list[float]]:
            kwargs.setdefault("dimensions", self.fixed_dimension)
            return await self.wrapped.aembed_texts(texts, **kwargs)

        def __getattr__(self, name: str) -> Any:
            return getattr(self.wrapped, name)

    return FixedDimensionEmbeddings(delegate, dimension)


def build_metric_objects(args: argparse.Namespace, metric_names: list[str]) -> dict[str, Any]:
    ensure_runtime_dependencies()

    api_key = args.api_key or os.getenv("OPENAI_API_KEY")
    base_url = args.base_url or os.getenv("OPENAI_BASE_URL")
    client = build_openai_client(api_key, base_url, args.request_timeout_seconds, args.openai_max_retries)

    llm_kwargs: dict[str, Any] = {"temperature": args.temperature}
    if args.llm_max_tokens > 0:
        llm_kwargs["max_tokens"] = args.llm_max_tokens

    llm = llm_factory(
        args.llm_model,
        provider=args.provider,
        client=client,
        **llm_kwargs,
    )

    metric_objects: dict[str, Any] = {}
    if "faithfulness" in metric_names:
        metric_objects["faithfulness"] = Faithfulness(llm=llm)
    if "context_precision" in metric_names:
        metric_objects["context_precision"] = ContextPrecisionWithReference(llm=llm, name="context_precision")
    if "context_recall" in metric_names:
        metric_objects["context_recall"] = ContextRecall(llm=llm)
    if "answer_relevancy" in metric_names:
        embedding_api_key = args.embedding_api_key or os.getenv("RAGAS_EMBEDDING_API_KEY") or api_key
        embedding_base_url = args.embedding_base_url or os.getenv("RAGAS_EMBEDDING_BASE_URL") or base_url
        embedding_client = build_openai_client(
            embedding_api_key,
            embedding_base_url,
            args.request_timeout_seconds,
            args.openai_max_retries,
        )
        embeddings = embedding_factory(
            args.embedding_provider,
            model=args.embedding_model,
            client=embedding_client,
            interface="modern",
        )
        if args.embedding_dimension > 0:
            embeddings = fixed_dimension_embeddings(embeddings, args.embedding_dimension)
        metric_objects["answer_relevancy"] = AnswerRelevancy(
            llm=llm,
            embeddings=embeddings,
            strictness=args.answer_relevancy_strictness,
        )
    return metric_objects


async def score_one(
    row: dict[str, Any],
    metric_names: list[str],
    metric_objects: dict[str, Any],
    args: argparse.Namespace,
) -> dict[str, Any]:
    user_input = first_text(row, "user_input", "question")
    response = first_text(row, "response", "answer")
    reference = first_text(row, "reference", "ground_truth")
    contexts, context_stats = trim_contexts(as_text_list(row.get("retrieved_contexts") or row.get("contexts")), args)

    output: dict[str, Any] = {
        "case_id": row.get("case_id"),
        "user_input": user_input,
        **context_stats,
    }
    errors: dict[str, str] = {}
    attempts_by_metric: dict[str, int] = {}

    for name in metric_names:
        metric = metric_objects[name]
        try:
            if name == "faithfulness":
                result, attempts = await score_metric_with_retry(
                    lambda: metric.ascore(
                        user_input=user_input,
                        response=response,
                        retrieved_contexts=contexts,
                    ),
                    args,
                )
            elif name == "answer_relevancy":
                result, attempts = await score_metric_with_retry(
                    lambda: metric.ascore(user_input=user_input, response=response),
                    args,
                )
            elif name == "context_precision":
                result, attempts = await score_metric_with_retry(
                    lambda: metric.ascore(
                        user_input=user_input,
                        reference=reference,
                        retrieved_contexts=contexts,
                    ),
                    args,
                )
            elif name == "context_recall":
                result, attempts = await score_metric_with_retry(
                    lambda: metric.ascore(
                        user_input=user_input,
                        reference=reference,
                        retrieved_contexts=contexts,
                    ),
                    args,
                )
            else:
                raise ValueError(f"unsupported metric: {name}")

            attempts_by_metric[name] = attempts
            output[name] = score_value(result.value)
            if name == "answer_relevancy":
                output["response_relevancy"] = score_value(result.value)
            if getattr(result, "reason", None):
                output[f"{name}_reason"] = result.reason
        except Exception as exc:
            output[name] = None
            if name == "answer_relevancy":
                output["response_relevancy"] = None
            errors[name] = f"{type(exc).__name__}: {exc}"

    if errors:
        output["errors"] = errors
    if attempts_by_metric:
        output["attempts"] = attempts_by_metric
    return output


async def run_ragas(rows: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    metric_names = parse_metrics(args.metrics)
    metric_objects = build_metric_objects(args, metric_names)
    semaphore = asyncio.Semaphore(args.concurrency)

    async def guarded(row: dict[str, Any]) -> dict[str, Any]:
        async with semaphore:
            return await score_one(row, metric_names, metric_objects, args)

    return await asyncio.gather(*(guarded(row) for row in rows))


def main() -> None:
    env_parser = argparse.ArgumentParser(add_help=False)
    env_parser.add_argument(
        "--env-file",
        default="eval/config/ragas.env",
        help="Local env file for RAGAS/OpenAI settings. Shell env vars take precedence.",
    )
    env_args, _ = env_parser.parse_known_args()
    load_env_file(Path(env_args.env_file))

    parser = argparse.ArgumentParser(
        description="Run RAGAS 0.4.x for ArchiveMind eval output.",
        parents=[env_parser],
    )
    parser.add_argument("--input", default="eval/outputs/ragas_input.json")
    parser.add_argument("--output", default="eval/outputs/ragas_scores.json")
    parser.add_argument("--summary", default="eval/outputs/ragas_summary.json")
    parser.add_argument("--metrics", default=os.getenv("RAGAS_METRICS", ",".join(DEFAULT_METRICS)))
    parser.add_argument("--concurrency", type=int, default=env_int("RAGAS_CONCURRENCY", 2))
    parser.add_argument("--provider", default=os.getenv("RAGAS_LLM_PROVIDER", "openai"))
    parser.add_argument("--llm-model", default=os.getenv("RAGAS_LLM_MODEL", "gpt-4o-mini"))
    parser.add_argument("--embedding-provider", default=os.getenv("RAGAS_EMBEDDING_PROVIDER", "openai"))
    parser.add_argument("--embedding-model", default=os.getenv("RAGAS_EMBEDDING_MODEL", "text-embedding-3-small"))
    parser.add_argument("--embedding-api-key", default=None)
    parser.add_argument("--embedding-base-url", default=None)
    parser.add_argument("--embedding-dimension", type=int, default=env_int("RAGAS_EMBEDDING_DIMENSION", 0))
    parser.add_argument("--temperature", type=float, default=env_float("RAGAS_TEMPERATURE", 0.0))
    parser.add_argument("--llm-max-tokens", type=int, default=env_int("RAGAS_LLM_MAX_TOKENS", 0))
    parser.add_argument("--answer-relevancy-strictness", type=int, default=3)
    parser.add_argument("--request-timeout-seconds", type=float, default=env_float("RAGAS_REQUEST_TIMEOUT_SECONDS", 180.0))
    parser.add_argument("--openai-max-retries", type=int, default=env_int("RAGAS_OPENAI_MAX_RETRIES", 2))
    parser.add_argument("--metric-timeout-seconds", type=float, default=env_float("RAGAS_METRIC_TIMEOUT_SECONDS", 240.0))
    parser.add_argument("--metric-retries", type=int, default=env_int("RAGAS_METRIC_RETRIES", 1))
    parser.add_argument("--retry-backoff-seconds", type=float, default=env_float("RAGAS_RETRY_BACKOFF_SECONDS", 2.0))
    parser.add_argument("--max-contexts", type=int, default=env_int("RAGAS_MAX_CONTEXTS", 6))
    parser.add_argument("--max-context-chars", type=int, default=env_int("RAGAS_MAX_CONTEXT_CHARS", 4000))
    parser.add_argument("--max-context-chars-per-item", type=int, default=env_int("RAGAS_MAX_CONTEXT_CHARS_PER_ITEM", 1200))
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--base-url", default=None)
    args = parser.parse_args()

    if args.concurrency < 1:
        raise SystemExit("--concurrency must be >= 1")
    if args.metric_retries < 0:
        raise SystemExit("--metric-retries must be >= 0")
    if args.openai_max_retries < 0:
        raise SystemExit("--openai-max-retries must be >= 0")

    input_path = Path(args.input)
    output_path = Path(args.output)
    rows = read_rows(input_path)
    if not rows:
        raise SystemExit(f"No rows found in {input_path}")

    scores = asyncio.run(run_ragas(rows, args))
    write_rows(output_path, scores)
    write_summary(Path(args.summary), scores)
    failed = sum(1 for row in scores if row.get("errors"))
    print(f"Wrote {len(scores)} RAGAS score rows to {output_path} (failed_rows={failed})")
    print(f"Wrote RAGAS summary to {Path(args.summary)}")


if __name__ == "__main__":
    main()
