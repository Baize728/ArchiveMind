# ArchiveMind RAG 二期离线评测报告（合并版）

- Case 数: 30
- 已生成答案: 30
- 错误 Case: 0
- RAGAS 输入: `eval/outputs/ragas_input.json`

## 总览指标

| 指标 | 当前值 |
| --- | ---: |
| Hit@1 | 0.9333 |
| Hit@3 | 0.9667 |
| Hit@5 | 1.0000 |
| Recall@10 | 1.0000 |
| MRR | 0.9583 |
| Permission Leak Count | 0 |
| DB/ES Inconsistent Count | 0 |
| MustContain Bad Count | 3 |
| MustNotContain Bad Count | 0 |
| Source Reference Bad Count | 0 |
| Refusal Bad Count | 1 |

## BadCase

| Case | 类型 | 首个命中排名 | 问题 | 原因 |
| --- | --- | ---: | --- | --- |
| dist_lock_001 | list | 1 | 常见的分布式锁实现方案有哪些？ | REFUSAL_BAD |
| micro_intro_001 | definition | 1 | 什么是微服务？ | MUST_CONTAIN_BAD |
| micro_config_001 | implementation | 1 | Nacos 配置中心的原理是什么？ | MUST_CONTAIN_BAD |
| micro_seata_001 | list | 1 | Seata 支持哪些分布式事务模式？ | MUST_CONTAIN_BAD |
