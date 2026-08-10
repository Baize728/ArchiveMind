# ArchiveMind RAG 第三期质量治理与自动回归

本目录用于运行第三期离线测评闭环。第三期的重点不再是“单次跑通”，而是把 Java 检索指标、RAGAS 语义指标、规则检查和坏例归因合成一套可回归、可追溯、可对比的治理链路。

对应的总体方案见：

- [ArchiveMind-RAG-第三期质量治理与自动回归技术方案](../docs/ArchiveMind-RAG-第三期质量治理与自动回归技术方案.md)

## 目录说明

```text
eval/
  cases/
    rag_eval_cases.json         # 格式化评测问题集
  config/
    eval-profile.yml            # 第三期实际生效配置
    ragas.env.example           # RAGAS 本地配置模板
    thresholds.yml              # 历史样例，仅供对照，不作为当前入口
  outputs/
    eval_result.json            # Java EvalRunner 明细输出
    eval_report.csv             # Java 指标 CSV
    eval_report.md              # Java 指标 Markdown 报告
    ragas_input.json            # RAGAS 输入
    ragas_scores.json           # RAGAS 原始输出
    ragas_summary.json          # RAGAS 汇总
    eval_summary_merged.json    # Java + RAGAS 合并总表
    badcase_analysis.md         # 自动 BadCase 分析
    eval_manifest.json          # 本次评测 manifest / hash / 版本信息
  requirements-ragas.txt        # RAGAS Python 依赖锁定
  scripts/
    run_ragas_eval.py           # RAGAS 评分脚本
    run_ragas_eval.ps1          # Windows 单独执行 RAGAS
    merge_eval_reports.py       # 合并 Java + RAGAS 报告
    run_third_phase_eval.ps1    # 第三期一键执行入口
```

## 推荐入口

第三期建议直接跑一键脚本：

```powershell
.\eval\scripts\run_third_phase_eval.ps1
```

它会依次执行：

1. Java `EvalRunner`，生成 `eval_result.json`、`eval_report.csv`、`eval_report.md`、`ragas_input.json`
2. RAGAS 评分，生成 `ragas_scores.json`、`ragas_summary.json`
3. 报告合并，生成 `eval_summary_merged.json`、`badcase_analysis.md`、`eval_manifest.json`

常用参数：

```powershell
.\eval\scripts\run_third_phase_eval.ps1 `
  -JavaProfile "" `
  -Metrics "" `
  -Concurrency 2
```

说明：

- `-EvalArgs`：传给 Spring Boot 的评测参数，默认会开启 `generate-answers=true`
- `-JavaProfile`：可选 Spring 配置 profile
- `-Metrics`：可选 RAGAS 指标子集
- `-Concurrency`：RAGAS 并发数
- `-MaxContexts`、`-MaxContextChars`、`-MaxContextCharsPerItem`：限制每条样本喂给裁判的上下文规模，主要用于降低超时风险
- `-MetricTimeoutSeconds`、`-RequestTimeoutSeconds`：控制单条指标和单次上游请求的超时时间
- `-MetricRetries`、`-OpenAIMaxRetries`、`-RetryBackoffSeconds`：控制失败重试策略

## 分步执行

### 1. 运行 Java EvalRunner

确认 MySQL、ES、Redis、Reranker、Embedding 服务可用，并且评测语料已经入库后运行：

```powershell
.\mvnw.cmd -Dmaven.test.skip=true spring-boot:run `
  "-Dspring-boot.run.arguments=--eval.runner.enabled=true --eval.runner.cases-path=eval/cases/rag_eval_cases.json --eval.runner.output-dir=eval/outputs --eval.runner.generate-answers=true --eval.runner.exit-on-complete=true"
```

说明：

- `generate-answers=true`：会额外调用默认 LLM 生成回答，之后才能跑 RAGAS
- `exit-on-complete=true`：评测结束后退出 Spring Boot 进程，适合离线任务
- `generate-answers=false`：只做检索、规则和导出，不适合直接跑 RAGAS
- 本地 CPU 跑 reranker 时建议先保留较小候选数；如果后面上 GPU，可以再逐步提高

### 2. 运行 RAGAS

本项目长期使用 RAGAS 0.4.x 的新版 `metrics.collections` API，当前锁定到 `ragas==0.4.3`，避免正式测评时因为依赖漂移导致分数不可复现。

建议使用独立虚拟环境安装：

```powershell
python -m venv .venv-ragas
.\.venv-ragas\Scripts\Activate.ps1
python -m pip install -U pip
python -m pip install -r eval/requirements-ragas.txt
python -c "import ragas; print(ragas.__version__)"
```

配置裁判模型。推荐复制本地配置文件：

```powershell
Copy-Item eval/config/ragas.env.example eval/config/ragas.env
```

然后编辑 `eval/config/ragas.env`，填入你的裁判模型配置。该文件已被 `.gitignore` 忽略，不要提交。

