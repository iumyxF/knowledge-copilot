package com.example.knowledgecopilot.common;

import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException ex) {
        return error(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class
    })
    public ResponseEntity<ApiResponse<Void>> invalid(Exception ex) {
        return error(400, "INVALID_ARGUMENT", "请求参数不符合约束，请检查接口文档");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> oversized(Exception ex) {
        return error(413, "FILE_TOO_LARGE", "文件超过大小限制");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> missing(Exception ex) {
        return error(404, "NOT_FOUND", "资源不存在");
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> method(Exception ex) {
        return error(405, "METHOD_NOT_ALLOWED", "请求方法不支持");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> mediaType(Exception ex) {
        return error(415, "UNSUPPORTED_MEDIA_TYPE", "请求内容类型不支持");
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(Exception ex) {
        log.error("数据库操作失败，requestId={}", MDC.get("requestId"));
        return error(503, "DATABASE_UNAVAILABLE", "数据库操作失败");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception ex) {
        log.error("请求失败，requestId={}", MDC.get("requestId"), ex);
        return error(500, "INTERNAL_ERROR", "内部处理失败，请使用 requestId 排查");
    }

    private ResponseEntity<ApiResponse<Void>> error(int status, String code, String message) {
        return ResponseEntity.status(status)
                .body(new ApiResponse<>(code, message, null, MDC.get("requestId")));
    }
}
