# 知序：基于 AI Agent 的自适应个人学习管理系统

一个可直接运行的前后端毕业设计项目。公开宣传首页先以可交互的方式介绍产品能力，登录后系统再把学习画像、目标/项目、受约束计划提案、个人知识库、执行计时、诊断评估和数据优化组织为完整闭环。

Agent 的边界是系统设计的一部分：它可以生成提案、解释与引用，但不能绕过用户确认直接修改正式计划；容量、状态机、权限、版本与发布事务都由确定性后端规则控制。学习资料闭环聚焦 PDF、DOCX、Markdown、TXT 等文档型内容，不把视频或音频进度纳入掌握度、问答证据和学习报告统计。

## 技术栈

- 业务后端：Java（JDK 21 开发、Java 17 字节码）、Spring Boot 3.3、Spring Security、MyBatis-Plus、Flyway、MySQL 8、Redis、JWT、OpenAPI
- AI 服务：Python 3.11～3.13、FastAPI、HTTPX、Sentence Transformers、Qdrant、Pydantic、SSE
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、ECharts、Markdown-it、DOMPurify
- 部署：Docker Compose、Nginx、Qdrant、健康检查、持久化卷

## 目录

- `backend/`：按 `api / application / domain / infrastructure` 分层的模块化单体
- `ai-service/`：模型网关、画像结构化生成、Embedding、Qdrant 检索和 RAG 答案服务
- `frontend/`：学习者端与基于角色显示的管理员端
- `docs/`：架构、接口工作流、测试、部署与需求追踪
- `docker-compose.yml`：MySQL、Redis、Qdrant、Python AI、Java 后端和前端完整环境

## 快速启动

1. 复制 `.env.example` 为 `.env`，至少修改三个密码与 `JWT_SECRET`。生产环境不要使用示例值。
2. 启动：

   ```bash
   docker compose up --build -d
   ```

3. 打开 `http://localhost:8088` 进入公开宣传首页；登录页为 `http://localhost:8088/login`。后端 OpenAPI 在开发直连模式下位于 `http://localhost:8080/swagger-ui.html`。

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
mvn -s maven-settings.xml test
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
| `/dashboard` | 需要登录 | 学习总览和登录后工作台入口 |

宣传首页会根据登录状态显示“开始构建路径”或“进入工作台”。“开始构建路径”会直接打开注册模式；登录页也提供返回产品首页的入口。

新用户注册、忘记密码和更换邮箱依赖 SMTP。请在 `.env` 配置 `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME`、`MAIL_PASSWORD`（通常是邮箱服务商生成的 SMTP 授权码）和 `MAIL_FROM`；端口 587 默认使用 STARTTLS，端口 465 请改为 SSL。未配置邮件服务时，现有用户仍可登录，但发码接口会明确提示邮件服务尚未配置。

AI 画像访谈、画像驱动的目标推荐与知识问答由 Python 服务统一调用 OpenAI Chat Completions 兼容接口。DeepSeek 示例配置为 `MODEL_BASE_URL=https://api.deepseek.com`、`MODEL_NAME=deepseek-v4-flash`；Java 通过 `AI_SERVICE_BASE_URL` 和独立的 `AI_INTERNAL_TOKEN` 访问 Python，浏览器不能直连。真实 `MODEL_API_KEY` 只能写入 `.env`。模型或 Python 不可用时，画像切换为 Java 规则引导，目标推荐切换为确定性规则候选，问答按配置使用旧 Java 检索或已授权资料片段降级。

## 已实现的业务闭环

1. 邮箱验证码注册、忘记密码、邮箱变更验证、JWT 刷新轮换、失败锁定、RBAC、资源归属与审计。
2. AI 对话式画像访谈、用户确认后结构化落库、自定义起止日期、偏好、跨午夜可用时间、例外日、自评与画像版本。
3. 自定义目标与基于当前画像版本的 AI 目标推荐、用户确认落库、目标/项目/里程碑生命周期、目标项目关联、进度和依赖 DAG 校验。
4. 85% 容量约束的 Agent 计划提案、不可变版本、校验、差异、二次确认、幂等发布、Outbox 和重排提案。
5. 文档驱动的私有知识空间、文件安全校验、解析、切块、混合检索、带引用问答、证据不足拒答和确认删除。
6. 任务生命周期与排期状态分离、跨日计时分摊、笔记版本、学习总结和辅导会话。
7. 诊断/评估、答案暂存、幂等交卷、客观评分、错题本、掌握度与置信度、统一口径统计和修订版报告。
8. 通知去重与偏好、计算机/经济学演示目录、题库/模型/提示词/用户状态管理、作业监控和系统指标。

## 文档导航

### 面向使用者

- [详细用户使用手册](docs/用户使用手册.md)：从启动、注册、画像、目标、计划、执行、知识库、真实 AI 问答到评估分析的逐步操作，以及常见故障处理。
- [业务设计说明书](docs/业务设计说明书.md)：产品定位、角色边界、核心流程、状态机、数据规则、AI 使用矩阵、异常降级和当前实现限制。
- [小白项目全景指南](docs/小白项目全景指南.md)：用通俗方式解释 Vue、Spring Boot、Python AI、MySQL、Redis、Qdrant、文件处理和 DeepSeek 如何协作。

### 面向开发与部署

- [部署文档](docs/deployment.md)：环境配置、Docker、数据、备份与生产建议。
- [接口工作流](docs/api-workflows.md)：画像到计划、知识库问答、执行评估的主要 API 调用顺序。
- [架构说明](docs/architecture.md)：模块化单体和核心安全边界。
- [测试说明](docs/testing.md)：自动化测试、端到端验收和关键边界。
- [需求追踪矩阵](docs/requirements-traceability.md)：需求域到后端模块、前端入口和测试保证的对应关系。

当前 AI 实现口径：Python 负责模型流、画像候选更新、Sentence Transformers Embedding、Qdrant 权限过滤检索和带引用答案；Java 负责 JWT/RBAC、MySQL 权威 Chunk、二次鉴权、草稿合并、正式画像确认、问答落库、审计和业务事务。画像与知识问答都是真实 SSE。未安装/无法加载专业 Embedding 且显式允许时会降级为 `LOCAL_HASHED_384` 并在健康状态标明；旧 Java `LOCAL_HASHED_128` 仅作为迁移回滚路径。
