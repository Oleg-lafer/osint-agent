-- Accepted posts that actually entered embedding and clustering for this run.
-- Existing rows remain NULL because their accepted input count was not captured at completion.
ALTER TABLE AGENT_pipeline_runs
    ADD COLUMN processed_post_count INT NULL;
