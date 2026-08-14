# 接口工作流

所有业务接口位于 `/api/v1`，成功响应统一为 `{ success, data, requestId }`；失败响应包含稳定错误码与 `requestId`。注册/重置发码、注册、登录、密码重置和刷新允许匿名访问，其余接口使用 `Authorization: Bearer <accessToken>`。

## 注册与找回密码

1. `POST /auth/email-verification-codes` 以 `REGISTER` 用途向邮箱发送验证码。
2. `POST /auth/register` 同时提交用户名、邮箱、密码与验证码，成功后返回令牌。
3. 忘记密码时，以 `PASSWORD_RESET` 用途调用同一发码接口。
4. `POST /auth/password-reset` 提交邮箱、验证码与新密码；成功后重新登录。
5. 已登录用户更换邮箱先调用 `POST /users/me/email-verification-code`，再在 `PATCH /users/me` 中提交新邮箱和验证码。

## 从画像到计划

推荐使用对话式画像流程：

1. `POST /profiles/me/interview-sessions` 创建或恢复当前访谈；传 `{"restart":true}` 可放弃旧草稿并重新开始。
2. `POST /profiles/me/interview-sessions/{id}/messages` 提交用户说明和当前会话 `version`。Java 鉴权/限流后调用 Python `/internal/v1/profile/interview-turns:stream`；浏览器流依次收到 `message.started`、零到多个 `message.delta`，最后收到带完整会话草稿的 `message.completed`。Python 只投影可见 `assistantMessage`，Java 对最终 `updates` 再校验和合并；失败时以 `message.replace` 切到规则引导。只有完整轮次成功后才原子保存草稿和消息。
3. `POST /profiles/me/interview-sessions/{id}/confirmation` 提交当前 `version`；信息完整时，在同一事务中写入画像、偏好、每周时段并固化画像版本。

高级手动编辑使用 `POST /profiles/me/manual-save`，一次提交访谈会话版本、正式画像、偏好和可用时间。后端在同一事务内完成全部校验与写入、生成 `MANUAL_SAVE` 画像版本，并把右侧访谈草稿同步为已保存画像；任一步失败时整体回滚。细粒度接口还支持每周时段、特殊日期例外和自评证据。`POST /profiles/me/generation-jobs` 立即返回 `QUEUED`，前端通过 `GET /profiles/me/generation-jobs/{jobId}` 轮询到 `SUCCEEDED/FAILED`，模型调用不占用请求事务。

后续目标与计划流程：

4. 可直接 `POST /goals` 创建自定义目标。目录课程提交 `directionId`，目录外课程提交 `customDirection`，两者必须且只能填写一个；`sourceType=CUSTOM` 不要求方向已经写入画像，因此新用户可以先建立 `DRAFT` 草稿。目标页加载时调用 `GET /goals/recommendations/latest` 读取最近一次已保存推荐，不调用模型；只有用户明确点击推荐按钮时才调用 `POST /goals/recommendations`。推荐输入只由当前 `profile_version.snapshot_json` 重建；目录方向与自定义方向都会作为合法上下文传给 Python，自定义方向不映射公共目录。可恢复 AI 故障或输出校验失败时返回明确标记的确定性规则候选，所有方向已有同阶段活动目标时可返回空候选与业务提示。
5. 用户选择候选并编辑确认后再 `POST /goals`，提交方向字段、`sourceType`、`profileVersionId` 和 `recommendationId`。Java 锁定当前画像并从当前用户的 `goal_recommendation_batch` 反查候选、批次来源和画像版本；客户端不能决定正式来源或原始推荐理由。用户可编辑目标业务字段，但必须重新通过 Java 日期、容量、方向、成功标准和项目结构校验。来源快照与正式目标一起保存；前端应把 `profileVersionId` 当作字符串原样回传。随后 `POST /goals/{id}/activation` 激活。
6. 画像、偏好或可用时间被修改后，画像状态回到 `DRAFT`，必须生成新画像版本才能再次请求推荐；既有目标继续绑定创建时的旧画像版本，不被新画像静默改写。
7. 目标页使用 `/projects`、`/projects/{id}/goal-links`、`/projects/{id}/milestones` 和 `/goals/{id}/progress` 完成项目创建、草稿编辑、关联权重、里程碑结构、生命周期和进度展示。项目字段与结构仅 `DRAFT` 可改；`PAUSED` 必须先恢复才可完成。里程碑完成请求携带版本、总结和逐项确认，服务端以数据库 `acceptance_json` 为权威并用行锁保证重复/并发请求最多产生一次掌握度证据和 Outbox。
8. `POST /goals/{id}/planning-jobs` 异步提交生成隔离提案：请求必须携带唯一 `Idempotency-Key`，校验通过后立即返回 `QUEUED` 状态的作业，模型生成在后台线程执行（读取上下文与调用模型不持事务，写入在单个事务内一次提交）。前端轮询 `GET /planning-jobs/{jobId}`：`SUCCEEDED` 后通过 `GET /plan-versions/{planVersionId}` 读取提案，`FAILED` 返回 `errorCode`/`errorMessage`；页面刷新后可调用 `GET /goals/{goalId}/planning-jobs` 恢复轮询，超过 30 分钟的遗留 `QUEUED/RUNNING` 作业视为中断自动标记 `FAILED`。可在 `knowledgeSpaceIds` 显式提交最多 20 个知识空间。Java 只接受本人私有空间或可访问的公共空间，并把其中已完成索引的活动文档版本固化为上下文快照；Python 检索后要求每个任务返回真实 `sourceChunkIds`。
9. `GET /plan-versions/{versionId}` 审阅阶段、变更与校验结果。
10. `POST /plan-versions/{versionId}/confirmation-requests` 获取短时确认令牌。
11. `POST /plan-versions/{versionId}/publication` 发布，必须同时提交确认令牌和新的 `Idempotency-Key`。

