# 接口工作流

所有业务接口位于 `/api/v1`，成功响应统一为 `{ success, data, requestId }`；失败响应包含稳定错误码与 `requestId`。除注册、登录和刷新外，接口使用 `Authorization: Bearer <accessToken>`。

## 从画像到计划

1. `PUT /profiles/me` 保存基础画像与方向。
2. `PUT /profiles/me/preferences` 保存专注时长、难度和容量比例。
3. `PUT /profiles/me/availability` 保存每周时段。
4. `POST /profiles/me/generation-jobs` 固化画像版本。
5. `POST /goals` 创建目标，随后 `POST /goals/{id}/activation` 激活。
6. `POST /goals/{id}/planning-jobs` 生成隔离提案，请求必须携带唯一 `Idempotency-Key`。
7. `GET /plan-versions/{versionId}` 审阅阶段、变更与校验结果。
8. `POST /plan-versions/{versionId}/confirmation-requests` 获取短时确认令牌。
9. `POST /plan-versions/{versionId}/publication` 发布，必须同时提交确认令牌和新的 `Idempotency-Key`。

发布事务同时应用任务变更、更新正式版本、写发布记录、审计和 Outbox。失败时整体回滚。

## 知识库与问答

1. `POST /knowledge-spaces` 创建私有空间。
2. `POST /knowledge-spaces/{id}/documents` 使用 multipart 上传文件。
3. `POST /knowledge-searches` 在明确的 `spaceIds` 范围内检索。
4. `POST /qa-sessions` 创建会话，`POST /qa-sessions/{id}/messages` 返回带 citation 的回答。
5. 证据不足时返回拒答标识，不用模型常识补齐私有资料中不存在的结论。
6. 删除先调用 `deletion-requests`，再把短时令牌提交到 `deletion`。

## 执行与评估

- 任务启动、暂停、恢复、阻塞、完成和取消均通过动作型端点；非法状态跳转返回业务错误。
- `POST /study-sessions` 或 `/tasks/{id}/start` 开始计时，停止后按用户时区切分跨日时长。
- `PUT /tasks/{id}/note` 使用版本号更新，内容以 Markdown 存储，前端渲染时经过 DOMPurify。
- 评估答案通过 `PUT /attempts/{id}/answers/{sequence}` 实时保存；交卷端点要求 `Idempotency-Key`。
- `/analytics/*` 的比率指标都返回 value、numerator、denominator、timezone、refreshedAt 和 metricVersion。分母为零时 value 为 null。

完整接口与模型以运行时 OpenAPI `/v3/api-docs` 为准。
