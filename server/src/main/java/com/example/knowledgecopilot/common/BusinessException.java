package com.example.knowledgecopilot.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BusinessException notFound() {
        return new BusinessException(404, "NOT_FOUND", "资源不存在");
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, "STATE_CONFLICT", message);
    }

    public static BusinessException invalid(String message) {
        return new BusinessException(400, "INVALID_ARGUMENT", message);
    }
}
