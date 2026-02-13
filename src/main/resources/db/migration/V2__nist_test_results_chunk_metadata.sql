-- V2 -- Add chunk metadata for SP 800-22 chunked executions
--
-- Stores which chunk within a run produced each persisted test row.
-- This enables deterministic reconstruction of window/run/chunk mappings.

ALTER TABLE nist_test_results
    ADD COLUMN IF NOT EXISTS chunk_index INTEGER;

ALTER TABLE nist_test_results
    ADD COLUMN IF NOT EXISTS chunk_count INTEGER;

CREATE INDEX IF NOT EXISTS idx_test_suite_run_chunk
    ON nist_test_results (test_suite_run_id, chunk_index);
