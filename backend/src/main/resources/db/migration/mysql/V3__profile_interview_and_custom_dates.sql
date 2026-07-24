ALTER TABLE user_profile
  ADD COLUMN plan_start_date DATE NULL AFTER week_start,
  ADD COLUMN plan_end_date DATE NULL AFTER plan_start_date;

UPDATE user_profile
SET plan_start_date = DATE(created_at),
    plan_end_date = DATE_ADD(DATE(created_at), INTERVAL (plan_period_days - 1) DAY)
WHERE plan_start_date IS NULL OR plan_end_date IS NULL;

ALTER TABLE user_profile
  MODIFY plan_start_date DATE NOT NULL,
  MODIFY plan_end_date DATE NOT NULL;

ALTER TABLE user_profile_direction
  DROP INDEX uk_profile_direction,
  ADD COLUMN active_direction_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN deleted_at IS NULL THEN direction_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_profile_active_direction (profile_id, active_direction_id);

CREATE TABLE profile_interview_session (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  draft_json JSON NOT NULL,
  missing_fields_json JSON NOT NULL,
  completeness_percent TINYINT NOT NULL DEFAULT 0,
  assistant_mode VARCHAR(24) NOT NULL DEFAULT 'GUIDED',
  confirmed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_user_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'ACTIVE' AND deleted_at IS NULL THEN user_id ELSE NULL END) STORED,
  UNIQUE KEY uk_profile_interview_public (public_id),
  UNIQUE KEY uk_profile_interview_active_user (active_user_id),
  KEY idx_profile_interview_user_status (user_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE profile_interview_message (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  role VARCHAR(24) NOT NULL,
  content VARCHAR(4000) NOT NULL,
  source VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_profile_interview_message_public (public_id),
  UNIQUE KEY uk_profile_interview_sequence (session_id, sequence_no),
  KEY idx_profile_interview_message_user (user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
