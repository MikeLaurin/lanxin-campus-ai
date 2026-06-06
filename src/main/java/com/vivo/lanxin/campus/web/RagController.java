package com.vivo.lanxin.campus.web;

import com.vivo.lanxin.campus.model.Document;
import com.vivo.lanxin.campus.service.AiMockService;
import com.vivo.lanxin.campus.service.AuthService;
import com.vivo.lanxin.campus.service.RagService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
@RequestMapping("/api/v1/rag")
public class RagController {
    private final AuthService auth;
    private final AiMockService ai;
    private final RagService ragService;

    public RagController(AuthService auth, AiMockService ai, RagService ragService) {
        this.auth = auth;
        this.ai = ai;
        this.ragService = ragService;
    }

    @PostMapping("/documents/extract")
    public Map<String, Object> extractDocument(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        auth.getUserId(authHeader);
        validateDocumentFile(file);

        String originalFilename = InputSanitizer.clean(file.getOriginalFilename(), 180);
        String fileType = ControllerUtils.detectFileType(originalFilename);
        String fullText = ragService.extractText(originalFilename, fileType, file.getInputStream());
        String title = originalFilename != null && !originalFilename.isBlank() ? originalFilename : "未命名文档";

        return Map.of(
                "title", title,
                "fileType", fileType,
                "fileSize", file.getSize(),
                "fullText", fullText
        );
    }

    @PostMapping("/documents/ingest-text")
    public Map<String, Object> ingestText(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody IngestTextRequest request) {
        long userId = auth.getUserId(authHeader);
        Document doc = ragService.ingestText(userId,
                InputSanitizer.clean(request.title(), 180),
                InputSanitizer.clean(request.originalFilename(), 180),
                request.fileType(),
                request.fullText());
        return documentResponse(doc);
    }

    @PostMapping("/documents")
    public Map<String, Object> uploadDocument(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam("file") MultipartFile file) throws java.io.IOException {
        long userId = auth.getUserId(authHeader);

        Runtime rt = Runtime.getRuntime();
        System.out.println("[RAG Upload] file size: " + file.getSize() + " bytes, free memory: "
                + (rt.freeMemory() / 1024 / 1024) + "MB / " + (rt.totalMemory() / 1024 / 1024) + "MB");

        validateDocumentFile(file);

        String originalFilename = InputSanitizer.clean(file.getOriginalFilename(), 180);
        String fileType = ControllerUtils.detectFileType(originalFilename);
        String title = originalFilename != null && !originalFilename.isBlank() ? originalFilename : "未命名文档";
        Document doc = ragService.ingestDocument(userId, title, originalFilename, fileType, file.getInputStream());
        return documentResponse(doc);
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> listDocuments(@RequestHeader("Authorization") String authHeader) {
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

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> deleteDocument(@RequestHeader("Authorization") String authHeader,
                                              @PathVariable long id) {
        long userId = auth.getUserId(authHeader);
        ragService.deleteDocument(userId, id);
        return Map.of("ok", true);
    }

    @PostMapping("/chat")
    public Map<String, Object> ragChat(@RequestHeader("Authorization") String authHeader,
                                       @Valid @RequestBody ChatRequest request) {
        long userId = auth.getUserId(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatWithRag(userId, message);
    }

    @PostMapping(value = "/chat/stream", produces = "text/plain;charset=UTF-8")
    public StreamingResponseBody ragChatStream(@RequestHeader("Authorization") String authHeader,
                                               @Valid @RequestBody ChatRequest request) {
        long userId = auth.getUserId(authHeader);
        String message = InputSanitizer.clean(request.message(), 4_000);
        return ai.chatWithRagStream(userId, message);
    }

    private void validateDocumentFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        String originalFilename = InputSanitizer.clean(file.getOriginalFilename(), 180);
        String fileType = ControllerUtils.detectFileType(originalFilename);
        if (!"PDF".equals(fileType) && !"TXT".equals(fileType) && !"DOCX".equals(fileType)) {
            throw new IllegalArgumentException("仅支持 PDF、TXT 和 DOCX 文件");
        }
        long maxSize = 20 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new IllegalArgumentException("文件过大，最大支持 20MB");
        }
    }

    private Map<String, Object> documentResponse(Document doc) {
        return Map.of(
                "id", doc.getId(),
                "title", doc.getTitle(),
                "fileType", doc.getFileType(),
                "chunkCount", doc.getChunkCount(),
                "status", doc.getStatus(),
                "errorMessage", doc.getErrorMessage() != null ? doc.getErrorMessage() : ""
        );
    }
}
