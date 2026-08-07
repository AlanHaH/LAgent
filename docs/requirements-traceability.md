# 需求追踪矩阵

实现依据为 V1.2《基于AI Agent的自适应个人学习管理系统-详细需求文档》和 V1.4《Python AI服务详细需求文档》，并以可运行代码作为最终事实来源。文档已于 2026-08-02 同步异步 AI 作业、容量自适应排期、可靠 Outbox、评分申诉、文件安全与删除补偿、CI 门禁等完整性收口。`VERIFIED` 表示代码已实现且至少有编译、自动化测试、生产构建或本地 API 验收证据；完整 Docker 端到端验收仍需按 `testing.md` 执行。

| 需求域 | 后端模块 | 前端入口 | 关键保证 | 状态 |
|---|---|---|---|---|
| 前端产品导览（体验增强） | 无，纯前端 | `/` 宣传首页 | 匿名访问、五项能力切换、路径预览不落库、登录/注册分流、响应式与减弱动画适配 | VERIFIED |
| FR-SUP-001~010 | `support`, `shared.security` | 登录、注册、找回密码、账户设置、系统管理 | 邮箱验证、密码重置、JWT 轮换、失败锁定、RBAC、审计、无客户端 userId | VERIFIED |
| FR-PRO-001~008 | `profile` | 学习画像 | SSE 对话草稿、用户确认落库、时间重叠/跨午夜、容量偏好、版本、自评证据 | VERIFIED |
| FR-GPM-001~007 | `goalproject` | 目标与项目 | 自定义/画像推荐双入口、推荐确认、画像版本来源快照、目录与用户自定义方向双轨、生命周期、里程碑、关联、进度、依赖 DAG | VERIFIED |
| FR-AGT-001~009 | `planning` | Agent 计划 | AI 内容候选、知识空间显式选择、活动文档快照、Chunk 来源二次鉴权与任务引用、基于周时段/例外日/容量偏好的确定性排期、部分采纳、提案隔离、版本、校验、确认、幂等事务发布、可靠 Outbox、反馈触发优化 | VERIFIED |
| FR-RAG-001~009 | `knowledgebase` | 知识库、知识问答 | 层级资料分类、MIME/结构/压缩炸弹/可选病毒扫描、150MB 异步处理、空间/文档补偿删除、租户过滤、混合检索、引用、拒答、工具调用审计 | VERIFIED |
| PAI-GOAL-001~009 | `ai-service`, `shared.ai`, `knowledgebase`, `profile`, `goalproject` | 学习画像、目标推荐、知识问答 | Python模型网关/画像/目标候选/计划任务候选/Embedding/Qdrant/RAG，推荐批次持久化与只读恢复、显式重新推荐，Java二次校验、目录/自定义方向双轨，画像和问答真实 SSE | VERIFIED |
| FR-EXE-001~008 | `execution` | 任务图谱与执行 | 已发布 AI 计划任务/依赖图谱、仅当天节点开放、生命周期/排期状态分离、前置任务校验、计时、跨日分摊、笔记版本、完整历史持久化及 400 条/60 万字符上下文预算 | VERIFIED |
| FR-EVA-001~010 | `evaluation` | 评估与错题、学习分析 | 计算机/经济学公共题种子、暂存、幂等交卷、异步批改/重试、逐题评分版本、错题订正、学习者申诉/管理员复核、掌握度重算与趋势、每日统计和报告 | VERIFIED |
| 管理治理（FR-SUP 扩展） | `support.application.AdminService`, `support.api.AdminController` | `/admin` 七个工作区 | ADMIN 路由隔离、权限点校验、自锁保护、目录依赖环校验、题库发布、模型/提示词版本治理、运行记录、审计筛选 | VERIFIED |
| NFR 安全 | `shared.security`, 全模块 ownership 查询 | 全局路由与错误处理 | BCrypt、JWT、资源归属、XSS 清理、文件隔离、公开标识 | VERIFIED |
| NFR 可运维 | Actuator、OpenAPI、Flyway、Outbox、CI | 管理端总览、运行记录、审计日志 | requestId、统一错误、健康检查、异步作业状态、Outbox 重试/死信、删除失败补偿、真实 MySQL 迁移测试、前后端与 AI 静态/测试门禁 | VERIFIED |

## 关键需求到测试的对应

- 可用时间、跨午夜和重叠：`AvailabilityPolicyTest`
- 目标/项目状态机：`StateMachineTest`
- 依赖环：`DependencyGraphPolicyTest`
- 任务状态合法性：`TaskStatusPolicyTest`
- 计划容量、时长、取消任务不占容量和发布前校验：`PlanValidationPolicyTest`
- 学习计时跨日切分：`StudySessionAllocationTest`
- 总览双进度：后端测试集与 2026-07-28 本地 MySQL/API 验收；半程账号返回总体进度 `294/588=50%`、近 7 天完成率 `1/1=100%`
- 掌握度缺失分量与自评置信度：`MasteryPolicyTest`
- 文件名、切块、检索工具：`KnowledgeUtilitiesTest`
- Python内部接口、画像投影、Embedding/Qdrant权限过滤、知识库计划 Chunk 白名单、长任务对话上下文和引用降级：`ai-service/tests`
- Java到 Python 的内部令牌、JSON 与 SSE 契约：`PythonAiServiceClientTest`
- 注册邮箱验证、更换邮箱、找回密码、JWT 访问和匿名拒绝：`AuthIntegrationTest`
- 验证码一次性消费与错误次数：`EmailVerificationServiceTest`
- 宣传首页路由、能力切换、约束调整、路径生成和注册跳转：前端生产构建及浏览器交互验收
