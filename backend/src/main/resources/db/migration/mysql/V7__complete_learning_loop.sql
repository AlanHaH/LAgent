ALTER TABLE tutoring_message
  ADD COLUMN metadata_json JSON NULL AFTER model_run_id;

CREATE INDEX idx_task_dependency_successor
  ON task_dependency(successor_task_id, predecessor_task_id);

ALTER TABLE resource_category
  ADD COLUMN public_id VARCHAR(64) NULL AFTER id,
  ADD UNIQUE KEY uk_resource_category_public(public_id);

UPDATE resource_category SET public_id=UUID() WHERE public_id IS NULL;

ALTER TABLE resource_category
  MODIFY COLUMN public_id VARCHAR(64) NOT NULL;
