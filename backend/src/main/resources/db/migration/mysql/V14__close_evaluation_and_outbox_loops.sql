ALTER TABLE wrong_question
  ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'OPEN' AFTER confirmed_reason_code,
  ADD COLUMN corrected_at TIMESTAMP(6) NULL AFTER status,
  ADD KEY idx_wrong_question_user_status (user_id, status, last_wrong_at);

ALTER TABLE outbox_event
  ADD COLUMN processed_at TIMESTAMP(6) NULL AFTER next_retry_at,
  ADD COLUMN last_error VARCHAR(1000) NULL AFTER processed_at;
