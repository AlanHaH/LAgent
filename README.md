# 知序：基于 AI Agent 的自适应个人学习管理系统

一个可直接运行的前后端毕业设计项目。系统把学习画像、目标/项目、受约束计划提案、个人知识库、执行计时、诊断评估和数据优化组织为完整闭环。

Agent 的边界是系统设计的一部分：它可以生成提案、解释与引用，但不能绕过用户确认直接修改正式计划；容量、状态机、权限、版本与发布事务都由确定性后端规则控制。

## 技术栈

- 后端：Java 17、Spring Boot 3.3、Spring Security、MyBatis-Plus、Flyway、MySQL 8、Redis、JWT、OpenAPI
- 前端：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus、ECharts、Markdown-it、DOMPurify
- 部署：Docker Compose、Nginx、多阶段镜像、健康检查、持久化卷

## 目录

- `backend/`：按 `api / application / domain / infrastructure` 分层的模块化单体
- `frontend/`：学习者端与基于角色显示的管理员端
- `docs/`：架构、接口工作流、测试、部署与需求追踪
- `docker-compose.yml`：MySQL、Redis、后端和前端完整环境

## 快速启动

1. 复制 `.env.example` 为 `.env`，至少修改三个密码与 `JWT_SECRET`。生产环境不要使用示例值。
2. 启动：

   ```bash
   docker compose up --build -d
   ```

3. 打开 `http://localhost:8088`。后端 OpenAPI 在开发直连模式下位于 `http://localhost:8080/swagger-ui.html`。

只有设置了 `APP_ADMIN_PASSWORD` 才会初始化管理员。普通用户可从登录页注册。

## 本地开发

```bash
cd backend
mvn test
mvn spring-boot:run
```

```bash
cd frontend
npm install
npm run dev
```

本地后端需要 MySQL 8 和 Redis。数据库结构由 Flyway 自动创建，时间字段统一以 UTC 保存。前端开发服务器会把 `/api` 代理到 `localhost:8080`。
后端构建要求 Maven 3.6.3 或更高版本；Docker 构建固定使用 Maven 3.9.9。

## 已实现的业务闭环

1. 注册登录、JWT 刷新轮换、失败锁定、RBAC、资源归属与审计。
2. 学习画像、偏好、跨午夜可用时间、例外日、自评与画像版本。
3. 目标/项目/里程碑生命周期、目标项目关联、进度和依赖 DAG 校验。
4. 85% 容量约束的 Agent 计划提案、不可变版本、校验、差异、二次确认、幂等发布、Outbox 和重排提案。
5. 私有知识空间、文件安全校验、解析、切块、混合检索、带引用问答、证据不足拒答和确认删除。
6. 任务生命周期与排期状态分离、跨日计时分摊、笔记版本、学习总结和辅导会话。
7. 诊断/评估、答案暂存、幂等交卷、客观评分、错题本、掌握度与置信度、统一口径统计和修订版报告。
8. 通知去重与偏好、目录/题库/模型/提示词/用户状态管理、作业监控和系统指标。

详细运行说明见 [部署文档](docs/deployment.md)，接口主流程见 [接口工作流](docs/api-workflows.md)，验证结果见 [测试说明](docs/testing.md)。
