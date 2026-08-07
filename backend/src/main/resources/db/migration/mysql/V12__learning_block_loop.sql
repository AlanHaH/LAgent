CREATE TABLE knowledge_point_reference (
  id BIGINT PRIMARY KEY,
  knowledge_point_id BIGINT NOT NULL,
  title VARCHAR(255) NOT NULL,
  url VARCHAR(1000) NOT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'OFFICIAL_WEB',
  summary VARCHAR(1000) NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_kp_reference_url (knowledge_point_id, url(300)),
  KEY idx_kp_reference_status (knowledge_point_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='知识点可信资料索引，仅保存经过管理员维护的来源元数据';

CREATE TABLE learning_block (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  origin_plan_version_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  objective VARCHAR(1000) NOT NULL,
  direction_name VARCHAR(120) NOT NULL,
  exploration_required TINYINT(1) NOT NULL DEFAULT 0,
  source_status VARCHAR(32) NOT NULL,
  source_manifest_json JSON NOT NULL,
  source_queries_json JSON NOT NULL,
  generation_status VARCHAR(24) NOT NULL DEFAULT 'OUTLINE',
  material_markdown MEDIUMTEXT NULL,
  exercises_json JSON NULL,
  test_json JSON NULL,
  pass_score DECIMAL(5,2) NOT NULL DEFAULT 70.00,
  latest_score DECIMAL(5,2) NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'READY',
  completed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_learning_block_public (public_id),
  UNIQUE KEY uk_learning_block_task (task_id),
  UNIQUE KEY uk_learning_block_plan_sequence (origin_plan_version_id, sequence_no),
  KEY idx_learning_block_user_status (user_id, status),
  KEY idx_learning_block_goal_sequence (goal_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Agent按块生成的学习单元，独立保存资料、练习、来源和块测状态';

CREATE TABLE learning_block_attempt (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  block_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  answers_json JSON NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  passed TINYINT(1) NOT NULL,
  feedback_json JSON NOT NULL,
  submitted_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_learning_block_attempt_public (public_id),
  KEY idx_learning_block_attempt (block_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='知识块测试尝试记录，保留每次作答、得分和反馈';

ALTER TABLE learning_task
  ADD COLUMN learning_block_id BIGINT NULL AFTER origin_plan_version_id,
  ADD KEY idx_task_learning_block (learning_block_id);
