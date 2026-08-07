ALTER TABLE learning_goal
  MODIFY COLUMN direction_id BIGINT NULL COMMENT '管理员学习目录方向ID；用户自定义方向时为空',
  ADD COLUMN custom_direction VARCHAR(120) NULL COMMENT '用户自定义学习方向；目录方向时为空' AFTER direction_id;
