package com.example.knowledgecopilot.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

public record PageResult<T>(long total, long pageNo, long pageSize, List<T> records) {
    public static <T> Page<T> page(long pageNo, long pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) {
            throw BusinessException.invalid("pageNo >= 1，pageSize 范围为 1~100");
        }
        return new Page<>(pageNo, pageSize);
    }

    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getRecords());
    }
}
