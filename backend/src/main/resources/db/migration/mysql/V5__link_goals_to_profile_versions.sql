ALTER TABLE learning_goal
  ADD COLUMN source_type VARCHAR(24) NOT NULL DEFAULT 'CUSTOM' COMMENT '目标来源：自定义、AI推荐或规则推荐' AFTER source_goal_id,
  ADD COLUMN profile_version_id BIGINT NULL COMMENT '推荐目标所依据的画像版本ID' AFTER source_type,
  ADD COLUMN recommendation_snapshot_json JSON NULL COMMENT '用户确认推荐目标时的推荐依据快照' AFTER profile_version_id,
  ADD KEY idx_goal_profile_version (profile_version_id);
