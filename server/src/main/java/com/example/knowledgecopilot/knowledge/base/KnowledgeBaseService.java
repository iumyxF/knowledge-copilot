package com.example.knowledgecopilot.knowledge.base;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.knowledgecopilot.common.BusinessException;
import com.example.knowledgecopilot.common.PageResult;
import com.example.knowledgecopilot.evaluation.EvaluationDataset;
import com.example.knowledgecopilot.evaluation.EvaluationDatasetMapper;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocument;
import com.example.knowledgecopilot.knowledge.document.KnowledgeDocumentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {
    private final KnowledgeBaseMapper mapper;
    private final KnowledgeDocumentMapper documents;
    private final EvaluationDatasetMapper datasets;

    public KnowledgeBase require(Long id) {
        KnowledgeBase base = mapper.selectById(id);
        if (base == null || Boolean.TRUE.equals(base.getIsDeleted())) {
            throw BusinessException.notFound();
        }
        return base;
    }

    /**
     * 关联资源新增和知识库删除使用同一行锁，避免产生孤立资源。
     */
    public KnowledgeBase lock(Long id) {
        KnowledgeBase base =
                mapper.selectOne(
                        new LambdaQueryWrapper<KnowledgeBase>()
                                .eq(KnowledgeBase::getId, id)
                                .last("FOR UPDATE"));
        if (base == null || Boolean.TRUE.equals(base.getIsDeleted())) {
            throw BusinessException.notFound();
        }
        return base;
    }

    public KnowledgeBase create(String name, String description) {
        KnowledgeBase base = new KnowledgeBase();
        base.setName(name.trim());
        base.setDescription(description);
        base.setCorpusRevision(0L);
        base.setIsDeleted(false);
        mapper.insert(base);
        return require(base.getId());
    }

    public PageResult<KnowledgeBase> list(long pageNo, long pageSize) {
        return PageResult.of(
                mapper.selectPage(
                        PageResult.page(pageNo, pageSize),
                        new LambdaQueryWrapper<KnowledgeBase>()
                                .eq(KnowledgeBase::getIsDeleted, false)
                                .orderByDesc(KnowledgeBase::getId)));
    }

    @Transactional
    public KnowledgeBase update(Long id, String name, String description) {
        KnowledgeBase base = lock(id);
        mapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, id)
                        .set(KnowledgeBase::getName, name.trim())
                        .set(KnowledgeBase::getDescription, description)
                        .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now()));
        return require(id);
    }

    @Transactional
    public void delete(Long id) {
        lock(id);
        if (documents.selectCount(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getKnowledgeBaseId, id)
                        .ne(KnowledgeDocument::getStatus, "DELETED"))
                > 0
                || datasets.selectCount(
                new LambdaQueryWrapper<EvaluationDataset>()
                        .eq(EvaluationDataset::getKnowledgeBaseId, id)
                        .eq(EvaluationDataset::getIsDeleted, false))
                > 0) {
            throw BusinessException.conflict("请先删除知识库中的文档和评测集");
        }
        mapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, id)
                        .set(KnowledgeBase::getIsDeleted, true)
                        .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now()));
    }

    public void incrementRevision(Long id) {
        mapper.update(
                null,
                new LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, id)
                        .setSql("corpus_revision = corpus_revision + 1")
                        .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now()));
    }
}
