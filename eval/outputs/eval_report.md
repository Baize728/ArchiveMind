# ArchiveMind RAG 二期离线评测报告

- Run ID: `eval-20260807-152728-5c5ee4cd`
- Case 数: 30
- 错误 Case: 0
- RAGAS 输入: `eval\outputs\ragas_input.json`

## 总览指标

| 指标 | 当前值 | 建议阈值 |
| --- | ---: | ---: |
| Hit@5 | 1.0000 | 0.8500 |
| Recall@10 | 1.0000 | 0.8000 |
| MRR | 0.9583 | 0.6500 |
| Permission Leak Count | 0 | 0 |
| No-answer False Positive Count | 0 | 0 |
| DB/ES Inconsistent Count | 0 | 0 |

## BadCase

| Case | 类型 | 首个命中排名 | 错误 |
| --- | --- | ---: | --- |

## 下一步

1. 对 BadCase 按 `PARSE_BAD / CHUNK_BAD / CONTEXT_BAD / BM25_BAD / RERANK_BAD / GENERATION_BAD` 继续人工归因。
2. 运行 `eval/scripts/run_ragas_eval.py` 生成 RAGAS 分数，并合并到本报告。
3. 固定同一批 case 做 A/B/C/D 消融实验，比较结构感知 chunk 和 Contextual Retrieval 的收益。
