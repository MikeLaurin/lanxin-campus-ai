package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.model.Reminder;
import com.vivo.lanxin.campus.repository.NoteRepository;
import com.vivo.lanxin.campus.repository.ReminderRepository;
import com.vivo.lanxin.campus.service.AiMockService;
import com.vivo.lanxin.campus.service.AuthService;
import com.vivo.lanxin.campus.service.LanxinApiClient;
import com.vivo.lanxin.campus.service.RagService;
import com.vivo.lanxin.campus.model.Document;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AppController {
    private final NoteRepository noteRepo;
    private final ReminderRepository reminderRepo;
    private final AuthService auth;
    private final AiMockService ai;
    private final LanxinApiClient lanxin;
    private final RagService ragService;

    public AppController(NoteRepository noteRepo, ReminderRepository reminderRepo,
                         AuthService auth, AiMockService ai, LanxinApiClient lanxin,
                         RagService ragService) {
        this.noteRepo = noteRepo;
        this.reminderRepo = reminderRepo;
        this.auth = auth;
        this.ai = ai;
        this.lanxin = lanxin;
        this.ragService = ragService;
    }

    // ── Auth ──────────────────────────────────────────────

    @PostMapping("/user/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        return auth.login(username, password);
    }

    @PostMapping("/user/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        return auth.register(
                body.getOrDefault("username", ""),
                body.getOrDefault("password", ""),
                body.getOrDefault("name", ""),
                body.getOrDefault("school", ""),
                body.getOrDefault("major", ""),
                body.getOrDefault("grade", "")
        );
    }

    @PostMapping("/user/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        auth.logout(authHeader);
        return Map.of("ok", true);
    }

    @GetMapping("/user/profile")
    public Map<String, Object> profile(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        Map<String, Object> info = auth.getUserInfo(userId);
        info.put("slogan", "你的校园学习搭子，更懂你的 AI 管家");
        return info;
    }

    // ── Notes ─────────────────────────────────────────────

    @GetMapping("/notes")
    public List<Note> notes(@RequestHeader("Authorization") String authHeader,
                            @RequestParam(required = false) String keyword) {
        long userId = auth.getUserId(authHeader);
        if (keyword != null && !keyword.isBlank()) {
            return noteRepo.searchByUser(userId, keyword);
        }
        return noteRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @PostMapping("/notes")
    public Note createNote(@RequestHeader("Authorization") String authHeader,
                           @Valid @RequestBody NoteRequest request) {
        Note note = buildNote(request);
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        note.setRagDocumentId(ragService.indexNote(note));
        return noteRepo.save(note);
    }

    @GetMapping("/notes/{id}")
    public ResponseEntity<Note> note(@RequestHeader("Authorization") String authHeader,
                                      @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id)
                .filter(n -> n.getUserId() == userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/notes/{id}")
    public ResponseEntity<Note> updateNote(@RequestHeader("Authorization") String authHeader,
                                            @PathVariable long id, @RequestBody NoteRequest request) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId).map(note -> {
            note.setTitle(first(request.title(), note.getTitle()));
            note.setCourse(first(request.course(), note.getCourse()));
            note.setRawText(first(request.rawText(), note.getRawText()));
            note.setSummary(first(request.summary(), note.getSummary()));
            if (request.keyPoints() != null) note.setKeyPoints(request.keyPoints());
            if (request.formulas() != null) note.setFormulas(request.formulas());
            if (request.tags() != null) note.setTags(request.tags());
            note.setMindMap(first(request.mindMap(), note.getMindMap()));
            note = noteRepo.save(note);
            ragService.reindexNote(note);
            return ResponseEntity.ok(noteRepo.save(note));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/notes/{id}")
    public ResponseEntity<Void> deleteNote(@RequestHeader("Authorization") String authHeader,
                                            @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId).map(note -> {
            if (note.getRagDocumentId() != null) {
                ragService.deleteNoteDocument(note.getRagDocumentId());
            }
            noteRepo.delete(note);
            return ResponseEntity.noContent().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/notes/{id}/mindmap")
    public ResponseEntity<Map<String, String>> noteMindMap(@RequestHeader("Authorization") String authHeader,
                                                            @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId)
                .map(note -> ResponseEntity.ok(Map.of("mindMap", note.getMindMap())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/notes/batch-sync")
    public Map<String, Object> batchSync(@RequestHeader("Authorization") String authHeader,
                                          @RequestBody List<NoteRequest> notes) {
        long userId = auth.getUserId(authHeader);
        List<Note> saved = notes.stream().map(req -> {
            Note note = buildNote(req);
            note.setUserId(userId);
            note = noteRepo.save(note);
            note.setRagDocumentId(ragService.indexNote(note));
            return noteRepo.save(note);
        }).toList();
        return Map.of("synced", saved.size(), "items", saved);
    }

    // ── Reminders ─────────────────────────────────────────

    @GetMapping("/reminders")
    public List<Reminder> reminders(@RequestHeader("Authorization") String authHeader,
                                    @RequestParam(defaultValue = "false") boolean includeCompleted) {
        long userId = auth.getUserId(authHeader);
        if (includeCompleted) {
            return reminderRepo.findByUserIdOrderByDueDateAsc(userId);
        }
        return reminderRepo.findByUserIdAndCompletedFalse(userId);
    }

    @PostMapping("/reminders")
    public Reminder createReminder(@RequestHeader("Authorization") String authHeader,
                                    @Valid @RequestBody ReminderRequest request) {
        Reminder reminder = new Reminder();
        reminder.setUserId(auth.getUserId(authHeader));
        reminder.setTitle(request.title());
        reminder.setCourse(first(request.course(), "专业课程"));
        reminder.setDueDate(request.dueDate() == null ? LocalDate.now().plusDays(3) : request.dueDate());
        reminder.setPriority(first(request.priority(), "medium"));
        reminder.setSource(first(request.source(), "手动创建"));
        reminder.setRelatedNoteId(request.relatedNoteId());
        return reminderRepo.save(reminder);
    }

    @PutMapping("/reminders/{id}/complete")
    public ResponseEntity<Reminder> completeReminder(@RequestHeader("Authorization") String authHeader,
                                                      @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminder.setCompleted(true);
            return ResponseEntity.ok(reminderRepo.save(reminder));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/reminders/{id}")
    public ResponseEntity<Void> deleteReminder(@RequestHeader("Authorization") String authHeader,
                                                @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminderRepo.delete(reminder);
            return ResponseEntity.noContent().<Void>build();
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/reminders/today")
    public List<Reminder> todayReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        return reminderRepo.findByUserIdAndCompletedFalseAndDueDateLessThanEqualOrderByDueDateAsc(userId, today.plusDays(1));
    }

    @GetMapping("/reminders/priority")
    public List<Reminder> priorityReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        List<String> order = List.of("high", "medium", "low");
        return reminderRepo.findByUserIdAndCompletedFalse(userId).stream()
                .sorted(Comparator.comparingInt(r -> order.indexOf(r.getPriority())))
                .toList();
    }

    @PostMapping("/reminders/parse")
    public Reminder parseReminder(@RequestHeader("Authorization") String authHeader,
                                   @RequestBody Map<String, Object> payload) {
        Reminder reminder = ai.parseReminder(payload);
        reminder.setUserId(auth.getUserId(authHeader));
        return reminderRepo.save(reminder);
    }

    // ── AI ────────────────────────────────────────────────

    @PostMapping("/ai/note/process")
    public Note processNote(@RequestHeader("Authorization") String authHeader,
                             @RequestBody Map<String, Object> payload) {
        Note note = ai.processNote(payload);
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        note.setRagDocumentId(ragService.indexNote(note));
        return noteRepo.save(note);
    }

    @PostMapping("/ai/note/process-image")
    public Note processImage(@RequestHeader("Authorization") String authHeader,
                              @RequestParam("file") MultipartFile file) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        long maxSize = 10 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("图片过大，请使用压缩后的图片（最大10MB）");
        }
        byte[] bytes = file.getBytes();
        String mimeType = file.getContentType() != null ? file.getContentType() : "image/jpeg";
        Note note = ai.processImageNote(bytes, mimeType);
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        note.setRagDocumentId(ragService.indexNote(note));
        return noteRepo.save(note);
    }

    @PostMapping("/ai/note/mindmap")
    public Map<String, String> generateMindMap(@RequestHeader("Authorization") String authHeader,
                                                @RequestBody Map<String, Object> payload) {
        Note note = ai.processNote(payload);
        note.setUserId(auth.getUserId(authHeader));
        noteRepo.save(note);
        return Map.of("mindMap", note.getMindMap());
    }

    @PostMapping("/ai/reminder/parse")
    public Reminder aiParseReminder(@RequestHeader("Authorization") String authHeader,
                                     @RequestBody Map<String, Object> payload) {
        return parseReminder(authHeader, payload);
    }

    @PostMapping("/ai/makeup/generate")
    public Map<String, Object> makeup(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @RequestBody Map<String, Object> payload) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.makeupPackage(userId, String.valueOf(payload.getOrDefault("course", "专业课程")));
    }

    @PostMapping(value = "/ai/makeup/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody makeupStream(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                               @RequestBody Map<String, Object> payload) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.makeupPackageStream(userId, String.valueOf(payload.getOrDefault("course", "专业课程")));
    }

    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @RequestBody Map<String, Object> payload) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.chat(userId, payload);
    }

    @GetMapping("/ai/chat/history")
    public List<Map<String, String>> chatHistory() {
        return List.of();
    }

    @GetMapping("/ai/provider/status")
    public Map<String, Object> aiProviderStatus() {
        return Map.of(
                "provider", "lanxin",
                "config", lanxin.status(),
                "fallback", "local-demo"
        );
    }

    // ── RAG ────────────────────────────────────────────────

    @PostMapping("/rag/documents")
    public Map<String, Object> uploadDocument(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {

        long userId = auth.getUserId(authHeader);

        Runtime rt = Runtime.getRuntime();
        System.out.println("[RAG Upload] file size: " + file.getSize() + " bytes, free memory: "
                + (rt.freeMemory() / 1024 / 1024) + "MB / " + (rt.totalMemory() / 1024 / 1024) + "MB");

        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        String fileType = detectFileType(originalFilename);
        if (!"PDF".equals(fileType) && !"TXT".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 PDF 和 TXT 文件");
        }

        long maxSize = 20 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("文件过大，最大支持 20MB");
        }

        String title = originalFilename != null ? originalFilename : "未命名文档";
        Document doc = ragService.ingestDocument(
                userId, title, originalFilename, fileType, file.getInputStream());

        return Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "fileType", doc.getFileType(),
                "chunkCount", doc.getChunkCount(),
                "status", doc.getStatus(),
                "errorMessage", doc.getErrorMessage() != null ? doc.getErrorMessage() : ""
        );
    }

    @GetMapping("/rag/documents")
    public List<Map<String, Object>> listDocuments(
            @RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        return ragService.listDocuments(userId).stream()
                .map(doc -> Map.<String, Object>of(
                        "id", doc.getId(),
                        "title", doc.getTitle(),
                        "fileType", doc.getFileType(),
                        "fileSize", doc.getFileSize(),
                        "chunkCount", doc.getChunkCount(),
                        "status", doc.getStatus(),
                        "createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : ""
                ))
                .toList();
    }

    @DeleteMapping("/rag/documents/{id}")
    public Map<String, Object> deleteDocument(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        ragService.deleteDocument(userId, id);
        return Map.of("ok", true);
    }

    @PostMapping("/rag/chat")
    public Map<String, Object> ragChat(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody Map<String, Object> payload) {
        long userId = auth.getUserId(authHeader);
        String message = String.valueOf(payload.getOrDefault("message", ""));
        return ai.chatWithRag(userId, message);
    }

    // ── Reports ───────────────────────────────────────────

    @GetMapping("/reports/weekly")
    public Map<String, Object> weeklyReport(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        long noteCount = noteRepo.countByUserId(userId);
        long completed = reminderRepo.countByUserIdAndCompletedTrue(userId);
        return Map.of(
                "title", "本周学习周报",
                "noteCount", noteCount,
                "focusHours", 11.5,
                "completedTasks", completed,
                "highlights", List.of("课堂笔记归档率 92%", "高等数学复习连续 3 天", "DDL 风险已压到 2 项"),
                "message", "这周你把零散内容整理起来了，下一步重点是用自测题检查掌握程度。"
        );
    }

    @GetMapping("/reports/weekly/list")
    public List<Map<String, Object>> weeklyReports(@RequestHeader("Authorization") String authHeader) {
        return List.of(weeklyReport(authHeader));
    }

    @PostMapping("/reports/weekly/generate")
    public Map<String, Object> generateWeeklyReport(@RequestHeader("Authorization") String authHeader) {
        return weeklyReport(authHeader);
    }

    // ── Stats ─────────────────────────────────────────────

    @GetMapping("/stats/dashboard")
    public Map<String, Object> dashboard(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        long noteCount = noteRepo.countByUserId(userId);
        long openCount = reminderRepo.countByUserIdAndCompletedFalse(userId);
        long urgentCount = reminderRepo.countByUserIdAndCompletedFalseAndDueDateLessThanEqual(userId, today.plusDays(3));
        long completedCount = reminderRepo.countByUserIdAndCompletedTrue(userId);
        long studyDays = computeStudyDays(noteRepo.findStudyDatesByUserId(userId));
        return Map.of(
                "noteCount", noteCount,
                "openReminderCount", openCount,
                "urgentReminderCount", urgentCount,
                "completedReminderCount", completedCount,
                "offlineReady", true,
                "studyDays", studyDays
        );
    }

    private long computeStudyDays(List<LocalDate> dates) {
        if (dates.isEmpty()) return 0;
        long streak = 1;
        for (int i = 1; i < dates.size(); i++) {
            if (dates.get(i - 1).minusDays(1).equals(dates.get(i))) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    @GetMapping("/stats/continuity")
    public Map<String, Object> continuity() {
        return Map.of("days", 9, "best", 15);
    }

    // ── helpers ───────────────────────────────────────────

    private Note buildNote(NoteRequest request) {
        Note note = new Note();
        note.setTitle(request.title());
        note.setCourse(request.course());
        note.setRawText(request.rawText());
        note.setSummary(request.summary());
        note.setKeyPoints(request.keyPoints() == null ? List.of() : request.keyPoints());
        note.setFormulas(request.formulas() == null ? List.of() : request.formulas());
        note.setTags(request.tags() == null ? List.of() : request.tags());
        note.setMindMap(request.mindMap());
        note.setOfflineCreated(request.offlineCreated());
        return note;
    }

    private String first(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate;
    }

    private Long getUserIdOrNull(String authHeader) {
        try {
            return auth.getUserId(authHeader);
        } catch (Exception e) {
            return null;
        }
    }

    private String detectFileType(String filename) {
        if (filename == null) return "TXT";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md")) return "TXT";
        return "UNKNOWN";
    }

    public record NoteRequest(
            @NotBlank String title,
            String course,
            String rawText,
            String summary,
            List<String> keyPoints,
            List<String> formulas,
            List<String> tags,
            String mindMap,
            boolean offlineCreated
    ) {}

    public record ReminderRequest(
            @NotBlank String title,
            String course,
            LocalDate dueDate,
            String priority,
            String source,
            Long relatedNoteId
    ) {}
}
