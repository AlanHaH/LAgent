CREATE TABLE goal_recommendation_batch (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  profile_version_id BIGINT NOT NULL,
  profile_version_no INT NOT NULL,
  source VARCHAR(24) NOT NULL,
  response_json JSON NOT NULL,
  generated_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_goal_recommendation_batch_public (public_id),
  KEY idx_goal_recommendation_batch_user_generated (user_id, generated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='目标推荐批次表，保存用户最近及历史AI目标候选';
