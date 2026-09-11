CREATE TABLE evaluation_dataset (
 id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, description VARCHAR(1000),
 revision BIGINT NOT NULL DEFAULT 0, is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dataset_base ON evaluation_dataset(knowledge_base_id);
CREATE TABLE evaluation_case (
 id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, question VARCHAR(4000) NOT NULL, expected_answer LONGTEXT,
 relevant_document_ids_json LONGTEXT NOT NULL, evidence_json LONGTEXT NOT NULL,
 expected_refusal BOOLEAN NOT NULL, tags_json LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_case_dataset ON evaluation_case(dataset_id);
CREATE TABLE evaluation_run (
 id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 case_snapshot_json LONGTEXT NOT NULL, config_snapshot_json LONGTEXT NOT NULL, corpus_manifest_json LONGTEXT NOT NULL,
 dataset_revision BIGINT NOT NULL, corpus_revision BIGINT NOT NULL, metrics_json LONGTEXT,
 total_cases INT NOT NULL, completed_cases INT NOT NULL DEFAULT 0, error_code VARCHAR(64), error_message VARCHAR(1000),
 started_at TIMESTAMP NULL, finished_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_run_dataset_status ON evaluation_run(dataset_id, status);
CREATE TABLE evaluation_case_result (
 id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL, case_id BIGINT NOT NULL, case_snapshot_json LONGTEXT NOT NULL,
 status VARCHAR(32) NOT NULL, retrieved_chunks_json LONGTEXT, ranked_documents_json LONGTEXT,
 metrics_json LONGTEXT, latency_ms BIGINT, error_code VARCHAR(64), error_message VARCHAR(1000),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_result_case UNIQUE(run_id, case_id)
);
