# 知序 Python AI 服务

本服务承接模型调用、画像结构化生成、Embedding、Qdrant 检索和 RAG 答案生成。Spring Boot 仍负责用户认证、权限、业务事务、正式画像与计划写入。

详细契约见 [`../docs/Python AI服务详细需求文档.md`](../docs/Python%20AI服务详细需求文档.md)。

## 本地启动

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev,embeddings]"
$env:AI_INTERNAL_TOKEN="replace-with-at-least-32-random-characters"
$env:AI_MODEL_BASE_URL="https://api.deepseek.com"
$env:AI_MODEL_API_KEY="你的密钥"
$env:AI_MODEL_NAME="deepseek-v4-flash"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

本地默认使用 Qdrant Local Mode。若 Sentence Transformers 未安装或模型暂时不可下载，并且允许本地降级，服务会使用可复现哈希向量并在健康状态中标记降级。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m ruff check app tests
```

离线检索结果可按 JSONL 提供 `caseId/relevantChunkIds/predictedChunkIds`，计算 Recall@K 和 MRR：

```powershell
.\.venv\Scripts\python.exe -m app.evaluation .\evaluation-results.jsonl
```
