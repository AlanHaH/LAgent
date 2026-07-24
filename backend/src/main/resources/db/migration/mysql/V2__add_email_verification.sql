ALTER TABLE sys_user
  ADD COLUMN email_verified_at TIMESTAMP(6) NULL AFTER email;

UPDATE sys_user
SET email_verified_at = COALESCE(created_at, CURRENT_TIMESTAMP(6))
WHERE email_verified_at IS NULL;

ALTER TABLE sys_user
  MODIFY COLUMN email_verified_at TIMESTAMP(6) NOT NULL;