如果使用 OpenAI 或 OpenAI-compatible 网关，常见配置如下：

```text
OPENAI_API_KEY=你的 Key
OPENAI_BASE_URL=https://your-openai-compatible-endpoint/v1
RAGAS_LLM_PROVIDER=openai
RAGAS_LLM_MODEL=gpt-4o-mini
RAGAS_EMBEDDING_PROVIDER=openai
RAGAS_EMBEDDING_MODEL=text-embedding-3-small
RAGAS_EMBEDDING_DIMENSION=1536
RAGAS_TEMPERATURE=0
RAGAS_METRICS=faithfulness,answer_relevancy,context_precision,context_recall
```

如果你的中转站只支持 Chat Completions、不支持 Embeddings，可以先跳过 `answer_relevancy`：

```text
RAGAS_METRICS=faithfulness,context_precision,context_recall
```

如果你现在仍然出现大量超时，建议先用更保守的参数：

```text
RAGAS_CONCURRENCY=1
RAGAS_REQUEST_TIMEOUT_SECONDS=180
RAGAS_METRIC_TIMEOUT_SECONDS=240
RAGAS_MAX_CONTEXTS=4
RAGAS_MAX_CONTEXT_CHARS=2500
RAGAS_MAX_CONTEXT_CHARS_PER_ITEM=800
```

执行脚本：

```powershell
.\eval\scripts\run_ragas_eval.ps1 `
  -InputPath eval/outputs/ragas_input.json `
  -OutputPath eval/outputs/ragas_scores.json
```

也可以直接执行 Python 脚本：

```powershell
python eval/scripts/run_ragas_eval.py `
  --env-file eval/config/ragas.env `
  --input eval/outputs/ragas_input.json `
  --output eval/outputs/ragas_scores.json
```

环境变量优先级高于 `ragas.env`。如果当前终端已经设置了 `OPENAI_API_KEY`、`OPENAI_BASE_URL` 等变量，脚本会优先使用终端里的值。

## 输出文件怎么读

### `eval_result.json`

Java 侧单条 case 明细，包含检索、入库一致性、答案检查、导出用上下文等信息。

### `ragas_scores.json`

RAGAS 原始输出。这个文件可能出现：

- `MISSING`：该 case 没有拿到可用指标
- `PARTIAL`：只算出了一部分指标
- `FULL`：四个指标都完整

### `ragas_summary.json`

RAGAS 自己的汇总，只代表 RAGAS 原始结果。

### `eval_summary_merged.json`

第三期的推荐总入口。它把 Java 检索指标、规则结果、RAGAS 指标和门禁结果合在一起，后续看结论优先以这个文件为准。

### `badcase_analysis.md`

自动生成的坏例分析表，按主标签归类，便于快速定位：

- `PERMISSION_BAD`
- `INDEX_CONSISTENCY_BAD`
- `RETRIEVAL_BAD`
- `CONTEXT_RECALL_BAD`
- `CONTEXT_PRECISION_BAD`
- `GENERATION_BAD`
- `RULE_BAD`
- `RAGAS_SCORE_MISSING`

### `eval_manifest.json`

记录本次 runId、case 集版本、语料版本、配置版本、裁判模型版本，以及主要产物的 hash，方便做版本化回归。

## 配置说明

当前第三期生效配置在 `eval/config/eval-profile.yml`，其中包含：

- `case-set-version`
- `corpus-version`
- `config-version`
- `judge-model-version`
- 各类门禁阈值

`eval/config/thresholds.yml` 只是早期示例，当前代码不读取它，保留它只是为了和旧文档做对照。

## Case 编写规则

推荐使用格式化 JSON 数组，便于人工维护和版本审查。最小字段：

```json
{"caseId":"case_001","caseType":"concept","question":"什么是 CAP 理论？","reference":"CAP 理论指一致性、可用性、分区容错性。","goldFileMd5":"替换为实际文件MD5","goldChunkIds":[1],"userId":"1","orgTag":"admin","expectedAnswerable":true}
```

`goldChunkIds` 必须来自实际入库后的 `document_vectors.chunk_id`，否则 Hit@K、Recall@K、MRR 没有可信意义。

## 结果判读建议

- 如果 `eval_summary_merged.json` 里 `RAGAS Available=false`，先检查 `ragas.env`、模型可达性和 Python 依赖
- 如果 `ragas_scores.json` 大量 `MISSING/PARTIAL`，优先看网络、模型限流和并发设置
- 如果超时集中在 `context_recall`，通常是裁判链路太慢或上下文过长，不是 Java 检索链路本身坏了
- 如果 `hitAt5`、`recallAt10` 正常但 `mustContainBad` 偏高，优先查规则定义而不是先动检索
- 如果 `PERMISSION_BAD` 出现，先处理权限链路，不要把它当普通召回问题
