package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Note;

import java.time.LocalDateTime;
import java.util.List;

public record NoteDto(
        long id,
        String title,
        String course,
        String folderPath,
        String rawText,
        String summary,
        List<String> keyPoints,
        List<String> formulas,
        List<String> tags,
        String mindMap,
        boolean offlineCreated,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static NoteDto from(Note note) {
        return new NoteDto(
                note.getId(),
                note.getTitle(),
                note.getCourse(),
                note.getFolderPath(),
                note.getRawText(),
                note.getSummary(),
                note.getKeyPoints(),
                note.getFormulas(),
                note.getTags(),
                note.getMindMap(),
                note.isOfflineCreated(),
                note.getCreatedAt(),
                note.getUpdatedAt()
        );
    }
}
