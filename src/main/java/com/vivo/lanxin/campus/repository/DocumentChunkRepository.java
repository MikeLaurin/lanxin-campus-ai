package com.vivo.lanxin.campus.repository;

import com.vivo.lanxin.campus.model.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(long documentId);
    List<DocumentChunk> findByUserId(long userId);
    void deleteByDocumentId(long documentId);
    int countByDocumentId(long documentId);
}
