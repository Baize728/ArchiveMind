# ArchiveMind RAG 二期 BadCase 分析

## 执行概况

- EvalRunner：30 条 case 已生成答案，错误 0 条。
- RAGAS：30 条 case 已完成，失败 0 条。
- 裁判模型：RAGAS 主跑使用 DeepSeek Chat；embedding 使用 DashScope 2048 维。
- 说明：xfx.plus 在答案生成和 RAGAS 阶段多次返回 502，因此本次 RAGAS 裁判切换到 DeepSeek。

## 指标总览

| 指标 | 平均值 | 最低值 | 样本数 |
| --- | ---: | ---: | ---: |
| faithfulness | 0.9381 | 0.5556 | 30 |
| answer_relevancy | 0.9849 | 0.9073 | 30 |
| context_precision | 0.9049 | 0.0000 | 30 |
| context_recall | 0.9867 | 0.6000 | 30 |

## 检索与规则检查

| 指标 | 当前值 |
| --- | ---: |
| Hit@1 | 0.9333 |
| Hit@3 | 0.9667 |
| Hit@5 | 1.0000 |
| Recall@10 | 1.0000 |
| MRR | 0.9583 |
| DB/ES Inconsistent Count | 0 |
| Permission Leak Count | 0 |
| Java Rule BadCase Count | 4 |

## BadCase 明细

| Case | 问题 | 首个命中 | faithfulness | answer_relevancy | context_precision | context_recall | 归因 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| dist_cap_002 | 为什么 CAP 三者不可兼得？ | 1 | 1.0000 | 0.9948 | 0.7000 | 1.0000 | CONTEXT_PRECISION_LOW=0.7000 |
| dist_lock_001 | 常见的分布式锁实现方案有哪些？ | 1 | 0.8750 | 1.0000 | 1.0000 | 1.0000 | REFUSAL_RULE_FAIL |
| dist_tx_002 | 分布式事务有哪些常见实现方案？ | 2 | 0.6000 | 0.9977 | 0.8667 | 1.0000 | FAITHFULNESS_LOW=0.6000 |
| dist_tx_003 | 2PC 两阶段提交中有哪些角色？ | 1 | 0.7143 | 0.9958 | 1.0000 | 1.0000 | FAITHFULNESS_LOW=0.7143 |
| os_mode_001 | 用户态和内核态有什么区别？ | 1 | 1.0000 | 0.9923 | 0.7500 | 1.0000 | CONTEXT_PRECISION_LOW=0.7500 |
| os_process_001 | 常见的进程调度算法有哪些？ | 1 | 0.5556 | 1.0000 | 0.9167 | 1.0000 | FAITHFULNESS_LOW=0.5556 |
| os_ipc_001 | 进程间通信有哪些方式？ | 1 | 0.7000 | 0.9976 | 1.0000 | 1.0000 | FAITHFULNESS_LOW=0.7000 |
| micro_intro_001 | 什么是微服务？ | 1 | 0.9231 | 1.0000 | 1.0000 | 1.0000 | MUST_CONTAIN_FAIL |
| micro_config_001 | Nacos 配置中心的原理是什么？ | 1 | 1.0000 | 1.0000 | 1.0000 | 1.0000 | MUST_CONTAIN_FAIL |
| micro_gateway_001 | Spring Cloud Gateway 的核心概念有哪些？ | 1 | 1.0000 | 1.0000 | 0.5000 | 1.0000 | CONTEXT_PRECISION_LOW=0.5000 |
| micro_seata_001 | Seata 支持哪些分布式事务模式？ | 1 | 1.0000 | 1.0000 | 0.0000 | 0.6000 | CONTEXT_PRECISION_LOW=0.0000; CONTEXT_RECALL_LOW=0.6000; MUST_CONTAIN_FAIL |

## 归因结论

1. 检索主链路表现稳定：Hit@5 和 Recall@10 都为 1.0000，DB/ES 一致性和权限泄漏均无异常。
2. 低分主要不是召回失败，而是上下文排序噪声、答案规则字符串过严，以及少量生成答案未覆盖 reference 的所有要点。
3. `micro_seata_001` 是真实需要关注的样本：答案漏掉 XA，RAGAS 的 context_recall 也只有 0.6000，说明 answerTopK/top5 上下文对多 chunk 答案覆盖不足。
4. `dist_lock_001` 的 refusal 规则是误伤：答案最后补了一句“暂无相关信息”说明 Redis 细节不足，被规则当成拒答。
5. `micro_intro_001`、`micro_config_001` 的 MustContain 失败偏规则问题：答案语义正确，但没有精确出现“独立部署”“Nacos Client”等硬匹配词。

## 建议动作

1. 将 `mustContain` 从硬字符串匹配升级为同义词/正则组，例如“独立部署”允许“独立开发、部署”。
2. 对多 chunk 问题提高 `eval.runner.ragas-context-top-k` 或答案生成 `answerTopK`，优先验证 `micro_seata_001`。
3. 对 Gateway、CAP 三者不可兼得等 context_precision 偏低样本，检查 top5 中是否混入相邻章节，必要时优化 rerank 文本截断和结构字段权重。
4. xfx.plus 当前不适合作为稳定批量评测裁判；后续正式测评建议固定 DeepSeek 或换一个稳定 OpenAI-compatible 裁判通道。
