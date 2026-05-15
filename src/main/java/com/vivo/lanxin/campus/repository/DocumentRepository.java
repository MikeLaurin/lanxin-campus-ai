package com.vivo.lanxin.campus.repository;

import com.vivo.lanxin.campus.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByUserIdOrderByCreatedAtDesc(long userId);
    long countByUserId(long userId);
}
