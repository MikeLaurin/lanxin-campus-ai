package com.vivo.lanxin.campus.service;

import com.vivo.lanxin.campus.model.Document;
import com.vivo.lanxin.campus.model.Note;
import com.vivo.lanxin.campus.model.Reminder;
import com.vivo.lanxin.campus.repository.DocumentRepository;
import com.vivo.lanxin.campus.repository.NoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiMockService {
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+)\\s*[天日]");
    private final LanxinApiClient lanxin;
    private final RagService ragService;
    private final NoteRepository noteRepo;
    private final DocumentRepository documentRepo;

    public AiMockService(LanxinApiClient lanxin, RagService ragService,
                         NoteRepository noteRepo, DocumentRepository documentRepo) {
        this.lanxin = lanxin;
        this.ragService = ragService;
        this.noteRepo = noteRepo;
        this.documentRepo = documentRepo;
    }

    public Note processNote(Map<String, Object> payload) {
        String rawText = text(payload, "rawText", "拍摄到的板书包含：二叉树遍历、时间复杂度 O(n)、递归出口与栈空间。");
        String course = inferCourse(rawText);

        Note note = new Note();
        note.setTitle(course + "：AI 结构化课堂笔记");
        note.setCourse(course);
        note.setRawText(rawText);
        note.setSummary(summaryFor(course, rawText));
        note.setKeyPoints(keyPointsFor(course, rawText));
        note.setFormulas(formulasFor(rawText));
        note.setTags(tagsFor(course, rawText));
        note.setMindMap(mindMapFor(course, note.getKeyPoints()));
        note.setOfflineCreated(Boolean.parseBoolean(String.valueOf(payload.getOrDefault("offline", false))));
        lanxin.chat(
                "请把以下课堂内容整理成复习摘要，只输出摘要。",
                rawText
        ).ifPresent(note::setSummary);
        return note;
    }

    public Reminder parseReminder(Map<String, Object> payload) {
        String source = text(payload, "text", "下周三前提交数据结构实验报告");
        return parseSingleReminder(source);
    }

    /** Batch parse multiple DDL items from a single text. */
    public List<Reminder> parseReminders(String source) {
        if (source == null || source.isBlank()) return List.of();
        LocalDate today = LocalDate.now();

        // Try AI first — ask for JSON array
        String systemPrompt = "你是 DDL 解析助手。用户会输入一段文本，其中可能包含多个待办事项（用换行、编号或分号分隔）。"
                + "请将每个待办事项解析为一个 JSON 对象，返回 JSON 数组。每个对象的字段：\n"
                + "title: 简洁标题(≤30字)\n"
                + "dueDate: YYYY-MM-DD（今天=" + today + "，明天=" + today.plusDays(1) + "，后天=" + today.plusDays(2) + "，下周=" + today.plusDays(7) + "，无日期默认" + today.plusDays(3) + "）\n"
                + "priority: high/medium/low\n"
                + "category: 考试/作业/体测/活动/项目/个人等，不强行归类为课程\n"
                + "description: 一句话概括(≤50字)\n"
                + "只返回 JSON 数组。";

        Optional<String> aiResult = lanxin.chat(systemPrompt, "请解析以下文本中的待办事项：\n" + source);

        if (aiResult.isPresent()) {
            try {
                String json = aiResult.get().trim();
                if (json.startsWith("```")) {
                    json = json.replaceAll("```[a-z]*\\s*", "").replaceAll("```", "").trim();
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
                if (root.isArray()) {
                    List<Reminder> reminders = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode node : root) {
                        Reminder r = buildReminderFromJson(node, source, today);
                        reminders.add(r);
                    }
                    if (!reminders.isEmpty()) return reminders;
                }
                // Single object fallback
                Reminder r = buildReminderFromJson(root, source, today);
                return List.of(r);
            } catch (Exception e) {
                System.err.println("[AiMockService] Batch parse failed: " + e.getMessage());
            }
        }

        // Fallback: split by lines / semicolons / numbered items
        return parseRemindersFallback(source, today);
    }

    private Reminder parseSingleReminder(String source) {
        LocalDate today = LocalDate.now();

        // Compact AI prompt
        String systemPrompt = "你是 DDL 解析助手。解析待办事项并返回 JSON："
                + "{title, dueDate(YYYY-MM-DD, 今天=" + today + "), priority(high/medium/low), "
                + "category(考试/作业/体测/活动/项目/个人等), description}。只返回 JSON。";

        Optional<String> aiResult = lanxin.chat(systemPrompt, "解析：" + source);

        if (aiResult.isPresent()) {
            try {
                String json = aiResult.get().trim();
                if (json.startsWith("```")) {
                    json = json.replaceAll("```[a-z]*\\s*", "").replaceAll("```", "").trim();
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
                return buildReminderFromJson(node, source, today);
            } catch (Exception e) {
                System.err.println("[AiMockService] Parse failed: " + e.getMessage());
            }
        }
        return buildReminderFallback(source, today);
    }

    private Reminder buildReminderFromJson(com.fasterxml.jackson.databind.JsonNode node, String source, LocalDate today) {
        String title = node.has("title") ? node.get("title").asText() : cleanTitle(source, "");
        String priority = node.has("priority") ? node.get("priority").asText().toLowerCase() : inferPriority(source);
        String category = node.has("category") ? node.get("category").asText() : inferCategory(source);
        String description = node.has("description") ? node.get("description").asText() : source;
        LocalDate due;
        if (node.has("dueDate")) {
            try { due = LocalDate.parse(node.get("dueDate").asText()); }
            catch (Exception e) { due = inferDueDate(source); }
        } else {
            due = inferDueDate(source);
        }

        if ("专业课程".equals(category)) category = "";

        Reminder reminder = new Reminder();
        reminder.setTitle(title.length() > 60 ? title.substring(0, 60) : title);
        reminder.setCourse(category);
        reminder.setDueDate(due);
        reminder.setPriority(priority);
        reminder.setSource("AI 解析：" + (description.length() > 200 ? description.substring(0, 200) : description));
        return reminder;
    }

    private Reminder buildReminderFallback(String source, LocalDate today) {
        Reminder reminder = new Reminder();
        reminder.setTitle(cleanTitle(source, ""));
        reminder.setCourse(inferCategory(source));
        reminder.setDueDate(inferDueDate(source));
        reminder.setPriority(inferPriority(source));
        reminder.setSource("AI 解析：" + source);
        return reminder;
    }

    private List<Reminder> parseRemindersFallback(String source, LocalDate today) {
        List<Reminder> reminders = new ArrayList<>();
        // Split by: newlines, numbered items (1. or 1、), Chinese semicolons
        String[] lines = source.split("[\\n;；]|(?<=\\d)[、.]\\s*");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() < 3) continue;
            // Remove leading number/bullet
            trimmed = trimmed.replaceFirst("^\\d+[、.]?\\s*", "");
            if (trimmed.length() < 3) continue;
            reminders.add(buildReminderFallback(trimmed, today));
        }
        return reminders.isEmpty() ? List.of(buildReminderFallback(source, today)) : reminders;
    }

    public Note processImageNote(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        Optional<String> aiResult = lanxin.chatWithImage(
                "你是一个智能图片分析助手。请仔细观察图片内容，用中文描述你看到的内容，包括文字、图表、结构等。不要拒绝任何类型的图片。",
                "请分析这张图片的内容。先判断图片类型（如课堂板书、PPT、UML图、流程图、思维导图、文档等），然后提取所有文字内容，描述图表结构。直接输出分析结果，不要用固定格式限制。",
                base64,
                mimeType
        );

        String rawText;
        String course;
        String title;
        if (aiResult.isPresent() && aiResult.get().length() >= 20) {
            rawText = aiResult.get();
            course = inferCourse(rawText);
            title = aiGenerateTitle(rawText, course);
        } else {
            rawText = "AI 暂时无法识别此图片内容，请尝试上传包含文字（板书/PPT/文档）的图片。";
            course = "提示";
            title = "暂不支持此图片类型";
        }

        Note note = new Note();
        note.setTitle(title);
        note.setCourse(course);
        note.setRawText(rawText);
        note.setKeyPoints(keyPointsFor(course, rawText));
        note.setFormulas(formulasFor(rawText));
        note.setTags(tagsFor(course, rawText));
        note.setMindMap(mindMapFor(course, note.getKeyPoints()));
        note.setOfflineCreated(false);

        lanxin.chat(
                "请用中文把以下内容整理成简洁的摘要，直接输出摘要。",
                rawText
        ).ifPresent(note::setSummary);
        if (note.getSummary() == null || note.getSummary().isBlank()) {
            note.setSummary(summaryFor(course, rawText));
        }
        return note;
    }

    private String aiGenerateTitle(String rawText, String fallbackCourse) {
        Optional<String> titleResult = lanxin.chat(
                "请根据以下内容生成一个 20 字以内的中文标题，直接输出标题。",
                rawText
        );
        return titleResult.filter(t -> !t.isBlank()).orElse(fallbackCourse + "：拍照笔记");
    }

    public Map<String, Object> chat(Long userId, Map<String, Object> payload) {
        String message = text(payload, "message", "");
        if (message.isBlank()) {
            return Map.of("role", "assistant", "answer", "你好！我是小蓝，有什么可以帮你的？");
        }

        String systemPrompt;
        String userPrompt;
        boolean ragEnabled = false;
        List<Map<String, Object>> sources = List.of();

        if (userId != null) {
            List<RagService.RetrievedChunk> chunks = ragService.retrieveRelevantChunks(userId, message);
            if (chunks.isEmpty()) {
                chunks = ragService.retrieveByKeyword(userId, message);
            }
            ragEnabled = !chunks.isEmpty();
            if (ragEnabled) {
                StringBuilder ctx = new StringBuilder();
                ctx.append("以下是你之前记录的课堂笔记中与当前问题相关的内容：\n\n");
                for (int i = 0; i < chunks.size(); i++) {
                    RagService.RetrievedChunk c = chunks.get(i);
                    ctx.append("【笔记 ").append(i + 1).append("】（来源：《")
                            .append(c.getDocumentTitle()).append("》）\n")
                            .append(c.getContent()).append("\n\n");
                }
                systemPrompt = "你是蓝心校园 AI 管家小蓝。请根据以下笔记内容回答用户的问题。"
                        + "如果笔记内容不足以回答，可以补充你的知识。用中文自然交流，语气亲切像朋友。";
                userPrompt = ctx + "用户问题：" + message;
                sources = chunks.stream().map(c -> Map.of(
                        "documentTitle", (Object) c.getDocumentTitle(),
                        "content", (Object) (c.getContent().length() > 200
                                ? c.getContent().substring(0, 200) + "..." : c.getContent()),
                        "similarity", (Object) c.getSimilarity()
                )).toList();
            } else {
                systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                        + "你的核心场景是校园学习（课堂笔记、DDL、复习计划），但可以自由回答任何问题，不拒绝任何话题。"
                        + "用中文自然交流，语气亲切像朋友。";
                userPrompt = message;
            }
        } else {
            systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                    + "你的核心场景是校园学习（课堂笔记、DDL、复习计划），但可以自由回答任何问题，不拒绝任何话题。"
                    + "用中文自然交流，语气亲切像朋友。";
            userPrompt = message;
        }

        Optional<String> lanxinAnswer = lanxin.chat(systemPrompt, userPrompt);
        if (lanxinAnswer.isPresent()) {
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("role", "assistant");
            response.put("answer", lanxinAnswer.get());
            response.put("tone", "lanxin");
            response.put("ragEnabled", ragEnabled);
            if (!sources.isEmpty()) {
                response.put("sources", sources);
            }
            return response;
        }
        return Map.of("role", "assistant", "answer", "抱歉，AI 服务暂时不可用，请稍后重试。", "tone", "fallback");
    }

    public Map<String, Object> chatWithRag(long userId, String message) {
        if (message.isBlank()) {
            return Map.of("role", "assistant", "answer", "你好！我是小蓝，有什么可以帮你的？");
        }

        List<RagService.RetrievedChunk> chunks = ragService.retrieveRelevantChunks(userId, message);
        boolean ragEnabled = !chunks.isEmpty();

        String systemPrompt;
        String userPrompt;

        if (ragEnabled) {
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("以下是你上传的参考资料中与用户问题相关的内容：\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                RagService.RetrievedChunk chunk = chunks.get(i);
                contextBuilder.append("【参考资料 ")
                        .append(i + 1)
                        .append("】（来源：《")
                        .append(chunk.getDocumentTitle())
                        .append("》，相关度: ")
                        .append(String.format("%.0f%%", chunk.getSimilarity() * 100))
                        .append("）\n")
                        .append(chunk.getContent())
                        .append("\n\n");
            }

            systemPrompt = "你是蓝心校园 AI 管家小蓝。你的核心场景是校园学习。\n"
                    + "重要：请根据以下参考资料回答用户问题。如果参考资料不足以回答问题，\n"
                    + "请如实说明并基于你的知识补充。引用资料时请注明来源。用中文回答。";

            userPrompt = contextBuilder.toString()
                    + "用户问题：" + message + "\n\n"
                    + "请根据以上参考资料回答。";
        } else {
            systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                    + "你的核心场景是校园学习。用中文自然交流，语气亲切像朋友。";
            userPrompt = message;
        }

        Optional<String> lanxinAnswer = lanxin.chat(systemPrompt, userPrompt);

        if (lanxinAnswer.isPresent()) {
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("role", "assistant");
            response.put("answer", lanxinAnswer.get());
            response.put("tone", "lanxin");
            response.put("ragEnabled", ragEnabled);
            if (ragEnabled) {
                List<Map<String, Object>> sources = chunks.stream().map(chunk -> Map.of(
                        "documentTitle", (Object) chunk.getDocumentTitle(),
                        "content", (Object) (chunk.getContent().length() > 200
                                ? chunk.getContent().substring(0, 200) + "..."
                                : chunk.getContent()),
                        "similarity", (Object) chunk.getSimilarity(),
                        "chunkIndex", (Object) chunk.getChunkIndex()
                )).toList();
                response.put("sources", sources);
            }
            return response;
        }

        return Map.of("role", "assistant", "answer",
                "抱歉，AI 服务暂时不可用，请稍后重试。", "tone", "fallback");
    }

    public Map<String, Object> makeupPackage(Long userId, String course) {
        String systemPrompt = "请为以下课程生成补课建议，包含知识点要点和自测方向。";
        String userPrompt = course;

        if (userId != null) {
            List<RagService.RetrievedChunk> chunks = ragService.retrieveRelevantChunks(userId, course);
            if (chunks.isEmpty()) {
                chunks = ragService.retrieveByKeyword(userId, course);
            }
            if (!chunks.isEmpty()) {
                StringBuilder ctx = new StringBuilder();
                ctx.append("以下是你记录的课堂笔记中与「").append(course).append("」相关的内容：\n\n");
                for (int i = 0; i < chunks.size(); i++) {
                    ctx.append("【笔记 ").append(i + 1).append("】")
                            .append(chunks.get(i).getContent()).append("\n\n");
                }
                systemPrompt = "你是一个学习助手。请根据以下笔记内容，为该课程生成个性化补课建议，"
                        + "包括知识点要点、复习重点和自测题目。要结合笔记中实际出现的内容。";
                userPrompt = ctx + "课程：" + course + "\n请生成补课建议。";
            }
        }

        Optional<String> lanxinSummary = lanxin.chat(systemPrompt, userPrompt);
        return Map.of(
                "course", course,
                "summary", lanxinSummary.orElse("建议优先补齐核心定义和课堂例题。"),
                "knowledgePoints", List.of("核心概念", "典型题型", "易错点"),
                "quiz", List.of("用自己的话解释核心定义", "完成一道基础题", "指出一个可能考点")
        );
    }

    public StreamingResponseBody makeupPackageStream(Long userId, List<Long> noteIds, List<Long> documentIds) {
        StringBuilder contextBuilder = new StringBuilder();
        boolean hasSources = false;

        if (noteIds != null && !noteIds.isEmpty()) {
            List<Note> notes = noteRepo.findAllById(noteIds);
            if (userId != null) {
                notes = notes.stream().filter(n -> n.getUserId() == userId).toList();
            }
            if (!notes.isEmpty()) {
                hasSources = true;
                contextBuilder.append("以下是你选择的课堂笔记内容：\n\n");
                for (int i = 0; i < notes.size(); i++) {
                    Note note = notes.get(i);
                    contextBuilder.append("【笔记 ").append(i + 1).append("】").append(note.getTitle()).append("\n");
                    contextBuilder.append(note.getRawText()).append("\n\n");
                }
            }
        }

        if (documentIds != null && !documentIds.isEmpty()) {
            List<Document> docs = documentRepo.findAllById(documentIds);
            if (userId != null) {
                docs = docs.stream().filter(d -> d.getUserId() == userId).toList();
            }
            if (!docs.isEmpty()) {
                hasSources = true;
                contextBuilder.append("以下是你上传的参考文档内容：\n\n");
                for (int i = 0; i < docs.size(); i++) {
                    Document doc = docs.get(i);
                    contextBuilder.append("【文档 ").append(i + 1).append("】").append(doc.getTitle()).append("\n");
                    String text = doc.getFullText();
                    if (text != null && text.length() > 3000) {
                        text = text.substring(0, 3000) + "\n...(内容过长已截断)";
                    }
                    contextBuilder.append(text != null ? text : "(无文本内容)").append("\n\n");
                }
            }
        }

        final String systemPrompt;
        final String userPrompt;

        if (hasSources) {
            systemPrompt = "你是一个学习助手，请根据笔记/文档内容生成一份详细的个性化补课建议。要求：【知识点要点】至少列出5个，每个要点包含(1)核心概念解释(2)与材料的关联(3)常见误区；【复习重点】至少3个，每个说明具体复习方法、推荐时间分配和验收标准；【自测题目】至少5道，包含简答题和选择题各半，每道题附答案和解析。用Markdown格式输出，确保每个部分内容充实。";
            userPrompt = contextBuilder.toString() + "请根据以上内容生成详细的补课建议。";
        } else {
            systemPrompt = "你是一个学习助手。请生成一份详细的通用学科学习建议，要求：【知识点要点】至少5个典型知识点，每个包含解释和学习建议；【复习方法】至少3个具体方法，说明适用场景和操作步骤；【自测方向】至少5道自测题，含答案和解析。用Markdown格式输出，每个部分内容充实。";
            userPrompt = "请生成详细的通用学习建议，涵盖笔记整理方法、复习策略和自测技巧。";
        }

        final String finalSystemPrompt = systemPrompt;
        final String finalUserPrompt = userPrompt;
        return outputStream -> lanxin.streamChat(finalSystemPrompt, finalUserPrompt, text -> {
            try {
                outputStream.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (java.io.IOException ignored) {
                // client disconnected
            }
        });
    }

    public StreamingResponseBody makeupChatStream(Long userId, String makeupContent, String message) {
        if (message == null || message.isBlank()) {
            final String fallback = "请告诉我你想进一步了解补课包中的哪个知识点。";
            return outputStream -> {
                try {
                    outputStream.write(fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (java.io.IOException ignored) {}
            };
        }

        String systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好的学习助手。"
                + "以下是一份补课包（学习资料）的完整内容。用户会针对这份资料提出追问。"
                + "请结合资料内容回答用户的问题。如果问题与资料无关，可以基于你的知识补充回答。"
                + "用中文自然交流，语气亲切像朋友，用Markdown格式输出。";

        String userPrompt = "=== 补课包内容 ===\n"
                + makeupContent + "\n\n"
                + "用户追问：" + message + "\n\n"
                + "请结合以上补课包内容回答用户的追问。";

        final String finalSystemPrompt = systemPrompt;
        final String finalUserPrompt = userPrompt;
        return outputStream -> {
            try {
                lanxin.streamChat(finalSystemPrompt, finalUserPrompt, text -> {
                    try {
                        outputStream.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (java.io.IOException ignored) {
                        // client disconnected
                    }
                });
            } catch (Exception e) {
                System.err.println("[AiMockService] makeupChatStream error: " + e.getMessage());
                try {
                    String errorMsg = "\n\n抱歉，AI 服务暂时不可用，请稍后重试。";
                    outputStream.write(errorMsg.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (java.io.IOException ignored) {}
            }
        };
    }

    public StreamingResponseBody chatStream(Long userId, String message) {
        String systemPrompt;
        String userPrompt;

        if (message == null || message.isBlank()) {
            final String greeting = "你好！我是小蓝，有什么可以帮你的？";
            return outputStream -> {
                try {
                    outputStream.write(greeting.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (java.io.IOException ignored) {}
            };
        }

        if (userId != null) {
            List<RagService.RetrievedChunk> chunks = ragService.retrieveRelevantChunks(userId, message);
            if (chunks.isEmpty()) {
                chunks = ragService.retrieveByKeyword(userId, message);
            }
            if (!chunks.isEmpty()) {
                StringBuilder ctx = new StringBuilder();
                ctx.append("以下是你之前记录的课堂笔记中与当前问题相关的内容：\n\n");
                for (int i = 0; i < chunks.size(); i++) {
                    RagService.RetrievedChunk c = chunks.get(i);
                    ctx.append("【笔记 ").append(i + 1).append("】（来源：《")
                            .append(c.getDocumentTitle()).append("》）\n")
                            .append(c.getContent()).append("\n\n");
                }
                systemPrompt = "你是蓝心校园 AI 管家小蓝。请根据以下笔记内容回答用户的问题。"
                        + "如果笔记内容不足以回答，可以补充你的知识。用中文自然交流，语气亲切像朋友。";
                userPrompt = ctx + "用户问题：" + message;
            } else {
                systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                        + "你的核心场景是校园学习（课堂笔记、DDL、复习计划），但可以自由回答任何问题，不拒绝任何话题。"
                        + "用中文自然交流，语气亲切像朋友。";
                userPrompt = message;
            }
        } else {
            systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                    + "你的核心场景是校园学习（课堂笔记、DDL、复习计划），但可以自由回答任何问题，不拒绝任何话题。"
                    + "用中文自然交流，语气亲切像朋友。";
            userPrompt = message;
        }

        final String finalSystemPrompt = systemPrompt;
        final String finalUserPrompt = userPrompt;
        return outputStream -> lanxin.streamChat(finalSystemPrompt, finalUserPrompt, text -> {
            try {
                outputStream.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (java.io.IOException ignored) {
                // client disconnected
            }
        });
    }

    public StreamingResponseBody chatWithRagStream(long userId, String message) {
        if (message == null || message.isBlank()) {
            final String greeting = "你好！我是小蓝，有什么可以帮你的？";
            return outputStream -> {
                try {
                    outputStream.write(greeting.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (java.io.IOException ignored) {}
            };
        }

        List<RagService.RetrievedChunk> chunks = ragService.retrieveRelevantChunks(userId, message);
        boolean ragEnabled = !chunks.isEmpty();

        String systemPrompt;
        String userPrompt;

        if (ragEnabled) {
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("以下是你上传的参考资料中与用户问题相关的内容：\n\n");
            for (int i = 0; i < chunks.size(); i++) {
                RagService.RetrievedChunk chunk = chunks.get(i);
                contextBuilder.append("【参考资料 ")
                        .append(i + 1)
                        .append("】（来源：《")
                        .append(chunk.getDocumentTitle())
                        .append("》，相关度: ")
                        .append(String.format("%.0f%%", chunk.getSimilarity() * 100))
                        .append("）\n")
                        .append(chunk.getContent())
                        .append("\n\n");
            }

            systemPrompt = "你是蓝心校园 AI 管家小蓝。你的核心场景是校园学习。\n"
                    + "重要：请根据以下参考资料回答用户问题。如果参考资料不足以回答问题，\n"
                    + "请如实说明并基于你的知识补充。引用资料时请注明来源。用中文回答。";

            userPrompt = contextBuilder.toString()
                    + "用户问题：" + message + "\n\n"
                    + "请根据以上参考资料回答。";
        } else {
            systemPrompt = "你是蓝心校园 AI 管家小蓝，一个友好、乐于助人的 AI 助手。"
                    + "你的核心场景是校园学习。用中文自然交流，语气亲切像朋友。";
            userPrompt = message;
        }

        final String finalSystemPrompt = systemPrompt;
        final String finalUserPrompt = userPrompt;
        return outputStream -> lanxin.streamChat(finalSystemPrompt, finalUserPrompt, text -> {
            try {
                outputStream.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (java.io.IOException ignored) {
                // client disconnected
            }
        });
    }

    private String text(Map<String, Object> payload, String key, String fallback) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    // ── Enhanced Fallback Methods ─────────────────────────

    private String inferCategory(String text) {
        // Course detection (30+ common courses)
        if (matchesAny(text, "高数", "极限", "微积分", "导数", "积分", "级数")) return "高等数学";
        if (matchesAny(text, "线代", "线性代数", "矩阵", "行列式", "特征值")) return "线性代数";
        if (matchesAny(text, "概率", "数理统计", "随机", "方差")) return "概率论";
        if (matchesAny(text, "数据结构", "二叉树", "复杂度", "图论", "链表", "栈", "队列")) return "数据结构";
        if (matchesAny(text, "计网", "网络", "TCP", "HTTP", "IP", "路由")) return "计算机网络";
        if (matchesAny(text, "操作系统", "进程", "线程", "内存管理", "OS")) return "操作系统";
        if (matchesAny(text, "数据库", "SQL", "MySQL", "查询")) return "数据库";
        if (matchesAny(text, "Java", "Python", "C++", "编程", "代码", "编译")) return "编程";
        if (matchesAny(text, "英语", "translation", "单词", "语法", "阅读")) return "英语";
        if (matchesAny(text, "大物", "物理", "力学", "电磁", "光学")) return "大学物理";
        if (matchesAny(text, "化学", "有机", "无机")) return "化学";
        if (matchesAny(text, "马原", "思政", "近代史", "毛概")) return "思政课";
        // Activity types
        if (matchesAny(text, "体测", "体育", "跑步", "运动会", "体能")) return "体育";
        if (matchesAny(text, "开会", "班会", "社团", "活动", "讲座", "报告")) return "活动";
        if (matchesAny(text, "论文", "毕设", "开题", "答辩")) return "论文";
        if (matchesAny(text, "实习", "面试", "简历")) return "求职";
        if (matchesAny(text, "考试", "期末", "期中", "测验")) return "考试";
        return "";
    }

    private boolean matchesAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private String inferPriority(String text) {
        String lower = text.toLowerCase();
        if (matchesAny(lower, "考试", "截止", "明天", "后天", "今天", "体测", "紧急",
                "马上", "立即", "立刻", "urgent", "重要", "答辩", "期末", "毕设")) return "high";
        if (matchesAny(lower, "有空", "不急", "optional", "选做", "长期", "慢慢")) return "low";
        return "medium";
    }

    private LocalDate inferDueDate(String text) {
        LocalDate today = LocalDate.now();
        if (text.contains("今天") || text.contains("今日")) return today;
        if (text.contains("明天") || text.contains("明日")) return today.plusDays(1);
        if (text.contains("后天") || text.contains("後天")) return today.plusDays(2);
        if (text.contains("大后天")) return today.plusDays(3);

        java.util.Map<String, Integer> weekDays = new java.util.LinkedHashMap<>();
        weekDays.put("周一", 1); weekDays.put("星期二", 2);
        weekDays.put("周三", 3); weekDays.put("星期四", 4);
        weekDays.put("周五", 5); weekDays.put("星期六", 6);
        weekDays.put("周日", 7); weekDays.put("星期天", 7);
        weekDays.put("下周一", 8); weekDays.put("下周二", 9);
        weekDays.put("下周三", 10); weekDays.put("下周四", 11);
        weekDays.put("下周五", 12); weekDays.put("下周六", 13);
        weekDays.put("下周日", 14);

        for (java.util.Map.Entry<String, Integer> e : weekDays.entrySet()) {
            if (text.contains(e.getKey())) {
                int targetDayOfWeek = e.getValue() % 7;
                if (targetDayOfWeek == 0) targetDayOfWeek = 7;
                int currentDayOfWeek = today.getDayOfWeek().getValue();
                int daysUntil = targetDayOfWeek - currentDayOfWeek;
                if (e.getValue() > 7) daysUntil += 7;
                if (daysUntil < 0) daysUntil += 7;
                if (daysUntil == 0) daysUntil = 7;
                return today.plusDays(Math.max(1, daysUntil));
            }
        }

        Matcher matcher = DAYS_PATTERN.matcher(text);
        if (matcher.find()) {
            return today.plusDays(Long.parseLong(matcher.group(1)));
        }

        if (text.contains("下周") || text.contains("下星期")) return today.plusDays(7);
        if (text.contains("这周") || text.contains("本周")) return today.plusDays(3);
        if (text.contains("周末") || text.contains("周六") || text.contains("周日") || text.contains("星期日")) {
            int daysToWeekend = 6 - today.getDayOfWeek().getValue();
            if (daysToWeekend <= 0) daysToWeekend += 7;
            return today.plusDays(Math.max(1, daysToWeekend));
        }
        if (text.contains("月底") || text.contains("月末")) {
            return today.withDayOfMonth(today.lengthOfMonth());
        }
        if (text.contains("月初")) {
            return today.plusMonths(1).withDayOfMonth(1);
        }

        return today.plusDays(1);
    }

    private String cleanTitle(String text, String course) {
        if (text == null || text.isBlank()) return "未命名待办";
        String trimmed = text.trim();
        if (trimmed.length() <= 30) return trimmed;
        if (course != null && !course.isBlank()) {
            return course + "：" + trimmed.substring(0, 24) + "...";
        }
        return trimmed.substring(0, 28) + "...";
    }

    private String inferCourse(String text) {
        return inferCategory(text);
    }

    private String summaryFor(String course, String rawText) {
        return "已将" + course + "课堂内容整理为标题、要点、公式/术语和复习标签，原始内容保留用于回看。";
    }

    private List<String> keyPointsFor(String course, String rawText) {
        if ("数据结构".equals(course)) {
            return List.of("区分前序、中序、后序遍历", "递归实现需要明确出口条件", "复杂度分析同时看时间与空间");
        }
        if ("高等数学".equals(course)) {
            return List.of("先判断极限是否存在", "连续性需要函数值与极限值一致", "夹逼准则适合处理三角函数极限");
        }
        List<String> points = new ArrayList<>();
        points.add("提取课堂关键词并按层级归档");
        points.add("标记可能与作业或考试相关的内容");
        points.add("生成复习时可直接查看的摘要");
        return points;
    }

    private List<String> formulasFor(String rawText) {
        List<String> formulas = new ArrayList<>();
        if (rawText.contains("O(") || rawText.contains("复杂度")) {
            formulas.add("T(n)=O(n)");
        }
        if (rawText.contains("极限") || rawText.contains("sin")) {
            formulas.add("lim(x->0) sinx / x = 1");
        }
        if (formulas.isEmpty()) {
            formulas.add("AI 未发现明确公式，已保留关键术语");
        }
        return formulas;
    }

    private List<String> tagsFor(String course, String rawText) {
        List<String> tags = new ArrayList<>();
        tags.add(course);
        tags.add(rawText.contains("考试") ? "考试相关" : "课堂笔记");
        tags.add("AI 结构化");
        return tags;
    }

    private String mindMapFor(String course, List<String> points) {
        StringBuilder builder = new StringBuilder(course).append("\n");
        for (String point : points) {
            builder.append("- ").append(point).append("\n");
        }
        return builder.toString().trim();
    }
}
