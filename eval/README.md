# ArchiveMind 二期离线 RAG 测评

本目录用于运行 `Contextual Retrieval 二期测评技术方案` 中定义的离线评测闭环。

## 目录说明

```text
eval/
  cases/
    rag_eval_cases.json         # 格式化评测问题集
  config/
    eval-profile.yml            # 示例评测配置
    ragas.env.example           # RAGAS 本地配置模板
    thresholds.yml              # 示例阈值
  outputs/
    eval_result.json            # Java EvalRunner 明细输出
    eval_report.csv             # 指标 CSV
    eval_report.md              # Markdown 报告
    ragas_input.json            # RAGAS 输入
    ragas_scores.json           # RAGAS 输出
  requirements-ragas.txt        # RAGAS Python 依赖锁定
  scripts/
    run_ragas_eval.py           # RAGAS 评分脚本
    run_ragas_eval.ps1          # Windows PowerShell 启动脚本
```

## 运行 Java EvalRunner

EvalRunner 默认关闭。确认 MySQL、ES、Redis、Reranker、Embedding 服务可用，并且评测语料已经入库后运行：

```powershell
.\mvnw.cmd -Dmaven.test.skip=true spring-boot:run `
  "-Dspring-boot.run.arguments=--eval.runner.enabled=true --eval.runner.cases-path=eval/cases/rag_eval_cases.json --eval.runner.output-dir=eval/outputs --eval.runner.generate-answers=false --eval.runner.exit-on-complete=true"
```

说明：

- `generate-answers=false`：只跑入库一致性、检索指标和 RAGAS 输入导出，不调用回答 LLM。
- `generate-answers=true`：额外调用默认 LLM 生成回答，之后可以运行 RAGAS。
- `exit-on-complete=true`：评测结束后退出 Spring Boot 进程，适合命令行离线任务。
- 本地 CPU 跑 reranker 时建议保留默认 `RERANKER_TIMEOUT_SECONDS=60`、`RERANKER_MAX_CANDIDATES=20`、`RERANKER_MAX_DOCUMENT_CHARS=1600`；如果部署到 GPU，可以逐步提高候选数量。

## 运行 RAGAS

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

默认走 OpenAI：

```text
OPENAI_API_KEY=你的 OpenAI Key
RAGAS_LLM_PROVIDER=openai
RAGAS_LLM_MODEL=gpt-4o-mini
RAGAS_EMBEDDING_PROVIDER=openai
RAGAS_EMBEDDING_MODEL=text-embedding-3-small
RAGAS_EMBEDDING_DIMENSION=1536
RAGAS_TEMPERATURE=0
RAGAS_METRICS=faithfulness,answer_relevancy,context_precision,context_recall
```

如使用 OpenAI-compatible 网关，可以额外设置：

```text
OPENAI_API_KEY=你的网关 Key
OPENAI_BASE_URL=https://your-openai-compatible-endpoint/v1
RAGAS_LLM_MODEL=你的裁判模型
RAGAS_EMBEDDING_MODEL=你的 embedding 模型
```

如果你的中转站只支持 Chat Completions、不支持 Embeddings，可以先跳过 `answer_relevancy`，只跑不依赖 embedding 的指标：

```text
RAGAS_METRICS=faithfulness,context_precision,context_recall
```

然后执行：

```powershell
.\eval\scripts\run_ragas_eval.ps1 `
  -InputPath eval/outputs/ragas_input.json `
  -OutputPath eval/outputs/ragas_scores.json
```

如果你有另一个支持 OpenAI Embeddings API 的服务，可以在 `ragas.env` 中单独配置：

```text
RAGAS_EMBEDDING_API_KEY=你的 embedding key
RAGAS_EMBEDDING_BASE_URL=https://your-embedding-endpoint/v1
RAGAS_EMBEDDING_MODEL=text-embedding-3-small
RAGAS_EMBEDDING_DIMENSION=2048
```

然后执行 PowerShell 启动脚本：

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

环境变量优先级高于 `ragas.env`。如果你已经在当前终端设置了 `OPENAI_API_KEY`、`OPENAI_BASE_URL` 等变量，脚本会优先使用终端里的值。

默认运行 4 个指标：

- `faithfulness`：回答是否被召回上下文支撑。
- `answer_relevancy` / `response_relevancy`：回答是否切题。
- `context_precision`：召回上下文排序中，越靠前是否越有用。
- `context_recall`：召回上下文是否覆盖参考答案需要的信息。

如果没有开启 `generate-answers=true`，`ragas_input.json` 中的 `response` 会为空，此时不建议运行 RAGAS。脚本会逐 case 输出错误，不会因为某一条坏样本中断整批测评。

## Case 编写规则

推荐使用格式化 JSON 数组，便于人工维护和版本审查。最小字段：

```json
{"caseId":"case_001","caseType":"concept","question":"什么是 CAP 理论？","reference":"CAP 理论指一致性、可用性、分区容错性。","goldFileMd5":"替换为实际文件MD5","goldChunkIds":[1],"userId":"1","orgTag":"admin","expectedAnswerable":true}
```

`goldChunkIds` 必须来自实际入库后的 `document_vectors.chunk_id`，否则 Hit@K、Recall@K、MRR 没有可信意义。
