package com.vivo.lanxin.campus.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record ReminderRequest(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 80) String course,
        LocalDate dueDate,
        @Pattern(regexp = "high|medium|low") String priority,
        @Size(max = 200) String source,
        Long relatedNoteId,
        @Size(max = 20) String recurrence
) {}
