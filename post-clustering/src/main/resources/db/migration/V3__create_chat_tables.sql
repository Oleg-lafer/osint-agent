CREATE TABLE AGENT_chat_sessions (
    id CHAR(36) NOT NULL,
    user_id VARCHAR(255) NULL,
    status ENUM('ACTIVE', 'CLOSED') NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    closed_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE AGENT_chat_messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id CHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    role ENUM('USER', 'ASSISTANT') NOT NULL,
    content TEXT NULL,
    pipeline_run_id BIGINT NULL,
    topic_ids JSON NULL,
    sources JSON NULL,
    research_log JSON NULL,
    elapsed_ms BIGINT NULL,
    model VARCHAR(100) NULL,
    status ENUM('PENDING', 'COMPLETED', 'FAILED') NOT NULL,
    error_message TEXT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    CONSTRAINT uq_agent_chat_message_sequence UNIQUE (session_id, sequence_number),
    INDEX ix_agent_chat_messages_session_created (session_id, created_at),
    CONSTRAINT fk_agent_chat_messages_session FOREIGN KEY (session_id)
        REFERENCES AGENT_chat_sessions (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_chat_messages_pipeline_run FOREIGN KEY (pipeline_run_id)
        REFERENCES AGENT_pipeline_runs (id) ON DELETE SET NULL
) ENGINE=InnoDB;
