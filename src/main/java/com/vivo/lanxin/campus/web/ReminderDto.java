package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Reminder;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ReminderDto(
        long id,
        String title,
        String course,
        LocalDate dueDate,
        String priority,
        String source,
        Long relatedNoteId,
        boolean completed,
        LocalDateTime completedAt,
        String recurrence,
        LocalDateTime createdAt
) {
    public static ReminderDto from(Reminder reminder) {
        return new ReminderDto(
                reminder.getId(),
                reminder.getTitle(),
                reminder.getCourse(),
                reminder.getDueDate(),
                reminder.getPriority(),
                reminder.getSource(),
                reminder.getRelatedNoteId(),
                reminder.isCompleted(),
                reminder.getCompletedAt(),
                reminder.getRecurrence() != null ? reminder.getRecurrence() : "none",
                reminder.getCreatedAt()
        );
    }
}
