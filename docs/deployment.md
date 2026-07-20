# 部署说明

## 配置

从 `.env.example` 创建 `.env`。必须修改 `MYSQL_PASSWORD`、`MYSQL_ROOT_PASSWORD`、`JWT_SECRET`；`JWT_SECRET` 至少 32 个随机字符。只有显式设置非空 `APP_ADMIN_PASSWORD` 时才初始化管理员。

```bash
docker compose config
docker compose up --build -d
docker compose ps
```

Web 默认监听 `8088`。Nginx 只暴露前端和 `/api/`，不对外代理 Actuator。数据库、Redis 和对象文件分别保存在命名卷中。

## 数据与时间

- MySQL 使用 `utf8mb4`，会话默认时区为 UTC，Flyway 启动时自动迁移。
- 用户 IANA 时区保存在账户与画像中，仅在展示、归属日和统计区间换算时使用。
- 文档对象保存在后端不可执行目录，数据库只记录随机对象键和校验信息。

## 备份与升级

升级前分别备份 MySQL 与 `object-data` 卷。迁移脚本只追加，不修改已发布的 Flyway 文件。先在备份副本验证新镜像，确认健康检查、登录、任务与文档读取后再切流。

## 生产建议

- 在外部反向代理终止 TLS，只允许 HTTPS，并把 `CORS_ALLOWED_ORIGINS` 设置为实际域名。
- 使用密钥管理服务注入 JWT 和模型供应商密钥，不把密钥写进数据库明文字段或镜像。
- 对登录、上传、问答和规划接口增加网关限流；采集 Actuator/Prometheus 指标并设置失败作业告警。
- 定期清理过期刷新令牌和幂等记录；应用内定时任务已提供默认清理。
