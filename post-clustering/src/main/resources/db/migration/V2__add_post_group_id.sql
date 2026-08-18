-- Existing rows intentionally remain NULL: their logical post grouping is not known.
-- New application-created runs always receive a non-null post_group_id.
ALTER TABLE AGENT_pipeline_runs
    ADD COLUMN post_group_id VARCHAR(255) NULL AFTER id;
