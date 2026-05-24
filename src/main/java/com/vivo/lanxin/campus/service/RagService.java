package com.vivo.lanxin.campus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivo.lanxin.campus.model.Document;
import com.vivo.lanxin.campus.model.DocumentChunk;
import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.repository.DocumentChunkRepository;
import com.vivo.lanxin.campus.repository.DocumentRepository;
import com.vivo.lanxin.campus.repository.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;
import java.util.stream.Collectors;

@Service
public class RagService {
    private static final int CHUNK_SIZE_CHARS = 1000;
    private static final int CHUNK_OVERLAP_CHARS = 200;
    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int EMBEDDING_BATCH_SIZE = 5;

    private final DocumentRepository documentRepo;
    private final DocumentChunkRepository chunkRepo;
    private final NoteRepository noteRepo;
    private final LanxinApiClient lanxin;
    private final ObjectMapper objectMapper;

    public RagService(DocumentRepository documentRepo,
                      DocumentChunkRepository chunkRepo,
                      NoteRepository noteRepo,
                      LanxinApiClient lanxin,
                      ObjectMapper objectMapper) {
        this.documentRepo = documentRepo;
        this.chunkRepo = chunkRepo;
        this.noteRepo = noteRepo;
        this.lanxin = lanxin;
        this.objectMapper = objectMapper;
    }

    // ── Text Extraction ─────────────────────────────────────

