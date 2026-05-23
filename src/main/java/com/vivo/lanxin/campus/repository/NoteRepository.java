package com.vivo.lanxin.campus.repository;

import com.vivo.lanxin.campus.model.Note;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NoteRepository extends JpaRepository<Note, Long> {
    List<Note> findByUserIdOrderByUpdatedAtDesc(long userId);

    long countByUserId(long userId);

    @Query("SELECT DISTINCT CAST(n.createdAt AS java.time.LocalDate) FROM Note n WHERE n.userId = :userId ORDER BY CAST(n.createdAt AS java.time.LocalDate) DESC")
    List<java.time.LocalDate> findStudyDatesByUserId(@Param("userId") long userId);

    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND " +
           "(LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(n.course) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Note> searchByUser(@Param("userId") long userId, @Param("keyword") String keyword);

    @Query("SELECT n FROM Note n WHERE n.userId = :userId AND " +
           "(LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(n.course) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(n.rawText) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(n.summary) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<Note> fullTextSearch(@Param("userId") long userId, @Param("keyword") String keyword);
}
