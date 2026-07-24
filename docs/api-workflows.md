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

高级手动编辑仍兼容原接口：`PUT /profiles/me`（可提交 `planStartDate`、`planEndDate` 和一致的 `planPeriodDays`）、`PUT /profiles/me/preferences`、`PUT /profiles/me/availability`，最后调用 `POST /profiles/me/generation-jobs`。

后续目标与计划流程：

4. 可直接 `POST /goals` 创建自定义目标；自定义目标只要求 `directionId` 是有效学习目录，不要求该方向已经写入画像，因此新用户可以先建立 `DRAFT` 草稿。也可先调用 `POST /goals/recommendations`；推荐链路才要求当前已固化画像版本。
5. 用户选择候选并编辑确认后再 `POST /goals`，提交 `sourceType`、`profileVersionId`、`recommendationId` 和推荐理由。Java 校验画像版本归属和画像方向，将来源快照与正式目标一起保存；自定义目标使用 `sourceType=CUSTOM`，只记录目录方向。随后 `POST /goals/{id}/activation` 激活。
6. 画像、偏好或可用时间被修改后，画像状态回到 `DRAFT`，必须生成新画像版本才能再次请求推荐；既有目标继续绑定创建时的旧画像版本，不被新画像静默改写。
7. `POST /goals/{id}/planning-jobs` 生成隔离提案，请求必须携带唯一 `Idempotency-Key`。
8. `GET /plan-versions/{versionId}` 审阅阶段、变更与校验结果。
9. `POST /plan-versions/{versionId}/confirmation-requests` 获取短时确认令牌。
10. `POST /plan-versions/{versionId}/publication` 发布，必须同时提交确认令牌和新的 `Idempotency-Key`。

发布事务同时应用任务变更、更新正式版本、写发布记录、审计和 Outbox。失败时整体回滚。

## 知识库与问答

1. `POST /knowledge-spaces` 创建私有空间。
2. `POST /knowledge-spaces/{id}/documents` 使用 multipart 上传文件；Java 安全检查、Tika 提取、切块并写 MySQL 后，把 Chunk 批量发给 Python生成 Embedding 和 Qdrant 索引。
3. `POST /knowledge-searches` 先由 Java 解析并授权 `spaceIds`，Python 在 Qdrant 下推权限过滤和 Top-K，Java 再按返回的 `chunkId` 查询 MySQL 二次鉴权。
4. `POST /qa-sessions` 创建会话；以 `Accept: text/event-stream` 调用 `POST /qa-sessions/{id}/messages`，依次接收 `message.started`、`message.delta`、`citation.ready` 和 `message.completed`。引用只在 Java 二次鉴权后转发和落库。
5. 证据不足时返回拒答标识，不用模型常识补齐私有资料中不存在的结论。
6. 删除先调用 `deletion-requests`，再把短时令牌提交到 `deletion`。

## 执行与评估

- 任务启动、暂停、恢复、阻塞、完成和取消均通过动作型端点；非法状态跳转返回业务错误。
- `POST /study-sessions` 或 `/tasks/{id}/start` 开始计时，停止后按用户时区切分跨日时长。
- `PUT /tasks/{id}/note` 使用版本号更新，内容以 Markdown 存储，前端渲染时经过 DOMPurify。
- 评估答案通过 `PUT /attempts/{id}/answers/{sequence}` 实时保存；交卷端点要求 `Idempotency-Key`。
- `/analytics/*` 的比率指标都返回 value、numerator、denominator、timezone、refreshedAt 和 metricVersion。分母为零时 value 为 null。

完整接口与模型以运行时 OpenAPI `/v3/api-docs` 为准。
