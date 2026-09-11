package com.example.knowledgecopilot.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JsonSupport {
    private final ObjectMapper mapper;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 数据无效", ex);
        }
    }

    public <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 数据无效", ex);
        }
    }
}
