package com.example.knowledgecopilot.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        ready = true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        try {
            if (!ready && request.getRequestURI().startsWith("/api/v1/")) {
                response.setStatus(503);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter()
                        .write(
                                "{\"code\":\"STARTING\",\"message\":\"服务正在恢复任务，请稍后重试\",\"data\":null,\"requestId\":\""
                                        + requestId
                                        + "\"}");
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
