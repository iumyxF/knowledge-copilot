package com.example.knowledgecopilot.knowledge.document;

public record DocumentView(
        Long id,
        Long knowledgeBaseId,
        String originalName,
        String fileType,
        long fileSize,
        String sha256,
        String status,
        int attemptNo,
        int chunkCount,
        String errorCode,
        String errorMessage) {
    public static DocumentView of(KnowledgeDocument document) {
        return new DocumentView(
                document.getId(),
                document.getKnowledgeBaseId(),
                document.getOriginalName(),
                document.getFileType(),
                document.getFileSize(),
                document.getSha256(),
                document.getStatus(),
                document.getAttemptNo(),
                document.getChunkCount(),
                document.getErrorCode(),
                document.getErrorMessage());
    }
}
