# MySQL 数据库

MySQL 是最流行的开源关系型数据库。存储引擎默认 InnoDB：支持事务（ACID）、行级锁、外键、崩溃恢复（redo log）。

## 1. SQL 基础

- **DDL**：CREATE/ALTER/DROP TABLE；字段类型：INT、BIGINT、VARCHAR、TEXT、DATETIME/TIMESTAMP、DECIMAL。
- **DML**：SELECT / INSERT / UPDATE / DELETE。
- **查询**：WHERE 过滤、JOIN 连接（INNER/LEFT/RIGHT）、GROUP BY 分组 + HAVING 过滤、ORDER BY 排序、LIMIT 分页。
- **索引**：CREATE INDEX；主键、唯一键、普通索引、联合索引（最左前缀原则）。

```sql
SELECT t.title, SUM(s.effective_seconds) AS total
FROM learning_task t
LEFT JOIN study_session s ON s.task_id = t.id
WHERE t.user_id = ? AND s.status = 'COMPLETED'
GROUP BY t.id
ORDER BY total DESC
LIMIT 10;
```

## 2. 索引与查询优化

- **为什么快**：B+ 树索引把全表扫描 O(n) 降到 O(log n)，回表次数决定性能。
- **最左前缀**：联合索引 (a,b,c) 生效于 a、(a,b)、(a,b,c)，跳过 a 直接查 b 不走索引。
- **覆盖索引**：查询列都在索引中，无需回表。
- **回表**：二级索引查到主键再查主键索引取整行；避免 SELECT * 可减少回表。
- **EXPLAIN**：看 type（const > ref > range > index > ALL）、key、rows、Extra（Using filesort/Using temporary 要警惕）。
- **索引失效**：对列做函数/运算、隐式类型转换、前导模糊 %xx、OR 条件含非索引列。

## 3. 事务与隔离级别

- **ACID**：原子性（要么全成要么全败）、一致性、隔离性、持久性。
- **隔离级别**（默认 REPEATABLE READ）：读未提交（脏读）、读已提交（不可重复读）、可重复读（幻读）、串行化。
- **MVCC**：多版本并发控制，快照读不加锁，读写互不阻塞。
- **锁**：行锁（排他 X / 共享 S）、间隙锁（防幻读）、表锁、死锁检测。
- **大事务问题**：长事务持有锁、undo 膨胀、主从延迟，业务上尽量短事务。

## 4. 设计规范

- 表结构：每表都要主键（雪花 ID 或自增）、created_at/updated_at、version 乐观锁、deleted_at 逻辑删除。
- 字段：避免用 TEXT 存大字段且频繁查询；金额用 DECIMAL 不用浮点；时间统一 UTC 存储、展示时按用户时区转换。
- 三范式与冗余的平衡：适度冗余避免过度 JOIN。
- 状态机字段：用字符串状态 + 明确的流转规则，比散乱 boolean 可维护。

## 5. 性能与容量

- 连接池（HikariCP）：最大连接数 = 核心数 ×2 + 磁盘数，超买会拖垮数据库。
- 慢查询日志：long_query_time 阈值，定期分析。
- 分区/分库分表：数据量大再考虑，先保证索引与缓存到位。
- 缓存：热点数据用 Redis，先更缓存还是先更库？用 Cache Aside：先删缓存再写库，或双删。
- 备份：binlog 实时 + 定期全量，恢复演练要真正跑过。

## 6. 常见坑

- 隐式类型转换导致索引失效（字符串列查数字）。
- 深分页 LIMIT 100000,20 慢：改用游标/上一页 ID 定位。
- COUNT(*) 大表慢：近似统计用 information_schema。
- 时间比较用索引列不包函数：`created_at > ?` 而不是 `DATE(created_at) > ?`。
- 唯一约束防并发重复插入，而不是先查后插（有竞态）。
