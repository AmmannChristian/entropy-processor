-- V1 -- Initial Schema for Entropy Processor
--
-- Creates the four core tables used by the entropy-processor service:
--
--   1. entropy_data         -- Raw TDC decay events (TimescaleDB hypertable)
--   2. nist_test_results    -- NIST SP 800-22 statistical test outcomes
--   3. nist_90b_results     -- NIST SP 800-90B entropy assessment outcomes
--   4. data_quality_reports -- Periodic data-quality summary reports
--
-- Tables 1-3 are converted to TimescaleDB hypertables for efficient
-- time-range queries and automatic chunk-based retention management.

-- Enable TimescaleDB extension (idempotent).
CREATE EXTENSION IF NOT EXISTS timescaledb;


-- 1. entropy_data -- Raw Entropy Events
-- Each row represents a single radioactive-decay event captured by the TDC
-- hardware on the Raspberry Pi edge gateway. Events arrive in gRPC batches
-- and are bulk-inserted by EntropyDataPersistenceService.
--
-- The composite primary key (id, server_received) is required by TimescaleDB
-- because the hypertable partition column must be part of any unique constraint.

CREATE SEQUENCE IF NOT EXISTS entropy_data_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS entropy_data (
    id                BIGINT        NOT NULL DEFAULT nextval('entropy_data_SEQ'),
    batch_id          VARCHAR(64),                     -- Gateway-assigned batch identifier.
    timestamp         VARCHAR(64)   NOT NULL,           -- ISO-8601 string derived from rpi_timestamp_us (gateway ingestion time).
    hw_timestamp_ns   BIGINT        NOT NULL,           -- TDC hardware timestamp in nanoseconds (converted from picoseconds).
    rpi_timestamp_us  BIGINT,                           -- Gateway ingestion timestamp in microseconds since epoch.
    tdc_timestamp_ps  BIGINT,                           -- Raw TDC timestamp in picoseconds (original precision).
    channel           INTEGER,                          -- TDC input channel that detected the decay event.
    whitened_entropy  BYTEA,                            -- XOR-folded byte array derived from TDC and RPI timestamps.
    sequence          BIGINT        NOT NULL,           -- Monotonically increasing sequence number (gap detection).
    server_received   TIMESTAMPTZ   NOT NULL DEFAULT NOW(), -- Server-side reception timestamp (hypertable partition key).
    network_delay_ms  BIGINT,                           -- Estimated delay from edge gateway ingestion to cloud server reception, in milliseconds.
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(), -- Row insertion timestamp.
    source_address    VARCHAR(45)   DEFAULT NULL,       -- IP address of the edge gateway that sent this event.
    quality_score     DOUBLE PRECISION DEFAULT 1.0,     -- Per-event quality score in [0.0, 1.0].

    PRIMARY KEY (id, server_received)
);

