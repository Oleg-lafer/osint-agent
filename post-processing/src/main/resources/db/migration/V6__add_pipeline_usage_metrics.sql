ALTER TABLE AGENT_pipeline_runs
    ADD COLUMN duration_ms BIGINT NULL,
    ADD COLUMN input_tokens BIGINT NULL,
    ADD COLUMN output_tokens BIGINT NULL,
    ADD COLUMN total_tokens BIGINT NULL,
    ADD COLUMN estimated_cost_usd DECIMAL(12,6) NULL,
    ADD COLUMN usage_details JSON NULL;
