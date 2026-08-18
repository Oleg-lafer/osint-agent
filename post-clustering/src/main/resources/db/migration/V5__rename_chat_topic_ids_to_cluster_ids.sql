ALTER TABLE AGENT_chat_messages
    RENAME COLUMN topic_ids TO cluster_ids;

ALTER TABLE AGENT_post_processing
    RENAME COLUMN topic_cluster_id TO cluster_id;