发布事务同时应用任务变更、更新正式版本、写发布记录、审计和 Outbox。失败时整体回滚。用户可以只勾选部分变更发布；Outbox 消费采用重试、退避和死信状态，任务完成与学习反馈会产生学习信号，并按冷却时间自动创建新的优化建议，仍需用户确认后才能发布。

再次进入计划页或切换目标时，前端调用 `GET /goals/{goalId}/plan` 读取该目标最近发布的当前版本；响应包含阶段、变更项和校验结果。没有发布计划时返回空数据，前端才显示新建规划界面。

计划发布后可调用 `POST /goals/{goalId}/optimization-requests`，请求同样必须携带唯一 `Idempotency-Key`，并同样为异步执行（提交即返回作业，轮询与刷新恢复机制与首次规划一致）。系统先持久化 `optimization_request`，再让 Python 生成新的任务内容候选，由 Java 重新排期并与现有未完成任务比较，生成 `ADD_TASK`、`RESCHEDULE_TASK` 或 `CANCEL_TASK` 变更。成功或失败状态都会保留；用户仍需审阅、确认并发布新版本，优化请求不会直接改动正式任务。

## 知识库与问答

1. `POST /knowledge-spaces` 创建私有空间；`PATCH /knowledge-spaces/{id}` 可用版本号重命名或调整方向；`DELETE /knowledge-spaces/{id}` 接受请求后先将空间标记为 `DELETING` 并返回 202，再异步清理文档、Chunk、对象文件和向量索引；成功转为 `DELETED`，失败转为 `DELETE_FAILED`，不会出现数据库已删但外部对象残留却无法追踪的状态。
2. `GET/POST/PATCH/DELETE /knowledge-spaces/{id}/categories` 管理空间内的层级资料分类；同级分类不能重名，有子分类或文档时不能删除。`PATCH /documents/{id}/category` 分配或清除文档分类。
3. `POST /knowledge-spaces/{id}/documents` 使用 multipart 上传文件，当前单文件上限 150 MB；Java 先验证扩展名、真实 MIME、PDF/DOCX 结构、压缩炸弹与可选 ClamAV 病毒扫描，再保存随机对象键和元数据并返回。后台完成 Tika/OCR 提取、切块和 Qdrant 索引，状态从 `UPLOADED/PROCESSING` 进入 `READY` 或 `FAILED`。
4. `POST /knowledge-searches` 先由 Java 解析并授权 `spaceIds`，Python 在 Qdrant 下推权限过滤和 Top-K，Java 再按返回的 `chunkId` 查询 MySQL 二次鉴权；每次检索的查询哈希、命中 Chunk、耗时或错误会记录到 `agent_tool_call`。
5. `POST /qa-sessions` 创建会话；`DELETE /qa-sessions/{id}` 删除当前用户的会话、消息、引用和反馈。以 `Accept: text/event-stream` 调用 `POST /qa-sessions/{id}/messages`，依次接收 `message.started`、`message.delta`、`citation.ready` 和 `message.completed`。引用只在 Java 二次鉴权后转发和落库。
6. 证据不足时返回拒答标识，不用模型常识补齐私有资料中不存在的结论。
7. 单文档永久删除先调用 `deletion-requests`，再把短时令牌提交到 `deletion`；知识空间和问答会话删除由前端确认框保护，后端仍校验资源归属。

