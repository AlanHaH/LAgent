# 需求追踪矩阵

实现依据为《基于AI Agent的自适应个人学习管理系统-详细需求文档》。`VERIFIED` 表示代码已实现且至少有编译、自动化测试或生产构建证据；端到端数据库验收仍需按 `testing.md` 在 Docker 环境执行。

| 需求域 | 后端模块 | 前端入口 | 关键保证 | 状态 |
|---|---|---|---|---|
| FR-SUP-001~010 | `support`, `shared.security` | 登录、账户设置、系统管理 | JWT 轮换、失败锁定、RBAC、审计、无客户端 userId | VERIFIED |
| FR-PRO-001~008 | `profile` | 学习画像 | 时间重叠/跨午夜、容量偏好、版本、自评证据 | VERIFIED |
| FR-GPM-001~007 | `goalproject` | 目标与项目 | 生命周期、里程碑、关联、进度、依赖 DAG | VERIFIED |
| FR-AGT-001~009 | `planning` | Agent 计划 | 提案隔离、85% 容量、版本、校验、确认、幂等事务发布 | VERIFIED |
| FR-RAG-001~009 | `knowledgebase` | 知识库、知识问答 | 文件校验、租户过滤、混合检索、引用、拒答、确认删除 | VERIFIED |
| FR-EXE-001~008 | `execution` | 今日执行 | 生命周期/排期状态分离、计时、跨日分摊、笔记版本、辅导 | VERIFIED |
| FR-EVA-001~010 | `evaluation` | 评估与错题、学习分析 | 暂存、幂等交卷、评分快照、掌握度、置信度、指标与报告 | VERIFIED |
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
- 注册、JWT 访问和匿名拒绝：`AuthIntegrationTest`
