package com.vivo.lanxin.campus.repository;

import com.vivo.lanxin.campus.model.Reminder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByUserIdOrderByDueDateAsc(long userId);

    List<Reminder> findByUserIdAndCompletedFalseOrderByDueDateAsc(long userId);

    List<Reminder> findByUserIdAndCompletedFalseAndDueDateLessThanEqualOrderByDueDateAsc(long userId, LocalDate date);

    List<Reminder> findByUserIdAndCompletedFalseAndDueDateBetweenOrderByDueDateAsc(long userId, LocalDate start, LocalDate end);

    List<Reminder> findByUserIdAndCompletedFalse(long userId);

    // Completed reminders with pagination support
    List<Reminder> findByUserIdAndCompletedTrueOrderByCompletedAtDesc(long userId, Pageable pageable);

    // Overdue (past due and not completed)
    List<Reminder> findByUserIdAndCompletedFalseAndDueDateLessThanOrderByDueDateAsc(long userId, LocalDate today);

    long countByUserIdAndCompletedFalse(long userId);

    long countByUserIdAndCompletedFalseAndDueDateLessThan(long userId, LocalDate today);

    long countByUserIdAndCompletedFalseAndDueDateBetween(long userId, LocalDate start, LocalDate end);

    long countByUserIdAndCompletedFalseAndDueDateLessThanEqual(long userId, LocalDate date);

    long countByUserIdAndCompletedTrue(long userId);

    long countByUserIdAndCompletedTrueAndCompletedAtBetween(long userId, LocalDateTime start, LocalDateTime end);
}
