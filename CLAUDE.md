# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

知序：基于 AI Agent 的自适应个人学习管理系统（中文毕业设计全栈项目，UI、文档、注释均为中文）。由三个独立服务组成：

- `backend/` — Java 17 / Spring Boot 3.3 模块化单体（端口 8080）。业务事务、权限、审计和正式数据（画像、目标、计划、任务、评分、问答记录）的唯一权威。
- `ai-service/` — Python 3.11+ / FastAPI（端口 8090）。无状态：只生成候选内容（画像草稿、目标/计划/任务候选、带引用回答）与 Embedding / Qdrant 检索 / PDF OCR。
- `frontend/` — Vue 3 + TypeScript + Vite（开发端口 5300，代理 `/api` → 8080；生产 Nginx 端口 8088）。

**核心信任边界**（改 AI 链路前必读）：浏览器只访问 Java；Python 不解析用户 JWT，通过 `X-Internal-Token` 头鉴权（`ai-service/app/core/security.py`）。Python 返回的一切（Chunk ID、画像更新、引用、来源）都视为不可信输入，Java 从 MySQL 二次鉴权后才落库或进入模型上下文。Agent 只能生成提案，不能绕过用户确认修改正式计划。完整说明见 `docs/architecture.md`。

## 常用命令

本地配置统一来自仓库根目录 `.env`（已 gitignore，从 `.env.example` 复制并至少修改密码与 `JWT_SECRET`）。两个 PowerShell 启动脚本负责把 `.env` 映射为各服务的环境变量。

### 本地开发

```powershell
# 三个终端分别启动（先准备 Python 环境：cd ai-service; python -m venv .venv; .\.venv\Scripts\python.exe -m pip install -e ".[dev,embeddings]")
.\scripts\start-ai-local.ps1       # Python AI（uvicorn :8090）
.\scripts\start-backend-local.ps1  # Java 后端（spring-boot:run :8080）
cd frontend; npm install; npm run dev
```

### 测试与检查

```powershell
# 后端（必须带 -s maven-settings.xml：它把本地仓库重定向到项目内 .m2repo）
cd backend
mvn.cmd -s maven-settings.xml test
mvn.cmd -s maven-settings.xml test -Dtest=AvailabilityPolicyTest   # 单个测试类

# Python（测试全部离线，不访问真实模型）
cd ai-service
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m pytest tests/test_goals.py -k 用例名  # 单个
.\.venv\Scripts\python.exe -m ruff check app tests
.\.venv\Scripts\python.exe -m mypy app

# 前端
cd frontend
npm run build    # vue-tsc --noEmit 严格类型检查 + Vite 生产构建
npm run test:e2e # Playwright，场景数据在 frontend/e2e/scenarios/*.json
```

### Docker 全栈

```bash
docker compose up --build -d   # MySQL / Redis / Qdrant / ai-service / backend / frontend
```

## 架构

### Java 后端（backend/）

8 个模块，每个内部按 `api / application / domain / infrastructure` 分层：

- `support` — 认证（JWT、邮箱验证码、刷新轮换）、用户、通知、审计、管理端、学习目录
- `profile` — 学习画像、偏好、可用时间、AI 画像访谈、画像版本
- `goalproject` — 目标/项目与状态机
- `planning` — 计划版本/变更项、规划任务、**幂等发布与 Outbox**
- `execution` — 学习任务、计时、笔记、任务辅导会话
- `knowledgebase` — 文档/Chunk、知识空间、问答会话、文档索引任务
- `evaluation` — 评估、掌握度、错题本、报告、分析
- `shared` — 横切：`ai`（Python 客户端）、`security`（JWT）、`ratelimit`、`api`/`exception`（统一 `{success,data,requestId}` 响应与稳定错误码）、`web`（RequestIdFilter）

关键约定：

