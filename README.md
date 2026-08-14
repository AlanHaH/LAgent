# 知序：基于 AI Agent 的自适应个人学习管理系统

[![CI](https://github.com/AlanHaH/LAgent/actions/workflows/ci.yml/badge.svg)](https://github.com/AlanHaH/LAgent/actions/workflows/ci.yml)

> 把学习画像、目标、计划、资料、执行和评估连接成一个可确认、可追踪、可复盘的学习闭环。

一个可直接运行的前后端毕业设计项目。公开宣传首页先以可交互的方式介绍产品能力，登录后系统再把学习画像、目标/项目、受约束计划提案、个人知识库、执行计时、诊断评估和数据优化组织为完整闭环。

Agent 的边界是系统设计的一部分：它可以生成提案、解释与引用，但不能绕过用户确认直接修改正式计划；状态机、权限、版本与发布事务都由确定性后端规则控制。学习资料闭环聚焦 PDF、DOCX、Markdown、TXT 等文档型内容，不把视频或音频进度纳入掌握度、问答证据和学习报告统计。

## 项目亮点

- **AI 学习画像与目标推荐**：通过对话建立学习画像，生成目标候选，并在用户确认后进入正式学习路径。
- **受约束的 AI 计划**：结合可用时间、例外日期、任务容量和前置依赖生成计划，支持版本、差异校验、二次确认与发布。
- **知识库与 OCR**：支持文档上传、结构解析、OCR、切块、向量索引、混合检索和带引用问答；证据不足时拒答。
- **微信读书接入**：通过 MCP 同步书架与阅读内容，支持离线 Fake 模式，便于本地演示和测试。
- **学习块闭环**：将学习内容拆成可执行的学习块，配套块内练习与块测，并按规则推进解锁。
- **执行、评估与管理**：覆盖任务计时、知识图谱、诊断评估、错题订正、学习报告，以及模型和提示词治理。

## 技术栈

- 业务后端：Java（JDK 21 开发、Java 17 字节码）、Spring Boot 3.3、Spring Security、MyBatis-Plus、Flyway、MySQL 8、Redis、JWT、OpenAPI
- AI 服务：Python 3.11～3.13、FastAPI、LangChain ChatOpenAI/PromptTemplate、LangChain HuggingFace Embeddings、Sentence Transformers、Qdrant、Pydantic、SSE
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、ECharts、Markdown-it、DOMPurify
- 部署：Docker Compose、Nginx、Qdrant、健康检查、持久化卷

## 目录

- `backend/`：按 `api / application / domain / infrastructure` 分层的模块化单体
- `ai-service/`：模型网关、画像结构化生成、Embedding、Qdrant 检索和 RAG 答案服务
- `frontend/`：学习者端与基于角色显示的管理员端
- `docs/`：架构、接口工作流、测试、部署与需求追踪等开发文档
- `毕业论文/`：论文归档与写作素材（章节、插图、业务设计说明书、用户手册等，已 gitignore）
- `docker-compose.yml`：MySQL、Redis、Qdrant、Python AI、Java 后端和前端完整环境

## 快速启动

日常开发默认按[本地启动指南](docs/本地启动指南.md)在 Windows 本机手动启动，不使用 Docker：

1. 复制 `.env.example` 为 `.env`，至少修改两个 MySQL 密码、`JWT_SECRET`、`AI_INTERNAL_TOKEN` 和 `QDRANT_API_KEY`。生产环境不要使用示例值，也不要复用这些密钥。
2. 按[本地启动指南](docs/本地启动指南.md)依次启动 MySQL / Memurai、Python AI、Spring Boot 和前端。
3. 打开 `http://localhost:5300`。后端 OpenAPI 在开发直连模式下位于 `http://localhost:8080/swagger-ui.html`。

只有明确需要完整容器环境时，才使用 Docker：

```bash
docker compose up --build -d
```

Docker 模式启动后，打开 `http://localhost:8088` 进入公开宣传首页；登录页为 `http://localhost:8088/login`。

只有设置了 `APP_ADMIN_PASSWORD` 才会初始化管理员。普通用户可从登录页注册。

## 本地开发

本地依赖：JDK 21、Maven 3.6.3+、Python 3.11～3.13、MySQL 8，以及监听 `127.0.0.1:6379` 的 Redis 7 兼容服务。Windows 无 Docker 开发可使用 Memurai Developer；Redis 不可用时限流会退化为单进程计数，核心数据仍以 MySQL 为准。Python 本地默认使用嵌入式 Qdrant，不要求单独启动 6333 端口。

首次准备 Python AI 环境：

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[dev,embeddings]"
cd ..
```

在两个终端先后启动 Python AI 服务和 Java 后端。两个脚本都读取仅保存在本机、且已被 Git 忽略的 `.env`：

```powershell
.\scripts\start-ai-local.ps1
```

```powershell
.\scripts\start-backend-local.ps1
```

若 PowerShell 执行策略阻止 `.ps1`，使用 `powershell -ExecutionPolicy Bypass -File .\scripts\start-ai-local.ps1`（后端脚本同理）。

后端测试仍可单独执行：

```powershell
cd backend
mvn.cmd -s maven-settings.xml test
```

```bash
cd frontend
npm install
npm run dev
```

数据库结构由 Flyway 自动创建，时间字段统一以 UTC 保存。前端开发服务器监听 `http://localhost:5300`，并把 `/api` 代理到 `localhost:8080`。
后端构建要求 Maven 3.6.3 或更高版本；Docker 构建固定使用 Maven 3.9.9。

### 前端访问入口

| 路径 | 访问要求 | 用途 |
|---|---|---|
| `/` | 匿名可访问 | 产品宣传、功能切换和本地学习路径预览；预览数据不会保存 |
| `/login` | 匿名可访问 | 登录、邮箱验证码注册与找回密码 |
| `/dashboard` | 需要登录 | 学习总览和登录后工作台入口；同时展示当前目标总体进度与近 7 天任务完成率 |

宣传首页会根据登录状态显示“开始构建路径”或“进入工作台”。“开始构建路径”会直接打开注册模式；登录页也提供返回产品首页的入口。

新用户注册、忘记密码和更换邮箱依赖 SMTP。请在 `.env` 配置 `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME`、`MAIL_PASSWORD`（通常是邮箱服务商生成的 SMTP 授权码）和 `MAIL_FROM`；端口 587 默认使用 STARTTLS，端口 465 请改为 SSL。未配置邮件服务时，现有用户仍可登录，但发码接口会明确提示邮件服务尚未配置。

AI 画像访谈、画像驱动的目标推荐与知识问答由 Python 服务统一调用 OpenAI Chat Completions 兼容接口。DeepSeek 示例配置为 `MODEL_BASE_URL=https://api.deepseek.com`、`MODEL_NAME=deepseek-v4-flash`；Java 通过 `AI_SERVICE_BASE_URL` 和独立的 `AI_INTERNAL_TOKEN` 访问 Python，浏览器不能直连。真实 `MODEL_API_KEY` 只能写入 `.env`。模型或 Python 不可用时，画像切换为 Java 规则引导，目标推荐切换为确定性规则候选，问答按配置使用旧 Java 检索或已授权资料片段降级。

## 已实现的业务闭环

1. 邮箱验证码注册、忘记密码、邮箱变更验证、JWT 刷新轮换、失败锁定、RBAC、资源归属与审计。
2. AI 对话式画像访谈、用户确认后结构化落库，以及高级手动画像的原子保存与访谈草稿同步；支持自定义起止日期、7/14/30 天快捷周期、偏好、跨午夜可用时间、例外日、自评与画像版本。
3. 自定义目标与基于当前画像版本的 AI 目标推荐；推荐批次会持久化，刷新或再次进入目标页仍显示最近结果，只有用户明确点击重新推荐才再次调用模型。目标同时支持管理员学习目录方向和用户自定义方向，目录外方向可原样进入推荐、目标和 AI 计划。
4. AI 生成学习内容、基于每周可用时间/例外日/容量偏好的确定性排期、不可变计划版本、差异校验、部分采纳、二次确认、幂等发布和可靠 Outbox；完成情况与反馈会触发带冷却期的优化建议，正式任务只在用户再次确认发布后改变。
5. 文档驱动的私有知识空间、层级资料分类、结构与病毒扫描、150MB 文档异步解析/切块/向量索引、混合检索、带引用问答、证据不足拒答，以及先标记后异步清理的知识空间/文档安全删除。
6. 已发布 AI 计划驱动的任务知识图谱（完整路径可见、仅当天节点开放），以及任务生命周期与排期状态分离、前置任务约束、跨日计时分摊、笔记版本、学习总结和持久化任务辅导会话。辅导历史完整落库，单次模型调用默认装载最近 400 条、最多 60 万字符。
7. 诊断/评估、答案暂存、异步主观题批改与重试、幂等交卷、逐题评分历史、错题订正、评分申诉与管理员复核、掌握度重算/历史趋势、每日学习汇总和修订版报告；学习总览同时提供按预计分钟加权的当前目标总体进度，以及按任务数统计的近 7 天完成率。
8. 通知去重与偏好，以及完整管理端：用户状态与角色、学习方向/知识点/依赖、公共题库、模型与提示词治理、规划/文档/模型运行记录、审计日志、作业监控和系统指标。

## 文档导航

### 面向使用者（论文素材，归档于 `毕业论文/`）

- [详细用户使用手册](毕业论文/素材/docs/用户使用手册.md)：从启动、注册、画像、目标、计划、执行、知识库、真实 AI 问答到评估分析的逐步操作，以及常见故障处理。
- [业务设计说明书](毕业论文/素材/docs/业务设计说明书.md)：产品定位、角色边界、核心流程、状态机、数据规则、AI 使用矩阵、异常降级和当前实现限制。
- [小白项目全景指南](毕业论文/素材/docs/小白项目全景指南.md)：用通俗方式解释 Vue、Spring Boot、Python AI、MySQL、Redis、Qdrant、文件处理和 DeepSeek 如何协作。

### 面向开发与部署

- [本地启动指南](docs/本地启动指南.md)：本机原生 MySQL / Memurai、Qdrant 内嵌模式下的启动步骤与常见问题。
- [部署文档](docs/deployment.md)：环境配置、Docker、数据、备份与生产建议。
- [接口工作流](docs/api-workflows.md)：画像到计划、知识库问答、执行评估的主要 API 调用顺序。
- [架构说明](docs/architecture.md)：模块化单体和核心安全边界。
- [测试说明](docs/testing.md)：自动化测试、端到端验收和关键边界。
- [需求追踪矩阵](docs/requirements-traceability.md)：需求域到后端模块、前端入口和测试保证的对应关系。

### 论文素材速查

- [Python AI 服务详细需求文档](毕业论文/素材/docs/Python AI服务详细需求文档.md)：AI 服务需求规格（V1.4），含内部 API 契约与降级策略。
- [学习块闭环设计](毕业论文/素材/docs/knowledge-block-learning-loop.md)：学习块生成、块内练习与块测、70 分解锁规则及对应数据表。

当前 AI 实现口径：Python 负责通过 LangChain ChatOpenAI 适配 OpenAI 兼容模型流、PromptTemplate 管理提示词、画像候选更新、LangChain HuggingFace Embeddings/Sentence Transformers 向量化、Qdrant 权限过滤检索、任务辅导和带引用答案；Java 负责 JWT/RBAC、MySQL 权威 Chunk、二次鉴权、草稿合并、正式画像确认、问答落库、审计和业务事务。画像与知识问答都是真实 SSE。未安装/无法加载专业 Embedding 且显式允许时会降级为 `LOCAL_HASHED_384` 并在健康状态标明；旧 Java `LOCAL_HASHED_128` 仅作为迁移回滚路径。