## 执行与评估

- 任务启动、暂停、恢复、阻塞、完成和取消均通过动作型端点；开始或恢复任务时会检查 `task_dependency`，前置任务未完成则返回具体阻塞任务。
- `GET /tasks/graph` 从当前用户由已发布 AI 计划形成的正式任务、所属目标和 `task_dependency` 读取任务图谱。接口按用户时区标记 `TODAY / FUTURE / PAST / UNSCHEDULED`，只有 `TODAY` 节点返回 `availableToday=true`；图谱是正式计划数据的只读投影，不让模型另行生成展示关系。
- `POST /study-sessions` 或 `/tasks/{id}/start` 开始计时，停止后按用户时区切分跨日时长。
- `PUT /tasks/{id}/note` 使用版本号更新，内容以 Markdown 存储，前端渲染时经过 DOMPurify。
- `POST /tasks/{id}/chats` 基于当前任务标题、任务类型、数据库中的持久化会话和当前用户可访问知识空间生成辅导回答。默认模型窗口使用最近最多 400 条且累计不超过 600000 字符的消息；`GET /tasks/{id}/chats` 恢复完整持久化会话，`DELETE /tasks/{id}/chats` 关闭当前会话。Python 只返回建议和引用候选，不修改任务、笔记或计划。
- 评估答案通过 `PUT /attempts/{id}/answers/{sequence}` 实时保存；交卷端点要求 `Idempotency-Key` 并立即进入后台批改。客观题确定性评分，主观题模型失败时保留 `PARTIALLY_GRADED/GRADING_FAILED` 并可调用 `POST /attempts/{id}/grading-retry` 重试。每次评分写入 `grading_record`，掌握度重算后写入 `mastery_snapshot`。
- 学习者可对已完成批改的答案提交或撤回评分申诉；管理员在 `/api/v1/admin/assessment-appeals` 接受、拒绝或给出修正分。接受修正会追加评分版本、同步错题状态并重新计算掌握度，完整过程写审计。
- `GET /analytics/overview?start=YYYY-MM-DD&end=YYYY-MM-DD` 的 `metrics` 同时返回 `overallGoalTaskProgress` 和 `taskCompletionRate`。前者是当前用户 `ACTIVE/PAUSED` 目标下全部未取消任务的“已完成预计分钟 / 总预计分钟”，不受查询日期范围限制；后者是查询周期内“已完成任务数 / 计划任务数”，总览默认传最近 7 天。
- `/analytics/*` 的比率指标都返回 `value`、`numerator`、`denominator`、`periodStart`、`periodEnd`、`timezone`、`refreshedAt` 和 `metricVersion`。分母为零时 `value=null`；总览视觉组件将其作为 0% 空态展示。每日学习数据由定时汇总和查询时补算共同维护，定时任务使用 `scheduled_job_lock` 防止多实例重复执行。

## 管理端治理

所有管理接口位于 `/api/v1/admin`，先受 `ADMIN` 角色保护，再按权限点限制高影响操作。

- 用户：`GET /users` 支持关键字、状态和分页；`POST /users/{id}/status` 修改状态，`PUT /users/{id}/roles` 分配角色。写操作必须提交原因，自身禁用和移除自身最后一个管理员角色会被拒绝。
- 目录：`GET/POST /learning-directions`、`GET/POST /knowledge-points` 管理目录；`GET/POST/DELETE /knowledge-dependencies` 管理前置关系，后端校验同方向、自依赖、重复边和依赖环。
- 题库：`GET/POST /questions` 查询或创建公共题目，创建时绑定至少一个知识点。
- AI 治理：`GET/POST/PATCH /model-configs` 管理模型元数据与状态，密钥只保存引用；`GET/POST/PATCH /prompt-templates` 追加提示词版本并激活或归档。
- 监控：`GET /system-metrics` 返回业务指标，`GET /jobs` 返回规划、文档、模型调用三类运行记录，`GET /audit-logs` 支持操作、结果和关键字筛选。

公共学习方向仍通过 `/api/v1/learning-directions` 提供轻量查询，不执行管理端的知识点统计，避免公共目录读取与治理统计耦合。

完整接口与模型以运行时 OpenAPI `/v3/api-docs` 为准。
