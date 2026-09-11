package com.example.knowledgecopilot.knowledge.document;

import java.util.Set;

public enum DocumentStatus {
    UPLOADED,
    PARSING,
    PARSED,
    INDEXING,
    AVAILABLE,
    PARSE_FAILED,
    INDEX_FAILED,
    PROCESS_FAILED,
    DELETING,
    DELETED,
    DELETE_FAILED;

    public static final Set<String> PROCESSING =
            Set.of("UPLOADED", "PARSING", "PARSED", "INDEXING");
    public static final Set<String> RETRYABLE =
            Set.of("PARSE_FAILED", "INDEX_FAILED", "PROCESS_FAILED");
}