-- Convert to hypertable partitioned by server_received with 1-day chunks.
-- One-day chunks balance query performance against chunk management overhead
-- for the expected ingestion rate of approximately 600 events per second.
SELECT create_hypertable('entropy_data', 'server_received',
    chunk_time_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Indexes for common query patterns on entropy_data.
CREATE INDEX IF NOT EXISTS idx_entropy_batch_id ON entropy_data(batch_id);
CREATE INDEX IF NOT EXISTS idx_hw_timestamp_ns ON entropy_data(hw_timestamp_ns);
CREATE INDEX IF NOT EXISTS idx_sequence ON entropy_data(sequence);
CREATE INDEX IF NOT EXISTS idx_server_received ON entropy_data(server_received);

-- Composite index for time-windowed queries that also sort by hardware timestamp
-- (used by DataQualityService and EntropyStatisticsService).
CREATE INDEX IF NOT EXISTS idx_entropy_server_received_hw_timestamp_ns
    ON entropy_data (server_received, hw_timestamp_ns);


-- 2. nist_test_results -- NIST SP 800-22 Statistical Test Results
-- Stores individual test outcomes from the NIST SP 800-22 statistical test
-- suite. Each row corresponds to one named test (e.g., Frequency, Runs,
-- Serial) executed as part of a test-suite run identified by test_suite_run_id.

CREATE SEQUENCE IF NOT EXISTS nist_test_results_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS nist_test_results (
    id                BIGINT           NOT NULL DEFAULT nextval('nist_test_results_SEQ'),
    batch_id          VARCHAR(64),                      -- Entropy batch that provided the input data.
    test_suite_run_id UUID             NOT NULL,        -- Groups all tests belonging to a single suite execution.
    test_name         VARCHAR(100)     NOT NULL,        -- NIST test identifier (e.g., "Frequency", "BlockFrequency").
    passed            BOOLEAN          NOT NULL,        -- Whether the test passed at the configured significance level.
    p_value           DOUBLE PRECISION,                 -- Computed p-value; null if the test did not produce one.
    bits_tested       BIGINT,                           -- Number of bits submitted to this individual test.
    data_sample_size  BIGINT,                           -- Original sample size before any truncation.
    window_start      TIMESTAMPTZ      NOT NULL,        -- Start of the entropy data time window under test.
    window_end        TIMESTAMPTZ      NOT NULL,        -- End of the entropy data time window under test.
    executed_at       TIMESTAMPTZ      NOT NULL DEFAULT NOW(), -- Timestamp when this test was executed (partition key).
    details           JSONB,                            -- Additional test-specific output (e.g., sub-test statistics).

    PRIMARY KEY (id, executed_at)
);

-- Seven-day chunks suit the lower write frequency of NIST test executions
-- (typically once per scheduled interval rather than continuous ingestion).
SELECT create_hypertable('nist_test_results', 'executed_at',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

CREATE INDEX IF NOT EXISTS idx_test_suite_run ON nist_test_results(test_suite_run_id);
CREATE INDEX IF NOT EXISTS idx_executed_at ON nist_test_results(executed_at);
CREATE INDEX IF NOT EXISTS idx_passed ON nist_test_results(passed);


-- 3. nist_90b_results -- NIST SP 800-90B Entropy Assessment Results
-- Stores entropy estimates produced by the NIST SP 800-90B assessment service.
-- Each row contains multiple entropy estimator outputs (min-entropy, Shannon,
-- collision, Markov, compression) for a single assessment run.

CREATE SEQUENCE IF NOT EXISTS nist_90b_results_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS nist_90b_results (
    id                    BIGINT           NOT NULL DEFAULT nextval('nist_90b_results_SEQ'),
    batch_id              VARCHAR(64),                   -- Entropy batch that provided the input data.
    min_entropy           DOUBLE PRECISION,              -- Min-entropy estimate in bits per sample.
    shannon_entropy       DOUBLE PRECISION,              -- Shannon entropy estimate in bits per sample.
    collision_entropy     DOUBLE PRECISION,              -- Collision entropy (Renyi order 2) in bits per sample.
    markov_entropy        DOUBLE PRECISION,              -- Markov model entropy estimate in bits per sample.
    compression_entropy   DOUBLE PRECISION,              -- Compression-based entropy estimate in bits per sample.
    passed                BOOLEAN          NOT NULL,     -- Whether the overall assessment passed.
    bits_tested           BIGINT,                        -- Number of bits submitted for assessment.
    window_start          TIMESTAMPTZ      NOT NULL,     -- Start of the entropy data time window assessed.
    window_end            TIMESTAMPTZ      NOT NULL,     -- End of the entropy data time window assessed.
    executed_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(), -- Assessment execution timestamp (partition key).
    assessment_details    JSONB,                         -- Full estimator-level output from the 90B service.

    PRIMARY KEY (id, executed_at)
);

SELECT create_hypertable('nist_90b_results', 'executed_at',
    chunk_time_interval => INTERVAL '7 days',
    if_not_exists => TRUE
);

CREATE INDEX IF NOT EXISTS idx_90b_executed_at ON nist_90b_results(executed_at);
CREATE INDEX IF NOT EXISTS idx_90b_passed ON nist_90b_results(passed);


-- 4. data_quality_reports -- Periodic Quality Assessment Summaries
-- Stores composite quality reports generated by DataQualityService. Each report
-- covers a time window of entropy events and includes metrics for packet loss,
-- clock drift, decay-rate plausibility, and network delay stability.
--
-- This table is a regular (non-hypertable) table because report volume is low
-- and time-based partitioning provides no meaningful benefit.

CREATE SEQUENCE IF NOT EXISTS data_quality_reports_SEQ START WITH 1 INCREMENT BY 50;

CREATE TABLE IF NOT EXISTS data_quality_reports (
    id                        BIGINT           NOT NULL DEFAULT nextval('data_quality_reports_SEQ') PRIMARY KEY,
    report_timestamp          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),  -- When this report was generated.
    window_start              TIMESTAMPTZ      NOT NULL,                -- Start of the analysed event window.
    window_end                TIMESTAMPTZ      NOT NULL,                -- End of the analysed event window.
    total_events              BIGINT           NOT NULL,                -- Number of events in the window.
    missing_sequence_count    INTEGER,                                  -- Count of missing sequence numbers (packet loss).
    clock_drift_us_per_hour   DOUBLE PRECISION,                        -- Estimated clock drift rate in microseconds per hour.
    average_network_delay_ms  DOUBLE PRECISION,                        -- Mean network delay across all events in the window.
    average_decay_interval_ms DOUBLE PRECISION,                        -- Mean inter-event interval in milliseconds.
    decay_rate_realistic      BOOLEAN,                                 -- Whether the observed decay rate is plausible for entropy.
    overall_quality_score     DOUBLE PRECISION,                        -- Composite quality score in [0.0, 1.0].
    recommendations           TEXT[]                                   -- Array of human-readable improvement suggestions.
);

CREATE INDEX IF NOT EXISTS idx_report_timestamp ON data_quality_reports(report_timestamp);
CREATE INDEX IF NOT EXISTS idx_quality_score ON data_quality_reports(overall_quality_score);
CREATE INDEX IF NOT EXISTS idx_window_start ON data_quality_reports(window_start);
