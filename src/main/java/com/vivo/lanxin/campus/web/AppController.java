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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    @PostMapping("/user/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(
                request.username(),
                request.password(),
                request.name(),
                request.school(),
                request.major(),
                request.grade()
        );
    }

    @PostMapping("/user/refresh")
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
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
    public List<NoteDto> notes(@RequestHeader("Authorization") String authHeader,
                               @RequestParam(required = false) @Size(max = 80) String keyword,
                               @RequestParam(required = false) @Size(max = 200) String folder) {
        long userId = auth.getUserId(authHeader);
        keyword = InputSanitizer.clean(keyword, 80);
        folder = InputSanitizer.clean(folder, 200);
        if (keyword != null && !keyword.isBlank()) {
            return noteRepo.searchByUser(userId, keyword).stream().map(NoteDto::from).toList();
        }
        if (folder != null && !folder.isBlank()) {
            if (folder.endsWith("/")) {
                return noteRepo.findByUserIdAndFolderPathPrefix(userId, folder).stream().map(NoteDto::from).toList();
            }
            return noteRepo.findByUserIdAndFolderPath(userId, folder).stream().map(NoteDto::from).toList();
        }
        return noteRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(NoteDto::from).toList();
    }

    @GetMapping("/notes/folders")
    public List<Map<String, Object>> noteFolders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        List<String> paths = noteRepo.findDistinctFolderPathsByUserId(userId);
        return buildFolderTree(paths);
    }

    @PostMapping("/notes")
    public NoteDto createNote(@RequestHeader("Authorization") String authHeader,
                              @Valid @RequestBody NoteRequest request) {
        Note note = buildNote(request);
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        try {
            note.setRagDocumentId(ragService.indexNote(note));
            note = noteRepo.save(note);
        } catch (Exception e) {
            // RAG indexing failed but note is already saved — log and continue
            System.err.println("[RAG] Failed to index note " + note.getId() + ": " + e.getMessage());
        }
        return NoteDto.from(note);
    }

    @GetMapping("/notes/{id}")
    public ResponseEntity<NoteDto> note(@RequestHeader("Authorization") String authHeader,
                                         @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id)
                .filter(n -> n.getUserId() == userId)
                .map(note -> ResponseEntity.ok(NoteDto.from(note)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/notes/{id}")
    public ResponseEntity<NoteDto> updateNote(@RequestHeader("Authorization") String authHeader,
                                               @PathVariable long id, @Valid @RequestBody NoteRequest request) {
        long userId = auth.getUserId(authHeader);
        return noteRepo.findById(id).filter(n -> n.getUserId() == userId).map(note -> {
            note.setTitle(first(InputSanitizer.nullable(request.title(), 120), note.getTitle()));
            note.setCourse(first(InputSanitizer.nullable(request.course(), 80), note.getCourse()));
            note.setFolderPath(first(InputSanitizer.nullable(request.folderPath(), 200), note.getFolderPath()));
            note.setRawText(first(InputSanitizer.nullable(request.rawText(), 20_000), note.getRawText()));
            note.setSummary(first(InputSanitizer.nullable(request.summary(), 4_000), note.getSummary()));
            if (request.keyPoints() != null) note.setKeyPoints(InputSanitizer.cleanList(request.keyPoints(), 200, 20));
            if (request.formulas() != null) note.setFormulas(InputSanitizer.cleanList(request.formulas(), 200, 20));
            if (request.tags() != null) note.setTags(InputSanitizer.cleanList(request.tags(), 40, 12));
            note.setMindMap(first(InputSanitizer.nullable(request.mindMap(), 6_000), note.getMindMap()));
            note = noteRepo.save(note);
            ragService.reindexNote(note);
            return ResponseEntity.ok(NoteDto.from(noteRepo.save(note)));
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
        List<NoteDto> saved = notes.stream().limit(100).map(req -> {
            Note note = buildNote(req);
            note.setUserId(userId);
            note = noteRepo.save(note);
            try {
                note.setRagDocumentId(ragService.indexNote(note));
                note = noteRepo.save(note);
            } catch (Exception e) {
                System.err.println("[RAG] Failed to index batch note " + note.getId() + ": " + e.getMessage());
            }
            return NoteDto.from(note);
        }).toList();
        return Map.of("synced", saved.size(), "items", saved);
    }

    // ── Reminders ─────────────────────────────────────────

    @GetMapping("/reminders")
    public List<ReminderDto> reminders(@RequestHeader("Authorization") String authHeader,
                                       @RequestParam(defaultValue = "false") boolean includeCompleted) {
        long userId = auth.getUserId(authHeader);
        if (includeCompleted) {
            return reminderRepo.findByUserIdOrderByDueDateAsc(userId).stream().map(ReminderDto::from).toList();
        }
        return reminderRepo.findByUserIdAndCompletedFalse(userId).stream().map(ReminderDto::from).toList();
    }

    @PostMapping("/reminders")
    public ReminderDto createReminder(@RequestHeader("Authorization") String authHeader,
                                      @Valid @RequestBody ReminderRequest request) {
        Reminder reminder = new Reminder();
        reminder.setUserId(auth.getUserId(authHeader));
        reminder.setTitle(InputSanitizer.clean(request.title(), 120));
        reminder.setCourse(first(InputSanitizer.nullable(request.course(), 80), "专业课程"));
        reminder.setDueDate(request.dueDate() == null ? LocalDate.now().plusDays(3) : request.dueDate());
        reminder.setPriority(first(InputSanitizer.nullable(request.priority(), 20), "medium"));
        reminder.setSource(first(InputSanitizer.nullable(request.source(), 200), "手动创建"));
        reminder.setRelatedNoteId(request.relatedNoteId());
        return ReminderDto.from(reminderRepo.save(reminder));
    }

    @PutMapping("/reminders/{id}/complete")
    public ResponseEntity<ReminderDto> completeReminder(@RequestHeader("Authorization") String authHeader,
                                                        @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        return reminderRepo.findById(id).filter(r -> r.getUserId() == userId).map(reminder -> {
            reminder.setCompleted(true);
            return ResponseEntity.ok(ReminderDto.from(reminderRepo.save(reminder)));
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
    public List<ReminderDto> todayReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        LocalDate today = LocalDate.now();
        return reminderRepo.findByUserIdAndCompletedFalseAndDueDateLessThanEqualOrderByDueDateAsc(userId, today.plusDays(3))
                .stream().map(ReminderDto::from).toList();
    }

    @GetMapping("/reminders/priority")
    public List<ReminderDto> priorityReminders(@RequestHeader("Authorization") String authHeader) {
        long userId = auth.getUserId(authHeader);
        List<String> order = List.of("high", "medium", "low");
        return reminderRepo.findByUserIdAndCompletedFalse(userId).stream()
                .sorted(Comparator.comparingInt(r -> order.indexOf(r.getPriority())))
                .map(ReminderDto::from)
                .toList();
    }

    @PostMapping("/reminders/parse")
    public ReminderDto parseReminder(@RequestHeader("Authorization") String authHeader,
                                     @Valid @RequestBody TextRequest request) {
        Reminder reminder = ai.parseReminder(Map.of("text", InputSanitizer.clean(request.text(), 2_000)));
        reminder.setUserId(auth.getUserId(authHeader));
        return ReminderDto.from(reminderRepo.save(reminder));
    }

    // ── AI ────────────────────────────────────────────────

    @PostMapping("/ai/note/process")
    public NoteDto processNote(@RequestHeader("Authorization") String authHeader,
                               @Valid @RequestBody NoteProcessRequest request) {
        Note note = ai.processNote(Map.of(
                "rawText", InputSanitizer.clean(request.rawText(), 20_000),
                "offline", request.offline()
        ));
        note.setUserId(auth.getUserId(authHeader));
        note = noteRepo.save(note);
        try {
            note.setRagDocumentId(ragService.indexNote(note));
            note = noteRepo.save(note);
        } catch (Exception e) {
            System.err.println("[RAG] Failed to index processed note " + note.getId() + ": " + e.getMessage());
        }
        return NoteDto.from(note);
    }

    @PostMapping("/ai/note/process-image")
    public NoteDto processImage(@RequestHeader("Authorization") String authHeader,
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
        try {
            note.setRagDocumentId(ragService.indexNote(note));
            note = noteRepo.save(note);
        } catch (Exception e) {
            System.err.println("[RAG] Failed to index image note " + note.getId() + ": " + e.getMessage());
        }
        return NoteDto.from(note);
    }

    @PostMapping("/ai/note/mindmap")
    public Map<String, String> generateMindMap(@RequestHeader("Authorization") String authHeader,
                                                @Valid @RequestBody NoteProcessRequest request) {
        Note note = ai.processNote(Map.of(
                "rawText", InputSanitizer.clean(request.rawText(), 20_000),
                "offline", request.offline()
        ));
        note.setUserId(auth.getUserId(authHeader));
        noteRepo.save(note);
        return Map.of("mindMap", note.getMindMap());
    }

    @PostMapping("/ai/reminder/parse")
    public ReminderDto aiParseReminder(@RequestHeader("Authorization") String authHeader,
                                       @Valid @RequestBody TextRequest request) {
        return parseReminder(authHeader, request);
    }

    @PostMapping("/ai/makeup/generate")
    public Map<String, Object> makeup(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @Valid @RequestBody MakeupRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.makeupPackage(userId, first(InputSanitizer.nullable(request.course(), 80), "专业课程"));
    }

    @PostMapping(value = "/ai/makeup/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody makeupStream(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                               @RequestBody SourceSelectionRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        List<Long> noteIds = limitIds(request.noteIds());
        List<Long> documentIds = limitIds(request.documentIds());
        return ai.makeupPackageStream(userId, noteIds, documentIds);
    }

    @PostMapping(value = "/ai/makeup/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody makeupChatStream(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody MakeupChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        String makeupContent = InputSanitizer.clean(request.makeupContent(), 50_000);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.makeupChatStream(userId, makeupContent, message);
    }

    @PostMapping("/ai/chat")
    public Map<String, Object> chat(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @Valid @RequestBody ChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.chat(userId, Map.of("message", InputSanitizer.clean(request.message(), 4_000)));
    }

    @PostMapping(value = "/ai/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody chatStream(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @Valid @RequestBody ChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatStream(userId, message);
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

    @PostMapping("/rag/documents/extract")
    public Map<String, Object> extractDocument(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {

        auth.getUserId(authHeader);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }

        String originalFilename = InputSanitizer.clean(file.getOriginalFilename(), 180);
        String fileType = detectFileType(originalFilename);
        if (!"PDF".equals(fileType) && !"TXT".equals(fileType) && !"DOCX".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 PDF、TXT 和 DOCX 文件");
        }

        long maxSize = 20 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("文件过大，最大支持 20MB");
        }

        String fullText = ragService.extractText(originalFilename, fileType, file.getInputStream());
        String title = originalFilename != null && !originalFilename.isBlank() ? originalFilename : "未命名文档";

        return Map.of(
                "title", title,
                "fileType", fileType,
                "fileSize", file.getSize(),
                "fullText", fullText
        );
    }

    public record IngestTextRequest(
            @NotBlank String title,
            @NotBlank String originalFilename,
            @NotBlank String fileType,
            @NotBlank String fullText
    ) {}

    @PostMapping("/rag/documents/ingest-text")
    public Map<String, Object> ingestText(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody IngestTextRequest request) {

        long userId = auth.getUserId(authHeader);
        Document doc = ragService.ingestText(userId,
                InputSanitizer.clean(request.title(), 180),
                InputSanitizer.clean(request.originalFilename(), 180),
                request.fileType(),
                request.fullText());

        return Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "fileType", doc.getFileType(),
                "chunkCount", doc.getChunkCount(),
                "status", doc.getStatus(),
                "errorMessage", doc.getErrorMessage() != null ? doc.getErrorMessage() : ""
        );
    }

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

        String originalFilename = InputSanitizer.clean(file.getOriginalFilename(), 180);
        String fileType = detectFileType(originalFilename);
        if (!"PDF".equals(fileType) && !"TXT".equals(fileType) && !"DOCX".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 PDF、TXT 和 DOCX 文件");
        }

        long maxSize = 20 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("文件过大，最大支持 20MB");
        }

        String title = originalFilename != null && !originalFilename.isBlank() ? originalFilename : "未命名文档";
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
            @Valid @RequestBody ChatRequest request) {
        long userId = auth.getUserId(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatWithRag(userId, message);
    }

    @PostMapping(value = "/rag/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody ragChatStream(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody ChatRequest request) {
        long userId = auth.getUserId(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatWithRagStream(userId, message);
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

    private List<Long> limitIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        return ids.stream().filter(id -> id != null && id > 0).limit(50).toList();
    }

    private String detectFileType(String filename) {
        if (filename == null) return "TXT";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".txt")) return "TXT";
        if (lower.endsWith(".md")) return "TXT";
        if (lower.endsWith(".docx")) return "DOCX";
        return "UNKNOWN";
    }

    private List<Map<String, Object>> buildFolderTree(List<String> paths) {
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
                    java.util.List<Map<String, Object>> siblings = (java.util.List<Map<String, Object>>) nodeMap.get(prefix).get("children");
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

    public record LoginRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9_\\-]{3,50}$") String username,
            @NotBlank @Size(min = 6, max = 72) String password
    ) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9_\\-]{3,50}$") String username,
            @NotBlank @Size(min = 6, max = 72) String password,
            @Size(max = 50) String name,
            @Size(max = 100) String school,
            @Size(max = 50) String major,
            @Size(max = 20) String grade
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record NoteProcessRequest(
            @Size(max = 20_000) String rawText,
            boolean offline
    ) {}

    public record TextRequest(@NotBlank @Size(max = 2_000) String text) {}

    public record ChatRequest(@NotBlank @Size(max = 4_000) String message) {}

    public record MakeupRequest(@Size(max = 80) String course) {}

    public record SourceSelectionRequest(List<Long> noteIds, List<Long> documentIds) {}

    public record MakeupChatRequest(
            @NotBlank @Size(max = 50_000) String makeupContent,
            @NotBlank @Size(max = 4_000) String message
    ) {}

    public record NoteRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 80) String course,
            @Size(max = 200) String folderPath,
            @Size(max = 20_000) String rawText,
            @Size(max = 4_000) String summary,
            List<String> keyPoints,
            List<String> formulas,
            List<String> tags,
            @Size(max = 6_000) String mindMap,
            boolean offlineCreated
    ) {}

    public record ReminderRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 80) String course,
            LocalDate dueDate,
            @Pattern(regexp = "high|medium|low") String priority,
            @Size(max = 200) String source,
            Long relatedNoteId
    ) {}
}
