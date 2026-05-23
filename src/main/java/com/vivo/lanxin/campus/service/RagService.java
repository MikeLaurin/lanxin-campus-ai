package com.vivo.lanxin.campus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivo.lanxin.campus.model.Document;
import com.vivo.lanxin.campus.model.DocumentChunk;
import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.repository.DocumentChunkRepository;
import com.vivo.lanxin.campus.repository.DocumentRepository;
import com.vivo.lanxin.campus.repository.NoteRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class RagService {
    private static final int CHUNK_SIZE_CHARS = 1000;
    private static final int CHUNK_OVERLAP_CHARS = 200;
    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final int MAX_TEXT_LENGTH = 200_000;

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
        byte[] bytes = pdfStream.readAllBytes();
        try (var pdfDocument = Loader.loadPDF(bytes)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(pdfDocument);
            return text != null ? text.trim() : "";
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

            if (end < text.length()) {
                int breakPoint = findBestBreakPoint(text, start, end);
                if (breakPoint > start) {
                    end = breakPoint;
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            start = end - overlap;
            if (start >= text.length()) break;
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

    private Optional<String> generateEmbedding(String text) {
        return lanxin.embedding(text).map(this::serializeEmbedding);
    }

    private String serializeEmbedding(float[] embedding) {
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize embedding", e);
        }
    }

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
        Document doc = new Document();
        doc.setUserId(userId);
        doc.setTitle(title);
        doc.setOriginalFilename(originalFilename);
        doc.setFileType(fileType.toUpperCase());
        doc.setStatus("PROCESSING");
        documentRepo.save(doc);

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

            if (fullText.length() > MAX_TEXT_LENGTH) {
                fullText = fullText.substring(0, MAX_TEXT_LENGTH);
            }

            doc.setFullText(fullText);
            doc.setFileSize(fullText.length());

            List<String> chunks = chunkText(fullText);
            if (chunks.isEmpty()) {
                throw new IOException("文档分块后无有效内容");
            }

            List<DocumentChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                String chunkText = chunks.get(i);

                DocumentChunk chunk = new DocumentChunk();
                chunk.setUserId(userId);
                chunk.setDocumentId(doc.getId());
                chunk.setChunkIndex(i);
                chunk.setContent(chunkText);
                chunk.setCharCount(chunkText.length());

                Optional<String> embedding = generateEmbedding(chunkText);
                if (embedding.isPresent()) {
                    chunk.setEmbedding(embedding.get());
                } else {
                    chunk.setEmbedding(null);
                }

                chunkEntities.add(chunk);
            }

            chunkRepo.saveAll(chunkEntities);

            doc.setChunkCount(chunks.size());
            doc.setStatus("READY");
            documentRepo.save(doc);

            return doc;

        } catch (Exception e) {
            doc.setStatus("FAILED");
            doc.setErrorMessage(e.getMessage());
            documentRepo.save(doc);
            return doc;
        }
    }

    // ── Retrieval ───────────────────────────────────────────

    public List<RetrievedChunk> retrieveRelevantChunks(long userId, String query) {
        Optional<float[]> queryEmbedding = lanxin.embedding(query);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }

        List<DocumentChunk> allChunks = chunkRepo.findByUserId(userId);

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (DocumentChunk chunk : allChunks) {
            if (chunk.getEmbedding() == null || chunk.getEmbedding().isBlank()) {
                continue;
            }
            float[] chunkEmbedding = deserializeEmbedding(chunk.getEmbedding());
            double similarity = cosineSimilarity(queryEmbedding.get(), chunkEmbedding);
            if (similarity >= SIMILARITY_THRESHOLD) {
                scoredChunks.add(new ScoredChunk(chunk, similarity));
            }
        }

        return scoredChunks.stream()
                .sorted((a, b) -> Double.compare(b.similarity, a.similarity))
                .limit(TOP_K)
                .map(sc -> {
                    Document doc = documentRepo.findById(sc.chunk.getDocumentId()).orElse(null);
                    String docTitle = doc != null ? doc.getTitle() : "Unknown";
                    return new RetrievedChunk(
                            sc.chunk.getContent(),
                            docTitle,
                            sc.similarity,
                            sc.chunk.getChunkIndex()
                    );
                })
                .collect(Collectors.toList());
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
            generateEmbedding(chunkText).ifPresent(chunk::setEmbedding);
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
