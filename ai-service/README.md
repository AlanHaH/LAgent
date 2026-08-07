# 知序 Python AI 服务

本服务承接模型调用、画像结构化生成、目标/计划/任务辅导候选生成、Embedding、Qdrant 检索和 RAG 答案生成。模型侧使用 LangChain ChatOpenAI 适配 OpenAI 兼容接口，提示词通过 PromptTemplate 管理，Embedding 默认走 LangChain HuggingFaceEmbeddings + Sentence Transformers。Spring Boot 仍负责用户认证、权限、业务事务、正式画像、目标、计划与问答记录写入。

详细契约见 [`../毕业论文/素材/docs/Python AI服务详细需求文档.md`](../毕业论文/素材/docs/Python%20AI服务详细需求文档.md)。

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

本地默认使用 Qdrant Local Mode。若 LangChain HuggingFace/Sentence Transformers 未安装或模型暂时不可下载，并且允许本地降级，服务会使用可复现哈希向量并在健康状态中标记降级。

扫描版 PDF 使用 `PyMuPDF + RapidOCR + ONNX Runtime` 逐页识别。知识库先用 Tika 提取已有文字层，仅在 PDF 没有可用文字时调用 OCR；默认限制为 50 MB、100 页，可通过 `AI_OCR_ENABLED`、`AI_OCR_MAX_FILE_MB`、`AI_OCR_MAX_PAGES`、`AI_OCR_DPI` 调整。

计划推荐支持 Java 已授权的知识空间和活动文档版本：Python 用目标、目录结构与练习/验收三个查询并行检索，模型只能引用检索结果中的真实 `chunkId`。Java 发布前会再次从 MySQL 校验 Chunk、文档版本和用户权限，并把资料来源随正式任务保存。任务辅导的完整会话保存在业务库；Java 默认按最近 400 条、总计不超过 600000 字符构造模型上下文，给当前问题、检索证据和模型输出保留余量。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest
.\.venv\Scripts\python.exe -m ruff check app tests
```

离线检索结果可按 JSONL 提供 `caseId/relevantChunkIds/predictedChunkIds`，计算 Recall@K 和 MRR：

```powershell
.\.venv\Scripts\python.exe -m app.evaluation .\evaluation-results.jsonl
```
