INSERT IGNORE INTO task_dependency(predecessor_task_id, successor_task_id)
SELECT ranked.predecessor_task_id, ranked.id
FROM (
  SELECT id,
         LAG(id) OVER (
           PARTITION BY goal_id
           ORDER BY scheduled_start, due_at, id
         ) AS predecessor_task_id
  FROM learning_task
  WHERE lifecycle_status <> 'CANCELED'
    AND deleted_at IS NULL
) ranked
WHERE ranked.predecessor_task_id IS NOT NULL;
