package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.model.Reminder;
import com.vivo.lanxin.campus.repository.NoteRepository;
import com.vivo.lanxin.campus.repository.ReminderRepository;
import com.vivo.lanxin.campus.service.AiMockService;
import com.vivo.lanxin.campus.service.AuthService;
import com.vivo.lanxin.campus.service.LanxinApiClient;
import com.vivo.lanxin.campus.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {
    private final NoteRepository noteRepo;
    private final ReminderRepository reminderRepo;
    private final AuthService auth;
    private final AiMockService ai;
    private final LanxinApiClient lanxin;
    private final RagService ragService;

    public AiController(NoteRepository noteRepo, ReminderRepository reminderRepo, AuthService auth,
                        AiMockService ai, LanxinApiClient lanxin, RagService ragService) {
        this.noteRepo = noteRepo;
        this.reminderRepo = reminderRepo;
        this.auth = auth;
        this.ai = ai;
        this.lanxin = lanxin;
        this.ragService = ragService;
    }

    @PostMapping("/note/process")
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

    @PostMapping("/note/process-image")
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

    @PostMapping("/note/mindmap")
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

    @PostMapping("/reminder/parse")
    public ReminderDto aiParseReminder(@RequestHeader("Authorization") String authHeader,
                                       @Valid @RequestBody TextRequest request) {
        Reminder reminder = ai.parseReminder(Map.of("text", InputSanitizer.clean(request.text(), 2_000)));
        reminder.setUserId(auth.getUserId(authHeader));
        return ReminderDto.from(reminderRepo.save(reminder));
    }

    @PostMapping("/makeup/generate")
    public Map<String, Object> makeup(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @Valid @RequestBody MakeupRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.makeupPackage(userId, ControllerUtils.first(
                InputSanitizer.nullable(request.course(), 80), "专业课程"));
    }

    @PostMapping(value = "/makeup/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody makeupStream(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                               @RequestBody SourceSelectionRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        List<Long> noteIds = ControllerUtils.limitIds(request.noteIds());
        List<Long> documentIds = ControllerUtils.limitIds(request.documentIds());
        return ai.makeupPackageStream(userId, noteIds, documentIds);
    }

    @PostMapping(value = "/makeup/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody makeupChatStream(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody MakeupChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        String makeupContent = InputSanitizer.clean(request.makeupContent(), 50_000);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.makeupChatStream(userId, makeupContent, message);
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @Valid @RequestBody ChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        return ai.chat(userId, Map.of("message", InputSanitizer.clean(request.message(), 4_000)));
    }

    @PostMapping(value = "/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody chatStream(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @Valid @RequestBody ChatRequest request) {
        Long userId = getUserIdOrNull(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatStream(userId, message);
    }

    @GetMapping("/chat/history")
    public List<Map<String, String>> chatHistory() {
        return List.of();
    }

    @GetMapping("/provider/status")
    public Map<String, Object> aiProviderStatus() {
        return Map.of(
                "provider", "lanxin",
                "config", lanxin.status(),
                "fallback", "local-demo"
        );
    }

    // ── Voice / Speech endpoints ───────────────────────────────

    @PostMapping("/speech-to-text")
    public Map<String, Object> speechToText(@RequestHeader("Authorization") String authHeader,
                                            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("音频文件为空");
        }
        long maxSize = 10 * 1024 * 1024; // 10 MB
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("音频文件过大（最大10MB）");
        }
        String contentType = file.getContentType();
        if (contentType == null) contentType = "audio/webm";
        if (!contentType.startsWith("audio/") && !contentType.equals("video/webm")) {
            throw new IllegalArgumentException("不支持的文件类型：" + contentType + "，请上传音频文件");
        }
        byte[] audioBytes = file.getBytes();
        String text = ai.transcribeAudio(audioBytes, contentType);
        long durationSec = file.getSize() / 8000; // rough estimate: ~8 KB/s for compressed audio
        return Map.of("text", (Object) text, "duration", (Object) durationSec);
    }

    @PostMapping("/text-to-speech")
    public org.springframework.http.ResponseEntity<byte[]> textToSpeech(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody VoiceChatRequest request) {
        String text = InputSanitizer.clean(request.text(), 2000);
        if (text.isBlank()) {
            throw new IllegalArgumentException("文字内容为空");
        }
        byte[] audioBytes = ai.synthesizeSpeech(text);
        if (audioBytes == null || audioBytes.length == 0) {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(audioBytes.length))
                .body(audioBytes);
    }

    @PostMapping("/voice-chat")
    public Map<String, Object> voiceChat(@RequestHeader("Authorization") String authHeader,
                                         @RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "voiceResponse", defaultValue = "true") boolean voiceResponse)
            throws java.io.IOException {
        Long userId = getUserIdOrNull(authHeader);
        // Step 1: STT
        if (file.isEmpty()) {
            throw new IllegalArgumentException("音频文件为空");
        }
        String mimeType = file.getContentType() != null ? file.getContentType() : "audio/webm";
        String userText = ai.transcribeAudio(file.getBytes(), mimeType);
        if (userText.isBlank()) {
            return Map.of("userText", "", "aiText", "抱歉，没有听清，请再说一遍。", "audioBase64", "");
        }
        // Step 2: Chat
        Map<String, Object> chatResult = ai.chat(userId, Map.of("message", userText));
        String aiText = (String) chatResult.getOrDefault("answer", "抱歉，AI 服务暂时不可用。");
        // Step 3: TTS (optional)
        String audioBase64 = "";
        if (voiceResponse) {
            byte[] ttsAudio = ai.synthesizeSpeech(aiText);
            if (ttsAudio != null && ttsAudio.length > 0) {
                audioBase64 = java.util.Base64.getEncoder().encodeToString(ttsAudio);
            }
        }
        return Map.of("userText", (Object) userText,
                      "aiText", (Object) aiText,
                      "audioBase64", (Object) audioBase64);
    }

    @PostMapping(value = "/voice-chat/stream", produces = "text/plain;charset=UTF-8")
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody voiceChatStream(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        Long userId = getUserIdOrNull(authHeader);
        // Step 1: STT first
        if (file.isEmpty()) {
            throw new IllegalArgumentException("音频文件为空");
        }
        String mimeType = file.getContentType() != null ? file.getContentType() : "audio/webm";
        String userText = ai.transcribeAudio(file.getBytes(), mimeType);
        if (userText.isBlank()) {
            String fallback = "抱歉，没有听清你说的话，可以再说一遍吗？";
            return outputStream -> {
                try {
                    outputStream.write(("data: " + fallback + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (java.io.IOException ignored) {}
            };
        }
        // Step 2: Stream chat response (like chatStream)
        return ai.chatStream(userId, userText);
    }

    private Long getUserIdOrNull(String authHeader) {
        try {
            return auth.getUserId(authHeader);
        } catch (Exception e) {
            return null;
        }
    }
}
