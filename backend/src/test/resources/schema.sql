CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY, public_id VARCHAR(64) NOT NULL UNIQUE, username VARCHAR(50) NOT NULL UNIQUE,
 email VARCHAR(160) NOT NULL UNIQUE, email_verified_at TIMESTAMP NOT NULL, password_hash VARCHAR(100) NOT NULL, status VARCHAR(24) NOT NULL,
 timezone VARCHAR(80) NOT NULL, login_failed_count INT NOT NULL DEFAULT 0, locked_until TIMESTAMP,
 last_login_at TIMESTAMP, created_at TIMESTAMP NOT NULL, created_by BIGINT NOT NULL,
 updated_at TIMESTAMP NOT NULL, updated_by BIGINT NOT NULL, version INT NOT NULL DEFAULT 0, deleted_at TIMESTAMP
);
CREATE TABLE sys_role (id BIGINT PRIMARY KEY,code VARCHAR(50) UNIQUE,name VARCHAR(100),status VARCHAR(24));
CREATE TABLE sys_permission (id BIGINT PRIMARY KEY,code VARCHAR(100) UNIQUE,name VARCHAR(100),resource_type VARCHAR(80));
CREATE TABLE sys_user_role (user_id BIGINT NOT NULL,role_id BIGINT NOT NULL,PRIMARY KEY(user_id,role_id));
CREATE TABLE sys_role_permission (role_id BIGINT NOT NULL,permission_id BIGINT NOT NULL,PRIMARY KEY(role_id,permission_id));
CREATE TABLE refresh_token (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,token_hash VARCHAR(64) NOT NULL UNIQUE,device_id VARCHAR(120) NOT NULL,
 expires_at TIMESTAMP NOT NULL,revoked_at TIMESTAMP,rotated_to_id BIGINT,created_at TIMESTAMP NOT NULL,
 created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE audit_log (id BIGINT PRIMARY KEY,request_id VARCHAR(100),operator_id BIGINT,operator_type VARCHAR(24),action VARCHAR(80),resource_type VARCHAR(80),resource_id VARCHAR(100),before_summary VARCHAR(2000),after_summary VARCHAR(2000),result VARCHAR(24),ip VARCHAR(64),created_at TIMESTAMP);
CREATE TABLE learning_direction (
 id BIGINT PRIMARY KEY,parent_id BIGINT,code VARCHAR(80) NOT NULL UNIQUE,name VARCHAR(120) NOT NULL,
 status VARCHAR(24) NOT NULL,sort_no INT NOT NULL,created_at TIMESTAMP,created_by BIGINT,
 updated_at TIMESTAMP,updated_by BIGINT,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE knowledge_point (
 id BIGINT PRIMARY KEY,direction_id BIGINT NOT NULL,parent_id BIGINT,code VARCHAR(80) NOT NULL,
 name VARCHAR(120) NOT NULL,level INT NOT NULL,default_weight DECIMAL(8,4) NOT NULL,status VARCHAR(24) NOT NULL,
 created_at TIMESTAMP,created_by BIGINT,updated_at TIMESTAMP,updated_by BIGINT,
 version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP,UNIQUE(direction_id,code)
);
CREATE TABLE knowledge_dependency (
 predecessor_id BIGINT NOT NULL,successor_id BIGINT NOT NULL,type VARCHAR(24) NOT NULL,
 PRIMARY KEY(predecessor_id,successor_id)
);
CREATE TABLE user_profile (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL UNIQUE,timezone VARCHAR(80) NOT NULL,week_start INT NOT NULL,
 plan_start_date DATE NOT NULL,plan_end_date DATE NOT NULL,plan_period_days INT NOT NULL,background_text VARCHAR(2000),
 profile_status VARCHAR(24) NOT NULL,current_version_no INT NOT NULL,created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,
 updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE user_profile_direction (
 id BIGINT PRIMARY KEY,profile_id BIGINT NOT NULL,direction_id BIGINT,custom_direction VARCHAR(120),source_type VARCHAR(24) NOT NULL,
 current_stage VARCHAR(80) NOT NULL,is_primary BOOLEAN NOT NULL,status VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,
 created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE learning_preference (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL UNIQUE,content_modes_json JSON NOT NULL,guidance_style VARCHAR(40) NOT NULL,
 task_granularity VARCHAR(40) NOT NULL,focus_minutes INT NOT NULL,capacity_ratio DECIMAL(4,3) NOT NULL,difficulty_min INT NOT NULL,
 difficulty_max INT NOT NULL,reminder_json JSON NOT NULL,created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,
 updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE availability_rule (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,weekday INT NOT NULL,start_time TIME NOT NULL,end_time TIME NOT NULL,
 available_minutes INT NOT NULL,energy_level VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,
 updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE availability_exception (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,local_date DATE NOT NULL,available_minutes INT NOT NULL,reason VARCHAR(500),
 created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,
 version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP,UNIQUE(user_id,local_date)
);
CREATE TABLE profile_interview_session (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,
 draft_json JSON NOT NULL,missing_fields_json JSON NOT NULL,completeness_percent INT NOT NULL,assistant_mode VARCHAR(24) NOT NULL,
 confirmed_at TIMESTAMP,created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,
 updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE profile_interview_message (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,session_id BIGINT NOT NULL,user_id BIGINT NOT NULL,
 sequence_no INT NOT NULL,role VARCHAR(24) NOT NULL,content VARCHAR(4000) NOT NULL,source VARCHAR(24) NOT NULL,
 created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,
 version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP,UNIQUE(session_id,sequence_no)
);
CREATE TABLE self_assessment (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,knowledge_point_id BIGINT NOT NULL,level INT NOT NULL,assessed_at TIMESTAMP NOT NULL,
 last_studied_at DATE,note VARCHAR(1000),created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,
 updated_by BIGINT NOT NULL,version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE profile_generation_job (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,status VARCHAR(24) NOT NULL,
 profile_version_id BIGINT,error_code VARCHAR(80),created_at TIMESTAMP NOT NULL,finished_at TIMESTAMP
);
CREATE TABLE profile_version (
 id BIGINT PRIMARY KEY,profile_id BIGINT NOT NULL,version_no INT NOT NULL,snapshot_json JSON NOT NULL,
 confidence DECIMAL(5,4) NOT NULL,trigger_type VARCHAR(40) NOT NULL,trigger_event_id VARCHAR(100),
 created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,UNIQUE(profile_id,version_no)
);
CREATE TABLE learning_project (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,primary_direction_id BIGINT,
 name VARCHAR(120) NOT NULL,description VARCHAR(2000),start_date DATE NOT NULL,due_date DATE NOT NULL,
 priority VARCHAR(20) NOT NULL,status VARCHAR(24) NOT NULL,deliverable_json VARCHAR(4000) NOT NULL,repository_url VARCHAR(500),
 created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,
 version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE milestone (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,project_id BIGINT NOT NULL,name VARCHAR(120) NOT NULL,
 sequence_no INT NOT NULL,due_date DATE NOT NULL,weight DECIMAL(6,4) NOT NULL,status VARCHAR(24) NOT NULL,
 acceptance_json VARCHAR(4000) NOT NULL,completion_evidence_json VARCHAR(8000),completed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,updated_at TIMESTAMP NOT NULL,updated_by BIGINT NOT NULL,
 version INT NOT NULL DEFAULT 0,deleted_at TIMESTAMP
);
CREATE TABLE outbox_event (
 id BIGINT PRIMARY KEY,aggregate_type VARCHAR(80) NOT NULL,aggregate_id VARCHAR(100) NOT NULL,
 event_type VARCHAR(100) NOT NULL,payload_json VARCHAR(8000) NOT NULL,correlation_id VARCHAR(100) NOT NULL,
 status VARCHAR(24) NOT NULL,attempts INT NOT NULL,next_retry_at TIMESTAMP NOT NULL,created_at TIMESTAMP NOT NULL
);
CREATE TABLE model_run (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,purpose VARCHAR(40) NOT NULL,
 model_config_id BIGINT,prompt_version VARCHAR(40) NOT NULL,status VARCHAR(24) NOT NULL,input_ref_json JSON NOT NULL,
 output_hash VARCHAR(64),token_in INT,token_out INT,latency_ms BIGINT,error_code VARCHAR(80),created_at TIMESTAMP NOT NULL
);
CREATE TABLE prompt_template (
 id BIGINT PRIMARY KEY,public_id VARCHAR(64) NOT NULL UNIQUE,code VARCHAR(100) NOT NULL,
 version_no INT NOT NULL,content VARCHAR(16000) NOT NULL,schema_json JSON,
 status VARCHAR(24) NOT NULL,created_at TIMESTAMP NOT NULL,created_by BIGINT NOT NULL,
 UNIQUE(public_id),UNIQUE(code,version_no)
);
INSERT INTO sys_role(id,code,name,status) VALUES(1,'STUDENT','学生','ACTIVE'),(2,'ADMIN','管理员','ACTIVE');
INSERT INTO learning_direction(id,code,name,status,sort_no,version) VALUES
(10,'COMPUTER_SCIENCE','计算机科学','ACTIVE',10,0),
(200,'ECONOMICS','经济学','ACTIVE',100,0),
(201,'MICROECONOMICS','微观经济学','ACTIVE',110,0),
(202,'MACROECONOMICS','宏观经济学','ACTIVE',120,0),
(203,'ECONOMIC_THINKING','经济学思维与应用','ACTIVE',130,0);
