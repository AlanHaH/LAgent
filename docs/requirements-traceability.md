# 需求追踪矩阵

实现依据为 V1.1《基于AI Agent的自适应个人学习管理系统-详细需求文档》和 V1.3《Python AI服务详细需求文档》，二者已于 2026-07-24 同步当前 Java/Python/Qdrant 架构、画像驱动目标推荐链路与推荐目标保存修复。`VERIFIED` 表示代码已实现且至少有编译、自动化测试或生产构建证据；端到端数据库验收仍需按 `testing.md` 在 Docker 环境执行。

| 需求域 | 后端模块 | 前端入口 | 关键保证 | 状态 |
|---|---|---|---|---|
| 前端产品导览（体验增强） | 无，纯前端 | `/` 宣传首页 | 匿名访问、五项能力切换、路径预览不落库、登录/注册分流、响应式与减弱动画适配 | VERIFIED |
| FR-SUP-001~010 | `support`, `shared.security` | 登录、注册、找回密码、账户设置、系统管理 | 邮箱验证、密码重置、JWT 轮换、失败锁定、RBAC、审计、无客户端 userId | VERIFIED |
| FR-PRO-001~008 | `profile` | 学习画像 | SSE 对话草稿、用户确认落库、时间重叠/跨午夜、容量偏好、版本、自评证据 | VERIFIED |
| FR-GPM-001~007 | `goalproject` | 目标与项目 | 自定义/画像推荐双入口、推荐确认、画像版本来源快照、目录方向约束、生命周期、里程碑、关联、进度、依赖 DAG | VERIFIED |
| FR-AGT-001~009 | `planning` | Agent 计划 | 提案隔离、85% 容量、版本、校验、确认、幂等事务发布 | VERIFIED |
| FR-RAG-001~009 | `knowledgebase` | 知识库、知识问答 | 文件校验、租户过滤、混合检索、引用、拒答、确认删除 | VERIFIED |
| PAI-GOAL-001~009 | `ai-service`, `shared.ai`, `knowledgebase`, `profile`, `goalproject` | 学习画像、目标推荐、知识问答 | Python模型网关/画像/目标候选/Embedding/Qdrant/RAG，内部令牌，Java二次校验、目录映射与规则降级，画像和问答真实 SSE | VERIFIED |
| FR-EXE-001~008 | `execution` | 今日执行 | 生命周期/排期状态分离、计时、跨日分摊、笔记版本、辅导 | VERIFIED |
| FR-EVA-001~010 | `evaluation` | 评估与错题、学习分析 | 计算机/经济学公共题种子、暂存、幂等交卷、评分快照、掌握度、置信度、指标与报告 | VERIFIED |
| NFR 安全 | `shared.security`, 全模块 ownership 查询 | 全局路由与错误处理 | BCrypt、JWT、资源归属、XSS 清理、文件隔离、公开标识 | VERIFIED |
| NFR 可运维 | Actuator、OpenAPI、Flyway、Outbox | 管理员监控 | requestId、统一错误、健康检查、作业状态、持久化卷 | VERIFIED |

## 关键需求到测试的对应

- 可用时间、跨午夜和重叠：`AvailabilityPolicyTest`
- 目标/项目状态机：`StateMachineTest`
- 依赖环：`DependencyGraphPolicyTest`
- 任务状态合法性：`TaskStatusPolicyTest`
- 计划容量、时长和发布前校验：`PlanValidationPolicyTest`
- 学习计时跨日切分：`StudySessionAllocationTest`
- 掌握度缺失分量与自评置信度：`MasteryPolicyTest`
- 文件名、切块、检索工具：`KnowledgeUtilitiesTest`
- Python内部接口、画像投影、Embedding/Qdrant权限过滤和引用降级：`ai-service/tests`
- Java到 Python 的内部令牌、JSON 与 SSE 契约：`PythonAiServiceClientTest`
- 注册邮箱验证、更换邮箱、找回密码、JWT 访问和匿名拒绝：`AuthIntegrationTest`
- 验证码一次性消费与错误次数：`EmailVerificationServiceTest`
- 宣传首页路由、能力切换、约束调整、路径生成和注册跳转：前端生产构建及浏览器交互验收
