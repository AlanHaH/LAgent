CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(160) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
  login_failed_count INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMP(6) NULL,
  last_login_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_user_public_id (public_id),
  UNIQUE KEY uk_user_username (username),
  UNIQUE KEY uk_user_email (email),
  KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
  id BIGINT PRIMARY KEY,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_permission (
  id BIGINT PRIMARY KEY,
  code VARCHAR(100) NOT NULL,
  name VARCHAR(100) NOT NULL,
  resource_type VARCHAR(80) NULL,
  UNIQUE KEY uk_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_permission (
  role_id BIGINT NOT NULL,
  permission_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role(id),
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE refresh_token (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  device_id VARCHAR(120) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  revoked_at TIMESTAMP(6) NULL,
  rotated_to_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_refresh_hash (token_hash),
  KEY idx_refresh_user_expiry (user_id, expires_at),
  CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_direction (
  id BIGINT PRIMARY KEY,
  parent_id BIGINT NULL,
  code VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  sort_no INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_direction_code (code),
  KEY idx_direction_parent_status (parent_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_point (
  id BIGINT PRIMARY KEY,
  direction_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  replacement_knowledge_point_id BIGINT NULL,
  code VARCHAR(80) NOT NULL,
  name VARCHAR(160) NOT NULL,
  level INT NOT NULL DEFAULT 1,
  default_weight DECIMAL(6,4) NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_knowledge_direction_code (direction_id, code),
  KEY idx_knowledge_parent (parent_id),
  KEY idx_knowledge_direction_status (direction_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_dependency (
  predecessor_id BIGINT NOT NULL,
  successor_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL DEFAULT 'PREREQUISITE',
  PRIMARY KEY (predecessor_id, successor_id),
  CONSTRAINT chk_knowledge_not_self CHECK (predecessor_id <> successor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_profile (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai',
  week_start TINYINT NOT NULL DEFAULT 1,
  plan_period_days INT NOT NULL DEFAULT 30,
  background_text VARCHAR(2000) NULL,
  profile_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  current_version_no INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_profile_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_profile_direction (
  id BIGINT PRIMARY KEY,
  profile_id BIGINT NOT NULL,
  direction_id BIGINT NULL,
  custom_direction VARCHAR(120) NULL,
  source_type VARCHAR(24) NOT NULL DEFAULT 'CATALOG',
  current_stage VARCHAR(80) NOT NULL,
  is_primary TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  primary_flag TINYINT GENERATED ALWAYS AS (CASE WHEN is_primary = 1 AND deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_profile_direction (profile_id, direction_id),
  UNIQUE KEY uk_profile_primary (profile_id, primary_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE profile_version (
  id BIGINT PRIMARY KEY,
  profile_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  snapshot_json JSON NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  trigger_type VARCHAR(40) NOT NULL,
  trigger_event_id VARCHAR(100) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  UNIQUE KEY uk_profile_version (profile_id, version_no),
  UNIQUE KEY uk_profile_trigger (profile_id, trigger_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_preference (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  content_modes_json JSON NOT NULL,
  guidance_style VARCHAR(40) NOT NULL,
  task_granularity VARCHAR(40) NOT NULL,
  focus_minutes INT NOT NULL,
  capacity_ratio DECIMAL(4,3) NOT NULL DEFAULT 0.850,
  difficulty_min TINYINT NOT NULL DEFAULT 1,
  difficulty_max TINYINT NOT NULL DEFAULT 5,
  reminder_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_preference_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE availability_rule (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  weekday TINYINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  available_minutes INT NOT NULL,
  energy_level VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_availability_user_weekday (user_id, weekday),
  CONSTRAINT chk_availability_weekday CHECK (weekday BETWEEN 1 AND 7),
  CONSTRAINT chk_availability_minutes CHECK (available_minutes BETWEEN 0 AND 960)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE availability_exception (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  available_minutes INT NOT NULL,
  reason VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_availability_exception (user_id, local_date),
  CONSTRAINT chk_exception_minutes CHECK (available_minutes BETWEEN 0 AND 960)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE self_assessment (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  level TINYINT NOT NULL,
  assessed_at TIMESTAMP(6) NOT NULL,
  last_studied_at DATE NULL,
  note VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  KEY idx_self_assessment_user_kp (user_id, knowledge_point_id, assessed_at),
  CONSTRAINT chk_self_assessment_level CHECK (level BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE profile_generation_job (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  profile_version_id BIGINT NULL,
  error_code VARCHAR(80) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  finished_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_profile_job_public (public_id),
  KEY idx_profile_job_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_goal (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  direction_id BIGINT NOT NULL,
  source_goal_id BIGINT NULL,
  name VARCHAR(100) NOT NULL,
  type VARCHAR(40) NOT NULL,
  description VARCHAR(2000) NULL,
  priority VARCHAR(20) NOT NULL,
  start_date DATE NOT NULL,
  due_date DATE NOT NULL,
  weekly_budget_minutes INT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  success_criteria_json JSON NOT NULL,
  acceptance_snapshot_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status NOT IN ('COMPLETED','CANCELED') THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_goal_public (public_id),
  UNIQUE KEY uk_goal_active_name (user_id, name, active_flag),
  KEY idx_goal_user_status_due (user_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_project (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  primary_direction_id BIGINT NULL,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(2000) NULL,
  start_date DATE NOT NULL,
  due_date DATE NOT NULL,
  priority VARCHAR(20) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  deliverable_json JSON NOT NULL,
  repository_url VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL AND status NOT IN ('ARCHIVED','CANCELED') THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_project_public (public_id),
  UNIQUE KEY uk_project_active_name (user_id, name, active_flag),
  KEY idx_project_user_status_due (user_id, status, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE goal_project (
  goal_id BIGINT NOT NULL,
  project_id BIGINT NOT NULL,
  contribution_weight DECIMAL(6,4) NOT NULL,
  PRIMARY KEY (goal_id, project_id),
  CONSTRAINT chk_goal_project_weight CHECK (contribution_weight > 0 AND contribution_weight <= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE milestone (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  project_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  sequence_no INT NOT NULL,
  due_date DATE NOT NULL,
  weight DECIMAL(6,4) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
  acceptance_json JSON NOT NULL,
  completion_evidence_json JSON NULL,
  completed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_milestone_public (public_id),
  UNIQUE KEY uk_milestone_sequence (project_id, sequence_no),
  KEY idx_milestone_project_status (project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_plan (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  project_id BIGINT NULL,
  name VARCHAR(160) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_plan_public (public_id),
  KEY idx_plan_user_goal (user_id, goal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE planning_job (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  job_type VARCHAR(40) NOT NULL,
  status VARCHAR(24) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  plan_version_id BIGINT NULL,
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(500) NULL,
  started_at TIMESTAMP(6) NULL,
  finished_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_planning_job_public (public_id),
  UNIQUE KEY uk_planning_job_idempotency (user_id, idempotency_key),
  KEY idx_planning_job_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_version (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  plan_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  base_version_no INT NULL,
  status VARCHAR(32) NOT NULL,
  trigger_type VARCHAR(40) NOT NULL,
  trigger_event_id VARCHAR(120) NULL,
  context_snapshot_json JSON NOT NULL,
  proposal_hash CHAR(64) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  model_run_id BIGINT NULL,
  summary_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_plan_version_public (public_id),
  UNIQUE KEY uk_plan_version_no (plan_id, version_no),
  UNIQUE KEY uk_plan_trigger (plan_id, trigger_event_id, trigger_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_stage (
  id BIGINT PRIMARY KEY,
  plan_version_id BIGINT NOT NULL,
  client_ref VARCHAR(80) NOT NULL,
  name VARCHAR(120) NOT NULL,
  sequence_no INT NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  outcome VARCHAR(1000) NOT NULL,
  UNIQUE KEY uk_plan_stage_sequence (plan_version_id, sequence_no),
  UNIQUE KEY uk_plan_stage_ref (plan_version_id, client_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_change_item (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  plan_version_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  target_task_id BIGINT NULL,
  client_ref VARCHAR(80) NOT NULL,
  before_json JSON NULL,
  after_json JSON NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  risk_level VARCHAR(20) NOT NULL,
  confirm_required TINYINT(1) NOT NULL DEFAULT 0,
  item_status VARCHAR(24) NOT NULL DEFAULT 'PROPOSED',
  UNIQUE KEY uk_change_public (public_id),
  UNIQUE KEY uk_change_ref (plan_version_id, client_ref),
  KEY idx_change_target (target_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_validation_result (
  id BIGINT PRIMARY KEY,
  plan_version_id BIGINT NOT NULL,
  validator_code VARCHAR(80) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  field_path VARCHAR(200) NULL,
  message VARCHAR(1000) NOT NULL,
  details_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL,
  KEY idx_validation_version_severity (plan_version_id, severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_confirmation (
  id BIGINT PRIMARY KEY,
  plan_version_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  proposal_hash CHAR(64) NOT NULL,
  token_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  confirmed_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_confirmation_token (token_hash),
  KEY idx_confirmation_version_status (plan_version_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plan_publication (
  plan_id BIGINT PRIMARY KEY,
  plan_version_id BIGINT NOT NULL,
  published_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_publication_version (plan_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE learning_task (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  project_id BIGINT NULL,
  milestone_id BIGINT NULL,
  origin_plan_version_id BIGINT NULL,
  title VARCHAR(200) NOT NULL,
  description VARCHAR(2000) NULL,
  task_type VARCHAR(40) NOT NULL,
  priority VARCHAR(20) NOT NULL,
  estimated_minutes INT NOT NULL,
  scheduled_start TIMESTAMP(6) NULL,
  due_at TIMESTAMP(6) NULL,
  locked_schedule TINYINT(1) NOT NULL DEFAULT 0,
  lifecycle_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED',
  progress_percent DECIMAL(5,2) NOT NULL DEFAULT 0,
  completed_at TIMESTAMP(6) NULL,
  reschedule_count INT NOT NULL DEFAULT 0,
  acceptance_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_task_public (public_id),
  KEY idx_task_user_status_due (user_id, lifecycle_status, due_at),
  KEY idx_task_goal (goal_id),
  KEY idx_task_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_dependency (
  predecessor_task_id BIGINT NOT NULL,
  successor_task_id BIGINT NOT NULL,
  PRIMARY KEY (predecessor_task_id, successor_task_id),
  CONSTRAINT chk_task_not_self CHECK (predecessor_task_id <> successor_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_knowledge_point (
  task_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  weight DECIMAL(6,4) NOT NULL DEFAULT 1,
  PRIMARY KEY (task_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_status_history (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  from_status VARCHAR(24) NOT NULL,
  to_status VARCHAR(24) NOT NULL,
  reason VARCHAR(1000) NULL,
  event_at TIMESTAMP(6) NOT NULL,
  operator_type VARCHAR(24) NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  KEY idx_task_status_history (task_id, event_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_schedule_history (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  old_start TIMESTAMP(6) NULL,
  old_due TIMESTAMP(6) NULL,
  new_start TIMESTAMP(6) NULL,
  new_due TIMESTAMP(6) NULL,
  reason VARCHAR(1000) NOT NULL,
  source_plan_version_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  KEY idx_task_schedule_history (task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_session (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  session_group_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  source VARCHAR(24) NOT NULL,
  started_at TIMESTAMP(6) NOT NULL,
  ended_at TIMESTAMP(6) NULL,
  pause_seconds BIGINT NOT NULL DEFAULT 0,
  effective_seconds BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(24) NOT NULL,
  manual_reason VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_session_public (public_id),
  KEY idx_session_user_start (user_id, started_at),
  KEY idx_session_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_session_pause (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  paused_at TIMESTAMP(6) NOT NULL,
  resumed_at TIMESTAMP(6) NULL,
  seconds BIGINT NOT NULL DEFAULT 0,
  KEY idx_session_pause (session_id, paused_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_note (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  current_version_no INT NOT NULL DEFAULT 0,
  title VARCHAR(200) NOT NULL,
  sync_document_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_note_public (public_id),
  UNIQUE KEY uk_note_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_note_version (
  id BIGINT PRIMARY KEY,
  note_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  content_markdown MEDIUMTEXT NOT NULL,
  content_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  UNIQUE KEY uk_note_version (note_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE task_completion_summary (
  id BIGINT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  learned_text VARCHAR(3000) NULL,
  difficulty_text VARCHAR(3000) NULL,
  quality_level TINYINT NULL,
  confidence_level TINYINT NULL,
  remaining_questions VARCHAR(3000) NULL,
  revision_no INT NOT NULL DEFAULT 1,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_completion_summary_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tutoring_session (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  task_id BIGINT NOT NULL,
  mode VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  knowledge_scope_json JSON NOT NULL,
  started_at TIMESTAMP(6) NOT NULL,
  ended_at TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_tutoring_session_public (public_id),
  KEY idx_tutoring_user_task (user_id, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tutoring_message (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  guidance_level INT NOT NULL,
  model_run_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_tutoring_message_public (public_id),
  KEY idx_tutoring_message_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_space (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NULL,
  name VARCHAR(120) NOT NULL,
  visibility VARCHAR(24) NOT NULL DEFAULT 'PRIVATE',
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  direction_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_space_public (public_id),
  UNIQUE KEY uk_private_space_name (user_id, name, active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE resource_category (
  id BIGINT PRIMARY KEY,
  space_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  name VARCHAR(120) NOT NULL,
  path VARCHAR(1000) NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_category_name (space_id, parent_id, name, active_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE stored_object (
  id BIGINT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  original_file_name VARCHAR(255) NOT NULL,
  mime_type VARCHAR(120) NOT NULL,
  file_size BIGINT NOT NULL,
  file_hash CHAR(64) NOT NULL,
  scan_status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_object_key (object_key),
  KEY idx_object_owner_hash (owner_user_id, file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_document (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  space_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  category_id BIGINT NULL,
  display_name VARCHAR(255) NOT NULL,
  status VARCHAR(32) NOT NULL,
  active_version_no INT NOT NULL DEFAULT 1,
  visibility VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  active_flag TINYINT GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN 1 ELSE NULL END) STORED,
  UNIQUE KEY uk_document_public (public_id),
  UNIQUE KEY uk_document_space_name (space_id, display_name, active_flag),
  KEY idx_document_owner_status (owner_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_version (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  stored_object_id BIGINT NOT NULL,
  parser_version VARCHAR(50) NOT NULL,
  chunk_config_json JSON NOT NULL,
  embedding_model VARCHAR(120) NULL,
  embedding_dimension INT NULL,
  status VARCHAR(32) NOT NULL,
  text_hash CHAR(64) NULL,
  file_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_document_version (document_id, version_no),
  KEY idx_document_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_chunk (
  id BIGINT PRIMARY KEY,
  document_version_id BIGINT NOT NULL,
  chunk_no INT NOT NULL,
  text MEDIUMTEXT NOT NULL,
  text_hash CHAR(64) NOT NULL,
  token_count INT NOT NULL,
  title_path_json JSON NULL,
  page_from INT NULL,
  page_to INT NULL,
  paragraph_from INT NULL,
  paragraph_to INT NULL,
  vector_json JSON NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_chunk_no (document_version_id, chunk_no),
  KEY idx_chunk_hash (document_version_id, text_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_job (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  document_version_id BIGINT NOT NULL,
  job_type VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  idempotency_key VARCHAR(160) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(6) NULL,
  error_code VARCHAR(80) NULL,
  error_message VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_document_job_public (public_id),
  UNIQUE KEY uk_document_job_idempotency (idempotency_key),
  KEY idx_document_job_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE document_deletion_token (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  token_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_document_delete_token (token_hash),
  KEY idx_document_delete_status (document_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE qa_session (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  title VARCHAR(200) NOT NULL,
  selected_space_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
  context_summary VARCHAR(4000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_qa_session_public (public_id),
  KEY idx_qa_session_user_update (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE qa_message (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  answer_mode VARCHAR(32) NULL,
  evidence_level VARCHAR(24) NULL,
  model_run_id BIGINT NULL,
  latency_ms BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_qa_message_public (public_id),
  KEY idx_qa_message_session (session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE qa_citation (
  id BIGINT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  citation_code VARCHAR(20) NOT NULL,
  chunk_id BIGINT NOT NULL,
  document_version_id BIGINT NOT NULL,
  quote_preview VARCHAR(500) NOT NULL,
  rank_no INT NOT NULL,
  score_snapshot DECIMAL(8,6) NOT NULL,
  access_status VARCHAR(24) NOT NULL,
  UNIQUE KEY uk_qa_citation_code (message_id, citation_code),
  KEY idx_qa_citation_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE qa_feedback (
  id BIGINT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  rating TINYINT NOT NULL,
  reason_code VARCHAR(40) NULL,
  comment VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_qa_feedback (message_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  owner_user_id BIGINT NULL,
  visibility VARCHAR(24) NOT NULL,
  current_version_no INT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  source_type VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_question_public (public_id),
  KEY idx_question_status_visibility (status, visibility)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_version (
  id BIGINT PRIMARY KEY,
  question_id BIGINT NOT NULL,
  version_no INT NOT NULL,
  type VARCHAR(24) NOT NULL,
  stem VARCHAR(4000) NOT NULL,
  options_json JSON NULL,
  answer_json JSON NOT NULL,
  rubric_json JSON NULL,
  analysis VARCHAR(4000) NULL,
  difficulty TINYINT NOT NULL,
  source_document_version_id BIGINT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_question_version (question_id, version_no),
  CONSTRAINT chk_question_difficulty CHECK (difficulty BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_knowledge_point (
  question_version_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  allocation DECIMAL(6,4) NOT NULL,
  PRIMARY KEY (question_version_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assessment (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  owner_user_id BIGINT NULL,
  type VARCHAR(24) NOT NULL,
  title VARCHAR(200) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  duration_minutes INT NOT NULL,
  max_attempts INT NOT NULL,
  total_score DECIMAL(7,2) NOT NULL,
  pass_score DECIMAL(7,2) NOT NULL,
  scope_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_assessment_public (public_id),
  KEY idx_assessment_owner_status (owner_user_id, status, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assessment_question (
  assessment_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  question_version_id BIGINT NOT NULL,
  score DECIMAL(7,2) NOT NULL,
  snapshot_json JSON NOT NULL,
  PRIMARY KEY (assessment_id, sequence_no),
  UNIQUE KEY uk_assessment_question (assessment_id, question_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assessment_attempt (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  assessment_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at TIMESTAMP(6) NOT NULL,
  submitted_at TIMESTAMP(6) NULL,
  total_score DECIMAL(7,2) NULL,
  grading_version INT NOT NULL DEFAULT 0,
  invalid_reason VARCHAR(1000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_attempt_public (public_id),
  UNIQUE KEY uk_attempt_no (assessment_id, user_id, attempt_no),
  KEY idx_attempt_user_submitted (user_id, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE attempt_answer (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  attempt_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  answer_json JSON NOT NULL,
  saved_at TIMESTAMP(6) NOT NULL,
  score DECIMAL(7,2) NULL,
  grading_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  grader_type VARCHAR(24) NULL,
  grader_confidence DECIMAL(5,4) NULL,
  feedback VARCHAR(4000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_answer_public (public_id),
  UNIQUE KEY uk_attempt_answer_sequence (attempt_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE grading_record (
  id BIGINT PRIMARY KEY,
  answer_id BIGINT NOT NULL,
  grading_version INT NOT NULL,
  rubric_snapshot_json JSON NULL,
  score DECIMAL(7,2) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  grader_type VARCHAR(24) NOT NULL,
  model_run_id BIGINT NULL,
  reason VARCHAR(2000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_grading_version (answer_id, grading_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wrong_question (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  question_version_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  first_wrong_at TIMESTAMP(6) NOT NULL,
  last_wrong_at TIMESTAMP(6) NOT NULL,
  wrong_count INT NOT NULL DEFAULT 1,
  ai_reason_code VARCHAR(40) NULL,
  confirmed_reason_code VARCHAR(40) NULL,
  UNIQUE KEY uk_wrong_question (user_id, question_version_id, knowledge_point_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assessment_appeal (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  answer_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reason VARCHAR(2000) NOT NULL,
  evidence_json JSON NULL,
  status VARCHAR(24) NOT NULL,
  resolution VARCHAR(2000) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  resolved_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_assessment_appeal_public (public_id),
  KEY idx_assessment_appeal_answer (answer_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mastery_evidence (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  source_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  weight DECIMAL(8,6) NOT NULL,
  occurred_at TIMESTAMP(6) NOT NULL,
  valid_flag TINYINT(1) NOT NULL DEFAULT 1,
  calc_version VARCHAR(30) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_mastery_evidence_source (evidence_type, source_id, knowledge_point_id),
  KEY idx_mastery_evidence_user_kp (user_id, knowledge_point_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_mastery (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_point_id BIGINT NOT NULL,
  score DECIMAL(5,2) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  level VARCHAR(32) NOT NULL,
  evidence_count INT NOT NULL,
  calculated_at TIMESTAMP(6) NOT NULL,
  calc_version VARCHAR(30) NOT NULL,
  UNIQUE KEY uk_knowledge_mastery (user_id, knowledge_point_id),
  KEY idx_knowledge_mastery_level (user_id, level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mastery_snapshot (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  scope_type VARCHAR(40) NOT NULL,
  scope_id BIGINT NULL,
  snapshot_at TIMESTAMP(6) NOT NULL,
  data_json JSON NOT NULL,
  calc_version VARCHAR(30) NOT NULL,
  KEY idx_mastery_snapshot_user (user_id, snapshot_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE daily_study_stat (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  local_date DATE NOT NULL,
  timezone VARCHAR(80) NOT NULL,
  auto_seconds BIGINT NOT NULL DEFAULT 0,
  manual_seconds BIGINT NOT NULL DEFAULT 0,
  planned_tasks INT NOT NULL DEFAULT 0,
  completed_tasks INT NOT NULL DEFAULT 0,
  overdue_tasks INT NOT NULL DEFAULT 0,
  metric_version VARCHAR(30) NOT NULL,
  UNIQUE KEY uk_daily_stat (user_id, local_date, metric_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_report (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  type VARCHAR(24) NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  timezone VARCHAR(80) NOT NULL,
  revision_no INT NOT NULL,
  metric_snapshot_json JSON NOT NULL,
  narrative MEDIUMTEXT NOT NULL,
  model_run_id BIGINT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_report_public (public_id),
  UNIQUE KEY uk_report_revision (user_id, type, period_start, period_end, revision_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE optimization_request (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  trigger_event_id VARCHAR(120) NOT NULL,
  type VARCHAR(40) NOT NULL,
  evidence_json JSON NOT NULL,
  status VARCHAR(24) NOT NULL,
  plan_version_id BIGINT NULL,
  cooldown_until TIMESTAMP(6) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_optimization_public (public_id),
  UNIQUE KEY uk_optimization_trigger (user_id, trigger_event_id, type),
  KEY idx_optimization_goal_status (goal_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_record (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  key_hash CHAR(64) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  response_ref VARCHAR(200) NULL,
  status VARCHAR(24) NOT NULL,
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_idempotency (user_id, key_hash),
  KEY idx_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_provider_config (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  provider VARCHAR(60) NOT NULL,
  name VARCHAR(120) NOT NULL,
  encrypted_secret_ref VARCHAR(500) NULL,
  base_url VARCHAR(500) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_model_provider_public (public_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_config (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  provider_id BIGINT NOT NULL,
  purpose VARCHAR(60) NOT NULL,
  model_name VARCHAR(120) NOT NULL,
  parameters_json JSON NOT NULL,
  timeout_seconds INT NOT NULL,
  daily_limit BIGINT NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_model_config_public (public_id),
  UNIQUE KEY uk_model_purpose_name (purpose, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE prompt_template (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  code VARCHAR(100) NOT NULL,
  version_no INT NOT NULL,
  content MEDIUMTEXT NOT NULL,
  schema_json JSON NULL,
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  UNIQUE KEY uk_prompt_public (public_id),
  UNIQUE KEY uk_prompt_version (code, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE model_run (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NULL,
  purpose VARCHAR(60) NOT NULL,
  model_config_id BIGINT NULL,
  prompt_version VARCHAR(60) NULL,
  status VARCHAR(24) NOT NULL,
  input_ref_json JSON NULL,
  output_hash CHAR(64) NULL,
  token_in INT NULL,
  token_out INT NULL,
  latency_ms BIGINT NULL,
  error_code VARCHAR(80) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_model_run_public (public_id),
  KEY idx_model_run_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_tool_call (
  id BIGINT PRIMARY KEY,
  model_run_id BIGINT NOT NULL,
  tool_call_id VARCHAR(100) NOT NULL,
  tool_name VARCHAR(100) NOT NULL,
  args_hash CHAR(64) NOT NULL,
  args_summary_json JSON NULL,
  result_status VARCHAR(24) NOT NULL,
  duration_ms BIGINT NOT NULL,
  error_code VARCHAR(80) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_agent_tool_call (tool_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification (
  id BIGINT PRIMARY KEY,
  public_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  type VARCHAR(40) NOT NULL,
  channel VARCHAR(20) NOT NULL,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(4000) NOT NULL,
  biz_type VARCHAR(60) NULL,
  biz_id VARCHAR(80) NULL,
  status VARCHAR(24) NOT NULL,
  scheduled_at TIMESTAMP(6) NOT NULL,
  sent_at TIMESTAMP(6) NULL,
  read_at TIMESTAMP(6) NULL,
  dedupe_key VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_notification_public (public_id),
  UNIQUE KEY uk_notification_dedupe (user_id, channel, dedupe_key),
  KEY idx_notification_status_schedule (status, scheduled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE notification_preference (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  preference_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  created_by BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  updated_by BIGINT NOT NULL,
  version INT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMP(6) NULL,
  UNIQUE KEY uk_notification_preference_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_log (
  id BIGINT PRIMARY KEY,
  request_id VARCHAR(100) NOT NULL,
  operator_id BIGINT NOT NULL,
  operator_type VARCHAR(24) NOT NULL,
  action VARCHAR(80) NOT NULL,
  resource_type VARCHAR(80) NOT NULL,
  resource_id VARCHAR(100) NULL,
  before_summary VARCHAR(2000) NULL,
  after_summary VARCHAR(2000) NULL,
  result VARCHAR(24) NOT NULL,
  ip VARCHAR(64) NULL,
  created_at TIMESTAMP(6) NOT NULL,
  KEY idx_audit_operator_time (operator_id, created_at),
  KEY idx_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
  id BIGINT PRIMARY KEY,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id VARCHAR(100) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload_json JSON NOT NULL,
  correlation_id VARCHAR(100) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uk_outbox_event (event_type, aggregate_id, correlation_id),
  KEY idx_outbox_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE scheduled_job_lock (
  job_name VARCHAR(100) NOT NULL,
  shard_key VARCHAR(100) NOT NULL,
  locked_until TIMESTAMP(6) NOT NULL,
  owner VARCHAR(120) NOT NULL,
  PRIMARY KEY (job_name, shard_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_role(id, code, name, status) VALUES
  (1, 'STUDENT', '学生用户', 'ACTIVE'),
  (2, 'ADMIN', '管理员', 'ACTIVE');

INSERT INTO sys_permission(id, code, name, resource_type) VALUES
  (1, 'user:read', '用户查询', 'USER'),
  (2, 'user:status:write', '用户状态管理', 'USER'),
  (3, 'direction:write', '学习方向管理', 'DIRECTION'),
  (4, 'knowledge-point:write', '知识点管理', 'KNOWLEDGE_POINT'),
  (5, 'question:review', '题库审核', 'QUESTION'),
  (6, 'public-resource:write', '公共资源管理', 'DOCUMENT'),
  (7, 'model:config:write', '模型配置', 'MODEL_CONFIG'),
  (8, 'prompt:write', '提示词管理', 'PROMPT_TEMPLATE'),
  (9, 'audit:read', '审计日志查询', 'AUDIT_LOG'),
  (10, 'monitor:read', '运行监控', 'METRIC');

INSERT INTO sys_role_permission(role_id, permission_id)
SELECT 2, id FROM sys_permission;

INSERT INTO learning_direction(id, parent_id, code, name, status, sort_no,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (100, NULL, 'COMPUTER_SCIENCE', '计算机科学与技术', 'ACTIVE', 10, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (101, 100, 'JAVA_BACKEND', 'Java 后端开发', 'ACTIVE', 20, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (102, 100, 'FRONTEND', '前端开发', 'ACTIVE', 30, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (103, 100, 'AI_AGENT', 'AI Agent 与智能应用', 'ACTIVE', 40, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT INTO knowledge_point(id, direction_id, parent_id, code, name, level, default_weight, status,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (1000, 101, NULL, 'JAVA_FOUNDATION', 'Java 基础', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1001, 101, 1000, 'JAVA_OOP', '面向对象编程', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1002, 101, 1000, 'SPRING_BOOT', 'Spring Boot', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1003, 101, 1002, 'REST_API', 'REST API 设计', 3, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1010, 102, NULL, 'VUE3', 'Vue 3', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1020, 103, NULL, 'LLM_FOUNDATION', '大模型基础', 1, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1021, 103, 1020, 'RAG', '检索增强生成', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (1022, 103, 1020, 'AGENT_TOOL_USE', 'Agent 工具调用', 2, 1.0000, 'ACTIVE', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT INTO knowledge_dependency(predecessor_id, successor_id, type) VALUES
  (1000, 1002, 'PREREQUISITE'),
  (1002, 1003, 'PREREQUISITE'),
  (1020, 1021, 'PREREQUISITE'),
  (1020, 1022, 'PREREQUISITE');

INSERT INTO question(id, public_id, owner_user_id, visibility, current_version_no, status, source_type,
  created_at, created_by, updated_at, updated_by, version)
VALUES
  (2000, 'seed-java-oop-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (2001, 'seed-spring-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (2002, 'seed-rest-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (2003, 'seed-vue-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (2004, 'seed-rag-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0),
  (2005, 'seed-agent-1', NULL, 'PUBLIC', 1, 'PUBLISHED', 'CURATED', UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), 0, 0);

INSERT INTO question_version(id, question_id, version_no, type, stem, options_json, answer_json, rubric_json, analysis, difficulty, created_at)
VALUES
  (2100,2000,1,'SINGLE_CHOICE','面向对象封装的主要目的是什么？','["隐藏内部实现并通过稳定接口访问","让所有字段公开","避免创建对象","取消类型检查"]','"A"',NULL,'封装通过访问边界保护对象内部状态。',2,UTC_TIMESTAMP(6)),
  (2101,2001,1,'TRUE_FALSE','Spring Boot 可以通过自动配置减少常见基础配置。','["正确","错误"]','true',NULL,'自动配置是 Spring Boot 的核心能力之一。',2,UTC_TIMESTAMP(6)),
  (2102,2002,1,'SINGLE_CHOICE','创建资源成功的 REST 接口通常返回哪个状态码？','["201","200","404","500"]','"A"',NULL,'创建资源通常返回 201 Created。',2,UTC_TIMESTAMP(6)),
  (2103,2003,1,'TRUE_FALSE','Vue 3 的 computed 适合表达由响应式状态派生的数据。','["正确","错误"]','true',NULL,'computed 会缓存并追踪响应式依赖。',2,UTC_TIMESTAMP(6)),
  (2104,2004,1,'SINGLE_CHOICE','RAG 中引用校验的核心作用是什么？','["确保引用能映射到实际检索证据","增加回答长度","隐藏资料来源","绕过权限过滤"]','"A"',NULL,'引用必须能回到当次检索证据。',3,UTC_TIMESTAMP(6)),
  (2105,2005,1,'SINGLE_CHOICE','Agent 对高影响计划变更应如何处理？','["生成提案并请求用户确认","直接写数据库","静默取消任务","让模型自行授权"]','"A"',NULL,'高影响操作必须经过后端校验和用户确认。',3,UTC_TIMESTAMP(6));

INSERT INTO question_knowledge_point(question_version_id, knowledge_point_id, allocation) VALUES
 (2100,1001,1),(2101,1002,1),(2102,1003,1),(2103,1010,1),(2104,1021,1),(2105,1022,1);
