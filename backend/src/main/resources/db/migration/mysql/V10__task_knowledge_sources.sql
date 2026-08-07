CREATE TABLE task_knowledge_source (
  task_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (task_id, chunk_id),
  KEY idx_task_knowledge_source_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='任务知识来源表，固化AI计划中每个正式任务引用的知识库片段';
