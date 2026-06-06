package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.model.Reminder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

final class ControllerUtils {
    private ControllerUtils() {
    }

    static Note buildNote(NoteRequest request) {
        Note note = new Note();
        note.setTitle(InputSanitizer.clean(request.title(), 120));
        note.setCourse(InputSanitizer.clean(first(request.course(), "专业课程"), 80));
        note.setFolderPath(InputSanitizer.clean(request.folderPath(), 200));
        note.setRawText(InputSanitizer.clean(request.rawText(), 20_000));
        note.setSummary(InputSanitizer.clean(request.summary(), 4_000));
        note.setKeyPoints(InputSanitizer.cleanList(request.keyPoints(), 200, 20));
        note.setFormulas(InputSanitizer.cleanList(request.formulas(), 200, 20));
        note.setTags(InputSanitizer.cleanList(request.tags(), 40, 12));
        note.setMindMap(InputSanitizer.clean(request.mindMap(), 6_000));
        note.setOfflineCreated(request.offlineCreated());
        return note;
    }

    static Reminder buildReminder(long userId, ReminderRequest request) {
        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setTitle(InputSanitizer.clean(request.title(), 120));
        reminder.setCourse(first(InputSanitizer.nullable(request.course(), 80), ""));
        reminder.setDueDate(request.dueDate() == null ? LocalDate.now().plusDays(1) : request.dueDate());
        reminder.setPriority(first(InputSanitizer.nullable(request.priority(), 20), "medium"));
        reminder.setSource(first(InputSanitizer.nullable(request.source(), 200), "手动创建"));
        reminder.setRelatedNoteId(request.relatedNoteId());
        reminder.setRecurrence(first(InputSanitizer.nullable(request.recurrence(), 20), "none"));
        return reminder;
    }

    static String first(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    static List<Long> limitIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && id > 0).limit(50).toList();
    }

    static String detectFileType(String filename) {
        if (filename == null) return "TXT";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md")) return "TXT";
        if (lower.endsWith(".docx")) return "DOCX";
        return "UNKNOWN";
    }

    static List<Map<String, Object>> buildFolderTree(List<String> paths) {
        Map<String, Map<String, Object>> nodeMap = new java.util.LinkedHashMap<>();
        for (String path : paths) {
            if (path == null || path.isBlank()) continue;
            String[] parts = path.split("/");
            StringBuilder currentPath = new StringBuilder();
            for (String part : parts) {
                if (part.isBlank()) continue;
                String prefix = currentPath.toString();
                currentPath.append(currentPath.length() > 0 ? "/" : "").append(part);
                String fullPath = currentPath.toString();
                nodeMap.putIfAbsent(fullPath, new java.util.LinkedHashMap<>());
                Map<String, Object> node = nodeMap.get(fullPath);
                node.put("name", part);
                node.put("path", fullPath);
                node.putIfAbsent("children", new java.util.ArrayList<>());
                if (!prefix.isEmpty() && nodeMap.containsKey(prefix)) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> siblings =
                            (java.util.List<Map<String, Object>>) nodeMap.get(prefix).get("children");
                    boolean exists = siblings.stream().anyMatch(c -> fullPath.equals(c.get("path")));
                    if (!exists) {
                        siblings.add(node);
                    }
                }
            }
        }
        return nodeMap.values().stream()
                .filter(n -> !((String) n.get("path")).contains("/"))
                .toList();
    }
}
