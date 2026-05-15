package com.vivo.lanxin.campus.repository;

import com.vivo.lanxin.campus.model.Reminder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByUserIdOrderByDueDateAsc(long userId);

    List<Reminder> findByUserIdAndCompletedFalseOrderByDueDateAsc(long userId);

    List<Reminder> findByUserIdAndCompletedFalseAndDueDateLessThanEqualOrderByDueDateAsc(long userId, LocalDate date);

    List<Reminder> findByUserIdAndCompletedFalse(long userId);

    long countByUserIdAndCompletedFalse(long userId);

    long countByUserIdAndCompletedFalseAndDueDateLessThanEqual(long userId, LocalDate date);

    long countByUserIdAndCompletedTrue(long userId);
}
