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


def build_openai_client(api_key: str | None, base_url: str | None) -> Any:
    if not api_key:
        raise SystemExit(
            "Missing OPENAI_API_KEY. Set it in eval/config/ragas.env, "
            "as an environment variable, or pass --api-key."
        )

    client_kwargs: dict[str, str] = {"api_key": api_key}
    if base_url:
        client_kwargs["base_url"] = base_url
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
    client = build_openai_client(api_key, base_url)

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
        embedding_client = build_openai_client(embedding_api_key, embedding_base_url)
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
) -> dict[str, Any]:
    user_input = first_text(row, "user_input", "question")
    response = first_text(row, "response", "answer")
    reference = first_text(row, "reference", "ground_truth")
    contexts = as_text_list(row.get("retrieved_contexts") or row.get("contexts"))

    output: dict[str, Any] = {
        "case_id": row.get("case_id"),
        "user_input": user_input,
    }
    errors: dict[str, str] = {}

    for name in metric_names:
        metric = metric_objects[name]
        try:
            if name == "faithfulness":
                result = await metric.ascore(
                    user_input=user_input,
                    response=response,
                    retrieved_contexts=contexts,
                )
            elif name == "answer_relevancy":
                result = await metric.ascore(user_input=user_input, response=response)
            elif name == "context_precision":
                result = await metric.ascore(
                    user_input=user_input,
                    reference=reference,
                    retrieved_contexts=contexts,
                )
            elif name == "context_recall":
                result = await metric.ascore(
                    user_input=user_input,
                    reference=reference,
                    retrieved_contexts=contexts,
                )
            else:
                raise ValueError(f"unsupported metric: {name}")

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
    return output


async def run_ragas(rows: list[dict[str, Any]], args: argparse.Namespace) -> list[dict[str, Any]]:
    metric_names = parse_metrics(args.metrics)
    metric_objects = build_metric_objects(args, metric_names)
    semaphore = asyncio.Semaphore(args.concurrency)

    async def guarded(row: dict[str, Any]) -> dict[str, Any]:
        async with semaphore:
            return await score_one(row, metric_names, metric_objects)

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
    parser.add_argument("--metrics", default=os.getenv("RAGAS_METRICS", ",".join(DEFAULT_METRICS)))
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--provider", default=os.getenv("RAGAS_LLM_PROVIDER", "openai"))
    parser.add_argument("--llm-model", default=os.getenv("RAGAS_LLM_MODEL", "gpt-4o-mini"))
    parser.add_argument("--embedding-provider", default=os.getenv("RAGAS_EMBEDDING_PROVIDER", "openai"))
    parser.add_argument("--embedding-model", default=os.getenv("RAGAS_EMBEDDING_MODEL", "text-embedding-3-small"))
    parser.add_argument("--embedding-api-key", default=None)
    parser.add_argument("--embedding-base-url", default=None)
    parser.add_argument("--embedding-dimension", type=int, default=int(os.getenv("RAGAS_EMBEDDING_DIMENSION", "0")))
    parser.add_argument("--temperature", type=float, default=float(os.getenv("RAGAS_TEMPERATURE", "0")))
    parser.add_argument("--llm-max-tokens", type=int, default=int(os.getenv("RAGAS_LLM_MAX_TOKENS", "0")))
    parser.add_argument("--answer-relevancy-strictness", type=int, default=3)
    parser.add_argument("--api-key", default=None)
    parser.add_argument("--base-url", default=None)
    args = parser.parse_args()

    if args.concurrency < 1:
        raise SystemExit("--concurrency must be >= 1")

    input_path = Path(args.input)
    output_path = Path(args.output)
    rows = read_rows(input_path)
    if not rows:
        raise SystemExit(f"No rows found in {input_path}")

    scores = asyncio.run(run_ragas(rows, args))
    write_rows(output_path, scores)
    failed = sum(1 for row in scores if row.get("errors"))
    print(f"Wrote {len(scores)} RAGAS score rows to {output_path} (failed_rows={failed})")


if __name__ == "__main__":
    main()
