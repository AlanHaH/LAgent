# 部署说明

## 配置

从 `.env.example` 创建 `.env`。必须修改 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`、`AI_INTERNAL_TOKEN` 和 `QDRANT_API_KEY`；各类令牌应分别生成且不要复用，`JWT_SECRET` 至少 32 个随机字符。只有显式设置非空 `APP_ADMIN_PASSWORD` 时才初始化管理员。

如需启用 AI 画像访谈、目标推荐、计划/任务候选、任务辅导与知识库问答，还要设置 `MODEL_BASE_URL`、`MODEL_API_KEY`、`MODEL_NAME`、`AI_SERVICE_ENABLED=true` 和不少于 32 字符的独立 `AI_INTERNAL_TOKEN`。DeepSeek V4 Flash 使用 `https://api.deepseek.com` 与 `deepseek-v4-flash`。模型密钥只注入 Python AI 容器；内部令牌只用于 Java→Python 服务认证，不能复用 JWT 密钥。

支持 1M 上下文的模型建议保留默认 `TASK_CHAT_HISTORY_MAX_MESSAGES=400`、`TASK_CHAT_HISTORY_MAX_CHARACTERS=600000`。这两个值限制的是单次任务辅导调用，不删除数据库历史；不要直接设为 100 万字符，因为系统提示、当前问题、RAG 证据、JSON 封装和模型输出仍需要窗口余量。

注册、找回密码和更换邮箱需要 SMTP 邮件服务。至少设置 `MAIL_HOST`、`MAIL_PORT`、`MAIL_USERNAME`、`MAIL_PASSWORD`（多数邮箱要求填写 SMTP 授权码而不是登录密码）和 `MAIL_FROM`。端口 587 通常使用 `MAIL_STARTTLS_ENABLED=true`、`MAIL_SSL_ENABLED=false`；端口 465 通常使用 `MAIL_STARTTLS_ENABLED=false`、`MAIL_SSL_ENABLED=true`。建议把 `VERIFICATION_CODE_PEPPER` 设置成独立的长随机值。验证码摘要只保存在 Redis，Redis 不可用时发码与验码会安全失败，不会降级成固定验证码或明文存储。

```bash
docker compose config
docker compose up --build -d
docker compose ps
```

Web 默认监听 `8088`。Nginx 只暴露前端和 `/api/`；Python AI 与 Qdrant 不映射宿主端口，只允许 Compose 内网中的 Java/Python访问。MySQL、Redis、对象文件、Qdrant 索引和 Embedding 模型缓存分别使用命名卷。

Qdrant 服务端和 Python 客户端使用同一个 `QDRANT_API_KEY`，即使位于 Compose 内网也不允许匿名访问。文件上传至少执行格式、MIME 和压缩结构检查；生产环境建议部署 ClamAV，给后端设置 `CLAMAV_HOST`、`CLAMAV_PORT` 并启用 `ANTIVIRUS_REQUIRED=true`，使扫描服务不可用时安全失败。

本地无 Docker 开发先创建 `ai-service/.venv` 并安装 `.[dev,embeddings]`，再依次运行 `scripts/start-ai-local.ps1`、`scripts/start-backend-local.ps1` 和前端 `npm run dev`。本地默认 `AI_QDRANT_MODE=local`，索引位于 `ai-service/data/qdrant`；不要同时用两个 Python 进程打开同一 Local 路径。

## 数据与时间

- MySQL 使用 `utf8mb4`，会话默认时区为 UTC，Flyway 启动时自动迁移。
- 用户 IANA 时区保存在账户与画像中，仅在展示、归属日和统计区间换算时使用。
- 文档对象保存在后端不可执行目录，数据库只记录随机对象键和校验信息。

## 备份与升级

升级前分别备份 MySQL 与 `object-data` 卷；Qdrant 索引虽然可由 MySQL Chunk 重建，生产环境仍建议备份 `qdrant-data` 以缩短恢复时间。Embedding 模型缓存可重新下载，不作为权威数据。迁移脚本只追加，不修改已发布的 Flyway 文件。先在备份副本验证新镜像，确认 Java/Python/Qdrant 健康检查、登录、任务、文档读取与流式问答后再切流。

## 生产建议

- 在外部反向代理终止 TLS，只允许 HTTPS，并把 `CORS_ALLOWED_ORIGINS` 设置为实际域名。
- 使用密钥管理服务注入 JWT 和模型供应商密钥，不把密钥写进数据库明文字段或镜像。
- 只允许 Java 网络身份访问 Python 的 8090，只允许 Python 访问 Qdrant 6333；生产建议关闭 FastAPI OpenAPI 页面并将 `AI_ALLOW_HASH_FALLBACK=false`。
- 登录与模型调用已有 Redis 限流和单机降级；生产环境仍应在网关层对登录、上传、问答和规划接口设置第二层限流，并采集 Actuator/Prometheus 指标与失败作业告警。
- 定期清理过期刷新令牌和幂等记录；应用内定时任务已提供默认清理。
- 对 Outbox 死信、`DELETE_FAILED` 文档/空间、`GRADING_FAILED` 评估和长期停留在 `QUEUED/RUNNING` 的作业设置告警与人工重试流程。