- `domain/*Entity`（MyBatis-Plus `@TableName`，继承 `BaseEntity`）即领域模型；领域规则放在无状态策略类（`*Policy`，如 `TaskStatusPolicy`、`AvailabilityPolicy`、`MasteryPolicy`）。乐观锁（version）、逻辑删除（deleted_at）、雪花 ID 由 `shared/infrastructure/MybatisConfig.java` 统一处理。
- 数据库结构由 Flyway 管理（V1–V17，`src/main/resources/db/migration/mysql/`），时间统一 UTC 存储。
- 写端点普遍要求 `Idempotency-Key` 头（`planning/application/IdempotencyService.java`）；计划发布在单事务内完成变更、版本切换、审计与 Outbox 写入（`outbox_event` 目前只写不消费）。
- 规划作业异步执行（`planning/application/PlanningJobAsyncConfig.java` + `PlanningJobAsyncTest`）：`POST /goals/{id}/planning-jobs` 提交后立即返回 QUEUED 作业，模型生成在后台线程（读取与模型调用不持事务，写入单事务一次提交）；前端轮询 `GET /planning-jobs/{jobId}`，失败持久化为 FAILED；`GET /goals/{goalId}/planning-jobs` 用于刷新后恢复轮询，遗留超过 30 分钟的作业视为中断自动过期。
- Java→Python 客户端：`shared/ai/PythonAiServiceClient.java`（RestClient + SSE 解析，内部端点全部在 `/internal/v1/`）；`shared/ai/RoutingAiModelClient.java` 在 Python 不可用时回退到旧直接调模型实现。
- 测试：19 个测试类，多数为 Mockito 单元测试；2 个 `@SpringBootTest` + MockMvc + H2（`application-test.yml` 禁用 Flyway，用 `schema.sql`）。集成测试用 `@MockBean` 替换邮件/模型客户端。

### Python AI 服务（ai-service/）

- `app/main.py` 是组合根（`create_app()` 工厂）；`app/config.py` 集中全部 `AI_*` 配置。
- `app/model/client.py` — LangChain ChatOpenAI 适配 OpenAI 兼容模型（`AI_MODEL_BASE_URL`/`AI_MODEL_NAME`/`AI_MODEL_API_KEY`），错误映射为稳定错误码（`AI_RATE_LIMITED`、`AI_MODEL_TIMEOUT` 等）；`app/model/runtime.py` 支持 Java 热切换模型配置。
- `app/rag/` — `embeddings.py`（HuggingFace/Sentence Transformers，`AI_ALLOW_HASH_FALLBACK` 时降级为 `LOCAL_HASHED_384` 并在 `/health/ready` 标记 degraded）；`vector_store.py`（Qdrant，local/server/memory 三种模式，权限过滤在查询阶段下推）；`retrieval.py`（向量+关键词混合重排、多样性选择，证据不足时 `evidenceSufficient=false`）；`answer.py`（严格引用约束，无效引用 → `RAG_FALLBACK` 由 Java 替换为拒答）。
- 画像访谈（`app/profile/`）用 `AssistantMessageProjector` 流式抽取可见 `assistantMessage` 增量，输出校验失败自动修复最多 2 次。
- 计划推荐（`app/plans/`）先并行三路检索证据，模型只能引用真实 `sourceChunkIds`；**排期不在 Python**，Java 确定性排期（每天一个任务、固定 09:00 开始，不受可用时段与容量限制）后由用户确认发布。
- SSE 事件约定（前后端共用）：`message.started` → `message.delta`* → `message.completed` / `message.failed`；问答另有 `citation.ready`（`app/core/sse.py` + `app/api/streaming.py`）。
- 测试全部离线：`tests/fakes.py` 提供 `FakeModelClient`（实现 `ModelClient` 协议）；RAG 测试用 Hash Embedding + Qdrant memory 模式。

### Vue 前端（frontend/）

- UI 全在 `src/views/`（无 components 目录，组件用 Element Plus）；仅一个 Pinia store（`src/stores/auth.ts`，token 镜像到 localStorage）。
- 全部 API 封装在 `src/api/http.ts`：axios 拦截器自动附加 Bearer token、401 单飞刷新重试、`api<T>()` 解包 `{success,data}`；`postSse()` 用 fetch 流式消费 SSE（不是 EventSource，便于带 Authorization 头）。
- 路由按角色守卫（`src/router.ts` 的 meta 标记 + `AppLayout.vue` 切换导航）：`/` 公开宣传页、`/login`、`/app` 下学习者页面、`/admin` 仅 ADMIN。
- Markdown 渲染统一 markdown-it + DOMPurify 消毒（`html:false`）。

## 改动前必读的业务规则

- 画像、偏好或可用时间修改后画像回到 `DRAFT`，必须重新生成画像版本才能再次请求目标推荐；既有目标继续绑定旧版本。
- 计划发布是幂等事务；任务图谱 `/tasks/graph` 是正式计划的只读投影，只有用户时区当天节点可执行，前置任务未完成不能启动后续任务。
- 问答证据不足必须拒答，不能用模型常识补全私有资料中不存在的结论。
- 前端对 `profileVersionId` 等长整型字段必须按字符串处理（避免 JS 精度丢失）。
- 接口调用顺序、状态机与双进度口径等见 `docs/api-workflows.md` 和 `docs/architecture.md`，实现对应各 `*Policy` 类。
