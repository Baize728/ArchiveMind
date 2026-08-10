# ArchiveMind RAG 第三期 BadCase 分析

## 指标总览

| 指标 | 当前值 |
| --- | ---: |
| Case 数 | 30 |
| 错误 Case | 0 |
| Hit@5 | 1.0000 |
| Recall@10 | 1.0000 |
| MRR | 0.9583 |
| Permission Leak | 0 |
| DB/ES Inconsistent | 0 |
| mustContainBad | 1 |
| refusalBad | 0 |
| RAGAS Available | True |
| RAGAS failedRows | 0 |
| RAGAS missingRows | 0 |
| RAGAS partialRows | 0 |
| RAGAS fullRows | 30 |

## BadCase 明细

| Case | 主标签 | 标签集合 | Hit@5 | Recall@10 | MRR | 规则失败 | RAGAS 状态 | RAGAS |
| --- | --- | --- | ---: | ---: | ---: | --- | --- | --- |
| dist_cap_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8400; answer_relevancy=0.8583; context_precision=0.9167; context_recall=1.0000 |
| dist_cap_002 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.8623; context_precision=1.0000; context_recall=1.0000 |
| dist_base_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.6000; answer_relevancy=0.9353; context_precision=1.0000; context_recall=1.0000 |
| dist_lock_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8182; answer_relevancy=0.9704; context_precision=1.0000; context_recall=1.0000 |
| dist_lock_002 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD, GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.9352; context_precision=0.8333; context_recall=1.0000 |
| dist_tx_001 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD, GENERATION_BAD, RULE_BAD | true | 1.0000 | 1.0000 | mustContainPass | FULL | faithfulness=1.0000; answer_relevancy=0.9347; context_precision=0.7500; context_recall=1.0000 |
| dist_tx_002 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 0.5000 | PASS | FULL | faithfulness=0.5455; answer_relevancy=0.9357; context_precision=1.0000; context_recall=1.0000 |
| dist_tx_003 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.7778; answer_relevancy=0.8557; context_precision=1.0000; context_recall=1.0000 |
| dist_consensus_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.2857; answer_relevancy=0.9373; context_precision=1.0000; context_recall=1.0000 |
| dist_idempotent_001 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD, GENERATION_BAD | true | 1.0000 | 0.2500 | PASS | FULL | faithfulness=0.7368; answer_relevancy=0.9465; context_precision=0.7500; context_recall=1.0000 |
| os_intro_002 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.9697; context_precision=0.8056; context_recall=1.0000 |
| os_mode_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8636; answer_relevancy=1.0000; context_precision=0.9167; context_recall=1.0000 |
| os_process_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.5000; answer_relevancy=1.0000; context_precision=0.9167; context_recall=1.0000 |
| os_ipc_001 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.9964; context_precision=0.8333; context_recall=1.0000 |
| os_deadlock_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.9048; context_precision=1.0000; context_recall=1.0000 |
| os_memory_001 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD, GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.9167; answer_relevancy=0.8776; context_precision=0.7500; context_recall=1.0000 |
| os_io_002 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8235; answer_relevancy=0.9173; context_precision=1.0000; context_recall=1.0000 |
| micro_intro_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.9091; answer_relevancy=0.7996; context_precision=1.0000; context_recall=1.0000 |
| micro_challenge_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8095; answer_relevancy=0.9955; context_precision=1.0000; context_recall=1.0000 |
| micro_registry_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8095; answer_relevancy=0.9636; context_precision=1.0000; context_recall=1.0000 |
| micro_registry_002 | CONTEXT_PRECISION_BAD | CONTEXT_PRECISION_BAD, GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.7241; answer_relevancy=0.9616; context_precision=0.8333; context_recall=1.0000 |
| micro_config_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=1.0000; answer_relevancy=0.9089; context_precision=1.0000; context_recall=1.0000 |
| micro_config_002 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.6667; answer_relevancy=0.8295; context_precision=1.0000; context_recall=1.0000 |
| micro_rpc_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.8367; answer_relevancy=0.9590; context_precision=1.0000; context_recall=1.0000 |
| micro_resilience_001 | CONTEXT_RECALL_BAD | CONTEXT_RECALL_BAD, GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.2500; answer_relevancy=0.8883; context_precision=1.0000; context_recall=0.5000 |
| micro_gateway_001 | GENERATION_BAD | GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.5000; answer_relevancy=0.9706; context_precision=0.9167; context_recall=1.0000 |
| micro_seata_001 | CONTEXT_RECALL_BAD | CONTEXT_RECALL_BAD, CONTEXT_PRECISION_BAD, GENERATION_BAD | true | 1.0000 | 1.0000 | PASS | FULL | faithfulness=0.2000; answer_relevancy=0.9795; context_precision=0.5833; context_recall=0.0000 |

## 归因规则

- `PERMISSION_BAD`：权限泄漏。
- `INDEX_CONSISTENCY_BAD`：DB/ES 不一致。
- `RETRIEVAL_BAD`：可回答 case 未命中 Hit@5。
- `CONTEXT_RECALL_BAD`：RAGAS context_recall 低于阈值。
- `CONTEXT_PRECISION_BAD`：RAGAS context_precision 低于阈值。
- `GENERATION_BAD`：RAGAS faithfulness 或 answer_relevancy 低于阈值。
- `RULE_BAD`：mustContain / mustNotContain / sourceReference / refusal 任一失败。

## 结论

- 通过门禁：`True`
- RAGAS 是否完整：`True`
