CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY, public_id VARCHAR(64) NOT NULL UNIQUE, username VARCHAR(50) NOT NULL UNIQUE,
 email VARCHAR(160) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, status VARCHAR(24) NOT NULL,
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
CREATE TABLE learning_direction (id BIGINT PRIMARY KEY,parent_id BIGINT,code VARCHAR(80),name VARCHAR(120),status VARCHAR(24),sort_no INT,version INT DEFAULT 0,deleted_at TIMESTAMP);
INSERT INTO sys_role(id,code,name,status) VALUES(1,'STUDENT','学生','ACTIVE'),(2,'ADMIN','管理员','ACTIVE');
INSERT INTO learning_direction(id,code,name,status,sort_no,version) VALUES(10,'COMPUTER_SCIENCE','计算机科学','ACTIVE',10,0);