    public String extractPdfText(InputStream pdfStream) throws IOException {
        Path tempFile = Files.createTempFile("pdf-upload-", ".pdf");
        try {
            Files.copy(pdfStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            ProcessBuilder pb = new ProcessBuilder(
                    "pdftotext", "-layout", "-nopgbrk",
                    tempFile.toAbsolutePath().toString(), "-"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (Scanner scanner = new Scanner(process.getInputStream(), StandardCharsets.UTF_8)) {
                scanner.useDelimiter("\\A");
                String text = scanner.hasNext() ? scanner.next() : "";
                process.waitFor();
                return text.trim();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PDF text extraction interrupted", e);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public String extractTextFile(InputStream stream) throws IOException {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }

    // ── Chunking ────────────────────────────────────────────

    public List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> paragraphs = splitByParagraphs(text);
        List<String> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            if (para.length() <= CHUNK_SIZE_CHARS) {
                if (!para.isBlank()) chunks.add(para.trim());
            } else {
                chunks.addAll(splitBySize(para, CHUNK_SIZE_CHARS, CHUNK_OVERLAP_CHARS));
            }
        }

        return mergeSmallChunks(chunks);
    }

    private List<String> splitByParagraphs(String text) {
        return Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private List<String> splitBySize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            if (end >= text.length()) {
                String chunk = text.substring(start).trim();
                if (!chunk.isEmpty()) {
                    chunks.add(chunk);
                }
                break;
            }

            int breakPoint = findBestBreakPoint(text, start, end);
            if (breakPoint > start) {
                end = breakPoint;
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end - overlap;
            if (start <= 0) start = end;
        }
        return chunks;
    }

    private int findBestBreakPoint(String text, int start, int end) {
        int searchStart = Math.max(start + CHUNK_SIZE_CHARS / 2, start);
        for (int i = end; i > searchStart; i--) {
            char c = text.charAt(i - 1);
            if ((c == '.' || c == '。' || c == '！' || c == '？' || c == '\n')
                    && i < text.length() && Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return end;
    }

    private List<String> mergeSmallChunks(List<String> chunks) {
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String chunk : chunks) {
            if (buffer.length() + chunk.length() <= CHUNK_SIZE_CHARS) {
                if (buffer.length() > 0) buffer.append("\n");
                buffer.append(chunk);
            } else {
                if (buffer.length() > 0) {
                    merged.add(buffer.toString());
                    buffer.setLength(0);
                }
                if (chunk.length() <= CHUNK_SIZE_CHARS) {
                    buffer.append(chunk);
                } else {
                    merged.add(chunk);
                }
            }
        }
        if (buffer.length() > 0) merged.add(buffer.toString());
        return merged;
    }

    // ── Embedding ───────────────────────────────────────────

    private float[] deserializeEmbedding(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize embedding", e);
        }
    }

    // ── Ingestion Pipeline ──────────────────────────────────

    public Document ingestDocument(long userId, String title, String originalFilename,
                                    String fileType, InputStream fileStream) {
        try {
            System.out.println("[RagService] Starting text extraction for: " + originalFilename);
            String fullText;
            if ("PDF".equalsIgnoreCase(fileType)) {
                fullText = extractPdfText(fileStream);
            } else {
                fullText = extractTextFile(fileStream);
            }
            System.out.println("[RagService] Text extracted, length: " + fullText.length());

            if (fullText.isBlank()) {
                throw new IOException("文档内容为空或无法提取文字");
            }

            System.out.println("[RagService] Chunking text...");
            List<String> chunks = chunkText(fullText);
            fullText = null;
            System.out.println("[RagService] Chunk count: " + chunks.size());

            if (chunks.isEmpty()) {
                throw new IOException("文档分块后无有效内容");
            }

            Document doc = new Document();
            doc.setUserId(userId);
            doc.setTitle(title);
            doc.setOriginalFilename(originalFilename);
            doc.setFileType(fileType.toUpperCase());
            doc.setStatus("PROCESSING");
            doc.setFileSize(0);
            documentRepo.save(doc);

            int totalChunks = chunks.size();
            for (int i = 0; i < totalChunks; i += EMBEDDING_BATCH_SIZE) {
                int batchEnd = Math.min(i + EMBEDDING_BATCH_SIZE, totalChunks);
                List<DocumentChunk> batch = new ArrayList<>(batchEnd - i);

                for (int j = i; j < batchEnd; j++) {
                    String chunkText = chunks.get(j);
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setUserId(userId);
                    chunk.setDocumentId(doc.getId());
                    chunk.setChunkIndex(j);
                    chunk.setContent(chunkText);
                    chunk.setCharCount(chunkText.length());
                    batch.add(chunk);
                }

                System.out.println("[RagService] Saving chunks " + (i + 1) + "-" + batchEnd + " / " + totalChunks);
                chunkRepo.saveAll(batch);
                batch.clear();
            }

            doc.setChunkCount(totalChunks);
            doc.setStatus("READY");
            documentRepo.save(doc);

            return doc;

        } catch (Exception e) {
            System.err.println("[RagService] Ingestion failed: " + e.getMessage());
            try {
                Document doc = new Document();
                doc.setUserId(userId);
                doc.setTitle(title);
                doc.setOriginalFilename(originalFilename);
                doc.setFileType(fileType.toUpperCase());
                doc.setStatus("FAILED");
                doc.setErrorMessage(e.getMessage());
                return documentRepo.save(doc);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to save failed document", e);
            }
        }
    }

    // ── Retrieval ───────────────────────────────────────────

    public List<RetrievedChunk> retrieveRelevantChunks(long userId, String query) {
        Optional<float[]> queryEmbedding = lanxin.embedding(query);
        if (queryEmbedding.isPresent()) {
            List<RetrievedChunk> vectorResults = vectorSearch(userId, queryEmbedding.get());
            if (!vectorResults.isEmpty()) {
                return vectorResults;
            }
        }
        return keywordSearchChunks(userId, query);
    }

    private List<RetrievedChunk> vectorSearch(long userId, float[] queryEmbedding) {
        List<DocumentChunk> allChunks = chunkRepo.findByUserId(userId);
        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (DocumentChunk chunk : allChunks) {
            if (chunk.getEmbedding() == null || chunk.getEmbedding().isBlank()) {
                continue;
            }
            float[] chunkEmbedding = deserializeEmbedding(chunk.getEmbedding());
            double similarity = cosineSimilarity(queryEmbedding, chunkEmbedding);
            if (similarity >= SIMILARITY_THRESHOLD) {
                scoredChunks.add(new ScoredChunk(chunk, similarity));
            }
        }
        return scoredChunks.stream()
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(TOP_K)
                .map(sc -> toRetrievedChunk(sc.chunk, sc.similarity))
                .collect(Collectors.toList());
    }

    private List<RetrievedChunk> keywordSearchChunks(long userId, String query) {
        List<DocumentChunk> allChunks = chunkRepo.findByUserId(userId);
        if (allChunks.isEmpty()) return List.of();

        String[] keywords = query.toLowerCase().split("[\\s，。！？,.!?]+");
        return allChunks.stream()
                .filter(chunk -> chunk.getContent() != null && matchesChunkKeywords(chunk.getContent(), keywords))
                .sorted((a, b) -> Integer.compare(b.getCharCount(), a.getCharCount()))
                .limit(TOP_K)
                .map(chunk -> toRetrievedChunk(chunk, 0.0))
                .collect(Collectors.toList());
    }

    private boolean matchesChunkKeywords(String content, String[] keywords) {
        String lower = content.toLowerCase();
        for (String kw : keywords) {
            if (kw.length() >= 2 && lower.contains(kw)) return true;
        }
        return false;
    }

    private RetrievedChunk toRetrievedChunk(DocumentChunk chunk, double similarity) {
        Document doc = documentRepo.findById(chunk.getDocumentId()).orElse(null);
        String docTitle = doc != null ? doc.getTitle() : "Unknown";
        return new RetrievedChunk(chunk.getContent(), docTitle, similarity, chunk.getChunkIndex());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimensions mismatch: "
                    + a.length + " vs " + b.length);
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ── Document Management ─────────────────────────────────

    public List<Document> listDocuments(long userId) {
        return documentRepo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void deleteDocument(long userId, long documentId) {
        documentRepo.findById(documentId).ifPresent(doc -> {
            if (doc.getUserId() == userId) {
                chunkRepo.deleteByDocumentId(documentId);
                documentRepo.delete(doc);
            }
        });
    }

    public List<RetrievedChunk> retrieveByKeyword(long userId, String query) {
        List<Note> allNotes = noteRepo.findByUserIdOrderByUpdatedAtDesc(userId);
        if (allNotes.isEmpty()) return List.of();

        String[] keywords = query.toLowerCase().split("[\\s，。！？,.!?]+");
        List<Note> matched = allNotes.stream()
                .filter(n -> matchesAnyKeyword(n, keywords))
                .limit(TOP_K)
                .toList();

        return matched.stream().map(note -> {
            String content = note.getSummary() != null && !note.getSummary().isBlank()
                    ? note.getSummary()
                    : (note.getRawText() != null ? note.getRawText().substring(0, Math.min(300, note.getRawText().length())) : "");
            return new RetrievedChunk(content, note.getTitle(), 0.0, 0);
        }).toList();
    }

    private boolean matchesAnyKeyword(Note note, String[] keywords) {
        String haystack = (note.getTitle() + " " + note.getCourse() + " "
                + note.getSummary() + " " + note.getRawText()).toLowerCase();
        for (String kw : keywords) {
            if (kw.length() >= 2 && haystack.contains(kw)) return true;
        }
        return false;
    }

    @Transactional
    public void deleteNoteDocument(long documentId) {
        chunkRepo.deleteByDocumentId(documentId);
        documentRepo.deleteById(documentId);
    }

    public long indexNote(com.vivo.lanxin.campus.model.Note note) {
        String content = buildNoteContent(note);
        Document doc = new Document();
        doc.setUserId(note.getUserId());
        doc.setTitle(note.getTitle());
        doc.setOriginalFilename("note_" + note.getId());
        doc.setFileType("NOTE");
        doc.setStatus("PROCESSING");
        doc.setFullText(content);
        doc.setFileSize(content.length());
        documentRepo.save(doc);

        List<String> chunks = chunkText(content);
        List<DocumentChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            DocumentChunk chunk = new DocumentChunk();
            chunk.setUserId(note.getUserId());
            chunk.setDocumentId(doc.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setCharCount(chunkText.length());
            chunkEntities.add(chunk);
        }
        chunkRepo.saveAll(chunkEntities);

        doc.setChunkCount(chunks.size());
        doc.setStatus("READY");
        documentRepo.save(doc);
        return doc.getId();
    }

    public void reindexNote(com.vivo.lanxin.campus.model.Note note) {
        if (note.getRagDocumentId() != null) {
            deleteNoteDocument(note.getRagDocumentId());
        }
        long docId = indexNote(note);
        note.setRagDocumentId(docId);
    }

    private String buildNoteContent(com.vivo.lanxin.campus.model.Note note) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(note.getTitle()).append("\n");
        if (note.getCourse() != null && !note.getCourse().isBlank()) {
            sb.append("课程：").append(note.getCourse()).append("\n");
        }
        if (note.getSummary() != null && !note.getSummary().isBlank()) {
            sb.append("摘要：").append(note.getSummary()).append("\n");
        }
        if (note.getKeyPoints() != null && !note.getKeyPoints().isEmpty()) {
            sb.append("要点：").append(String.join("；", note.getKeyPoints())).append("\n");
        }
        if (note.getRawText() != null && !note.getRawText().isBlank()) {
            sb.append("原始内容：").append(note.getRawText());
        }
        return sb.toString();
    }

    // ── Inner Classes ───────────────────────────────────────

    private static class ScoredChunk {
        final DocumentChunk chunk;
        final double similarity;
        ScoredChunk(DocumentChunk chunk, double similarity) {
            this.chunk = chunk;
            this.similarity = similarity;
        }
    }

    public static class RetrievedChunk {
        private final String content;
        private final String documentTitle;
        private final double similarity;
        private final int chunkIndex;

        public RetrievedChunk(String content, String documentTitle, double similarity, int chunkIndex) {
            this.content = content;
            this.documentTitle = documentTitle;
            this.similarity = similarity;
            this.chunkIndex = chunkIndex;
        }

        public String getContent() { return content; }
        public String getDocumentTitle() { return documentTitle; }
        public double getSimilarity() { return similarity; }
        public int getChunkIndex() { return chunkIndex; }
    }
}
