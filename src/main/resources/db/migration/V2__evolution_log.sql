-- ============================================================================
-- V2__evolution_log.sql
-- MIDAS_D3 — Evolution changelog schema
--
-- Table:
--   midas_evolution_log — one record per Evolution Agent analysis cycle,
--                         linked to the analyzed pipeline run.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- midas_evolution_log
-- Stores the refactoring report produced by the EvolutionAgent for each
-- completed pipeline run that had needs_refactoring = true.
-- ---------------------------------------------------------------------------
CREATE TABLE midas_evolution_log (
    id                 UUID            NOT NULL,
    run_id             VARCHAR(255)    NOT NULL,
    refactoring_report TEXT,
    created_at         TIMESTAMP       NOT NULL,

    CONSTRAINT pk_midas_evolution_log  PRIMARY KEY (id),
    CONSTRAINT fk_evolution_log_run    FOREIGN KEY (run_id)
        REFERENCES midas_run (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_evolution_log_run_id     ON midas_evolution_log (run_id);
CREATE INDEX idx_evolution_log_created_at ON midas_evolution_log (created_at DESC);
