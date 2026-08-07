# Spring Boot 实战

Spring Boot 是 Java 生态最主流的应用框架，基于 Spring 框架自动配置，目标是"开箱即用"：约定优于配置，内置 Tomcat，一键启动。

## 1. 核心概念

- **自动配置**：根据 classpath 依赖自动装配 Bean，如引入 spring-boot-starter-web 自动配置 Web 容器。
- **Starter**：场景化依赖，如 spring-boot-starter-data-jpa、mybatis-plus-boot-starter。
- **application.yml**：集中配置，支持 `${ENV:默认值}` 占位符与环境隔离（dev/prod profile）。
- **Spring IoC 容器**：Bean 由容器创建和管理，依赖注入（DI）实现解耦。
- **AOP**：面向切面，用于日志、事务、权限横切逻辑。

## 2. 分层架构（本项目的模块分层）

- **api**（Controller）：接收请求、参数校验（Bean Validation）、返回统一响应体，不写业务逻辑。
- **application**（Service）：业务规则、事务边界（@Transactional）、调用领域服务。
- **domain**：实体（Entity）+ 无状态策略类（Policy），领域规则放在策略类中。
- **infrastructure**：MyBatis-Plus 配置、分页、雪花 ID、乐观锁、逻辑删除等基础能力。

## 3. Web 开发要点

- **RESTful 设计**：资源名词复数 + HTTP 方法（GET 查 / POST 建 / PUT 改 / PATCH 局部改 / DELETE 删）。
- **统一响应**：`{success, data, requestId}` 包裹；异常统一由 @RestControllerAdvice 处理，返回稳定错误码。
- **参数校验**：@Valid + @NotBlank/@Size/@Email，错误信息国际化或统一文案。
- **JWT 鉴权**：无状态令牌，Header 携带 Bearer token；拦截器校验，角色权限用注解或路由守卫。
- **幂等**：写接口要求客户端带 Idempotency-Key 头，服务端去重，防止重复提交。
- **SSE 流式**：模型生成内容用 SseEmitter 逐段推送，实现打字机效果。

## 4. 数据访问（MyBatis-Plus）

```java
// 继承 BaseMapper 即获得 CRUD
public interface TaskMapper extends BaseMapper<LearningTaskEntity> {}
// 条件构造器
taskMapper.selectOne(new LambdaQueryWrapper<LearningTaskEntity>()
    .eq(LearningTaskEntity::getUserId, uid)
    .eq(LearningTaskEntity::getLifecycleStatus, "IN_PROGRESS"));
```

- **乐观锁**：version 字段 + @Version，更新时比对，冲突重试，防并发覆盖。
- **逻辑删除**：deleted_at 标记而非物理删除，查询自动过滤。
- **雪花 ID**：分布式唯一 ID，避免自增主键暴露数据量。
- **事务**：@Transactional 默认只回滚 RuntimeException；写库与发消息必须同事务（Outbox 模式）。

## 5. 常见实践

- **配置外部化**：密码、密钥放环境变量，绝不提交到仓库（.env + 启动脚本注入）。
- **单元测试**：Service 用 Mockito 隔离依赖；Controller 用 MockMvc + H2 集成测。
- **Flyway 迁移**：数据库结构变更用版本化脚本管理，环境间一致。
- **异步任务**：@Async + 线程池配置，长任务（如 AI 生成）后台执行、前端轮询状态。
- **健康检查**：/health/ready 探活，判断依赖（DB、Redis、模型服务）是否就绪。

## 6. 调优与排错

- 启动慢：检查自动配置日志、数据库连接、依赖扫描范围。
- 慢 SQL：开启 MyBatis 日志，用 EXPLAIN 看索引。
- 内存溢出：-Xmx 调优 + 堆转储分析（Eclipse MAT）。
- 接口超时：RestClient 设置连接/读取超时，外部服务不可用时降级或快速失败。
