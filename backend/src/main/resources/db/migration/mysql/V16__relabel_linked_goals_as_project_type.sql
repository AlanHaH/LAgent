-- 把已关联实践项目(goal_project)的目标归入「项目」分类。
-- 背景：重构后页面按 技能/项目/考试 三种目标类型分 tab，项目型目标内部携带
--   learning_project + milestone（创建目标时同事务自动创建）。旧数据里项目是独立
--   实体挂在目标下，需要把这类目标回填标记为 PROJECT，才能出现在「项目」tab。
-- 设计：
--  1) 仅更新 type<>'PROJECT' 的行，重复执行天然幂等；
--  2) 用 DISTINCT 聚合 goal_id，一个目标挂多个项目也只更新一次；
--  3) 只回填关联了「未软删除」项目的目标，项目已软删除的目标不强行改类；
--  4) 只改 learning_goal.type，不改动 goal_project / learning_project 任何数据。
UPDATE learning_goal g
JOIN (
  SELECT DISTINCT gp.goal_id
  FROM goal_project gp
  JOIN learning_project p ON p.id = gp.project_id
  WHERE p.deleted_at IS NULL
) linked ON linked.goal_id = g.id
SET g.type = 'PROJECT'
WHERE g.type <> 'PROJECT';
