CREATE TABLE knowledge_base (
 id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL, description VARCHAR(1000),
 corpus_revision BIGINT NOT NULL DEFAULT 0, is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_document (
 id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, original_name VARCHAR(255) NOT NULL,
 storage_path VARCHAR(1024) NOT NULL, file_type VARCHAR(16) NOT NULL, file_size BIGINT NOT NULL,
 sha256 VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_no INT NOT NULL DEFAULT 1,
 chunk_count INT NOT NULL DEFAULT 0, pipeline_config_json LONGTEXT, error_code VARCHAR(64), error_message VARCHAR(1000),
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_document_base_status ON knowledge_document(knowledge_base_id, status);
CREATE TABLE document_chunk (
 id BIGINT PRIMARY KEY, knowledge_base_id BIGINT NOT NULL, document_id BIGINT NOT NULL,
 attempt_no INT NOT NULL, chunk_index INT NOT NULL, content LONGTEXT NOT NULL, token_count INT NOT NULL,
 page INT, section VARCHAR(1000), embedding_id VARCHAR(128) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CONSTRAINT uk_chunk_position UNIQUE(document_id, attempt_no, chunk_index),
 CONSTRAINT uk_chunk_embedding UNIQUE(embedding_id)
);
CREATE INDEX idx_chunk_base ON document_chunk(knowledge_base_id);
