ALTER TABLE AGENT_chat_sessions
    ADD COLUMN pipeline_run_id BIGINT UNSIGNED NULL AFTER user_id;

UPDATE AGENT_chat_sessions sessions
SET pipeline_run_id = COALESCE(
    (SELECT MIN(messages.pipeline_run_id)
     FROM AGENT_chat_messages messages
     WHERE messages.session_id = sessions.id
       AND messages.pipeline_run_id IS NOT NULL),
    (SELECT runs.id
     FROM AGENT_pipeline_runs runs
     WHERE runs.status = 'COMPLETED'
       AND runs.dataset_overview IS NOT NULL
     ORDER BY runs.completed_at DESC, runs.id DESC
     LIMIT 1)
);

ALTER TABLE AGENT_chat_sessions
    MODIFY pipeline_run_id BIGINT UNSIGNED NOT NULL,
    ADD CONSTRAINT fk_agent_chat_sessions_pipeline_run FOREIGN KEY (pipeline_run_id)
        REFERENCES AGENT_pipeline_runs (id) ON DELETE RESTRICT;

ALTER TABLE AGENT_chat_messages
    DROP FOREIGN KEY fk_agent_chat_messages_pipeline_run,
    DROP COLUMN pipeline_run_id;
