# Docker 与部署运维

Docker 把应用和依赖打包成镜像，在任何装有 Docker 的机器上一致运行，解决"在我电脑上是好的"问题。本项目的 MySQL / Redis / Qdrant / AI 服务 / 后端 / 前端均以容器编排运行。

## 1. 核心概念

- **镜像（Image）**：只读模板，分层存储（基础层 + 变更层），Dockerfile 描述构建步骤。
- **容器（Container）**：镜像的运行实例，进程级隔离（namespace + cgroup）。
- **仓库（Registry）**：镜像分发，Docker Hub / 阿里云镜像仓库。
- **数据卷（Volume）**：容器销毁后数据仍保留，挂载宿主机目录或卷。
- **网络**：bridge（默认，容器互访）、host、自定义网络（按服务名解析）。

## 2. 常用命令

```bash
docker build -t myapp:1.0 .        # 构建镜像
docker run -d -p 8080:8080 -v data:/app/data --name app myapp:1.0
docker ps / docker logs -f app     # 查看进程与日志
docker exec -it app bash           # 进入容器
docker compose up --build -d       # 按编排文件启动全部服务
docker compose down                # 停止并清理
```

## 3. Dockerfile 要点

- 多阶段构建：构建阶段（装依赖、编译）+ 运行阶段（只拷贝产物），镜像更小。
- Java：`FROM eclipse-temurin:17-jre` + `COPY *.jar app.jar` + `ENTRYPOINT ["java","-jar","app.jar"]`。
- Python：`FROM python:3.11-slim` + `pip install -e .`；前端：nginx 托管 dist。
- 缓存：先拷贝依赖清单再装依赖（利用层缓存），改代码不重装依赖。
- 健康检查：HEALTHCHECK 命令，编排依赖就绪再启动。

## 4. Docker Compose

- docker-compose.yml 定义服务：镜像/构建、端口映射、环境变量、卷、依赖顺序（depends_on）。
- 环境变量注入：`environment:` 或 `env_file: .env`，密钥不进镜像、不进仓库。
- 网络：同一 compose 网络内服务名互访（如 backend 连 mysql:3306）。

## 5. 部署流程（本项目）

1. 本地验证：三个服务启动 → 全栈测试通过。
2. 构建产物：前端 npm run build → dist；后端 mvn package → jar；AI 服务 pip 打包。
3. 镜像构建：前端 nginx（反向代理 /api → 后端容器）；后端 + AI 服务各一镜像。
4. 编排启动：docker compose up -d，MySQL/Redis/Qdrant 数据卷持久化。
5. 验证：健康检查接口全绿；域名 + HTTPS（证书自动续期）；备份数据库。

## 6. 运维要点

- **日志**：stdout 统一收集（docker logs / ELK），不写本地文件。
- **监控**：CPU/内存/磁盘告警；模型服务失败率、延迟跟踪。
- **备份**：数据库每日全量 + binlog；备份要演练恢复。
- **回滚**：镜像打版本 tag，保留上一版本，发布失败一键回退。
- **安全**：镜像不用 root 用户运行；暴露端口最小化；密钥走环境变量/密管服务；依赖定期漏洞扫描。
- **性能**：容器资源限制（--memory）、副本横向扩容、前面加负载均衡。

## 7. 常见排错

- 端口冲突：`docker ps` 看占用；容器起不来：`docker logs <name>` 看启动日志。
- 容器间不通：检查是否同网络、服务名是否正确。
- 数据丢失：确认卷挂载；改表结构先备份。
- 时区问题：容器默认 UTC，应用统一 UTC 存储、展示层转换。
