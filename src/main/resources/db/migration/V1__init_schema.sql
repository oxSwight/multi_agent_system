-- ============================================================================
-- V1__init_schema.sql
-- MIDAS_D3 — Initial database schema
--
-- Tables:
--   midas_run       — one record per pipeline execution
--   midas_agent_log — one record per agent invocation within a run
--
-- Constraints / Indices follow each table definition.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- midas_run
-- Stores the lifecycle and aggregated metrics of every pipeline execution.
-- ---------------------------------------------------------------------------
CREATE TABLE midas_run (
    id                      VARCHAR(255)    NOT NULL,
    chat_id                 BIGINT,
    raw_user_idea           TEXT,
    status                  VARCHAR(50),
    artifact_path           VARCHAR(1000),
    total_prompt_tokens     INT             NOT NULL DEFAULT 0,
    total_completion_tokens INT             NOT NULL DEFAULT 0,
    needs_refactoring       BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP       NOT NULL,
    updated_at              TIMESTAMP       NOT NULL,

    CONSTRAINT pk_midas_run PRIMARY KEY (id)
);

CREATE INDEX idx_midas_run_status     ON midas_run (status);
CREATE INDEX idx_midas_run_created_at ON midas_run (created_at DESC);
CREATE INDEX idx_midas_run_chat_id    ON midas_run (chat_id) WHERE chat_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- midas_agent_log
-- One record per agent invocation. run_id FK links to the parent pipeline run.
-- ---------------------------------------------------------------------------
CREATE TABLE midas_agent_log (
    id                UUID            NOT NULL,
    run_id            VARCHAR(255)    NOT NULL,
    agent_type        VARCHAR(100),
    raw_output        TEXT,
    prompt_tokens     INT             NOT NULL DEFAULT 0,
    completion_tokens INT             NOT NULL DEFAULT 0,
    execution_time_ms BIGINT          NOT NULL DEFAULT 0,
    is_error          BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP       NOT NULL,

    CONSTRAINT pk_midas_agent_log    PRIMARY KEY (id),
    CONSTRAINT fk_agent_log_run      FOREIGN KEY (run_id)
        REFERENCES midas_run (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_agent_log_run_id    ON midas_agent_log (run_id);
CREATE INDEX idx_agent_log_is_error  ON midas_agent_log (run_id, is_error) WHERE is_error = TRUE;
