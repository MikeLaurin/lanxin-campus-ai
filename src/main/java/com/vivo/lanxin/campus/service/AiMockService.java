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
        String source = text(payload, "text", "下周三前提交数据结构实验报告，要求包含代码和复杂度分析。");
        String course = inferCourse(source);
        LocalDate due = inferDueDate(source);
        String priority = source.contains("考试") || source.contains("截止") || source.contains("明天") ? "high" : "medium";

        Reminder reminder = new Reminder();
        reminder.setTitle(cleanTitle(source, course));
        reminder.setCourse(course);
        reminder.setDueDate(due);
        reminder.setPriority(priority);
        reminder.setSource("AI 解析：" + source);
        return reminder;
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

    private String inferCourse(String text) {
        if (text.contains("高数") || text.contains("极限") || text.contains("函数")) {
            return "高等数学";
        }
        if (text.contains("数据结构") || text.contains("二叉树") || text.contains("复杂度")) {
            return "数据结构";
        }
        if (text.contains("英语") || text.toLowerCase(Locale.ROOT).contains("translation")) {
            return "大学英语";
        }
        return "专业课程";
    }

    private String summaryFor(String course, String rawText) {
        return "已将“" + course + "”课堂内容整理为标题、要点、公式/术语和复习标签，原始内容保留用于回看。";
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

    private LocalDate inferDueDate(String text) {
        if (text.contains("明天")) {
            return LocalDate.now().plusDays(1);
        }
        if (text.contains("后天")) {
            return LocalDate.now().plusDays(2);
        }
        Matcher matcher = DAYS_PATTERN.matcher(text);
        if (matcher.find()) {
            return LocalDate.now().plusDays(Long.parseLong(matcher.group(1)));
        }
        if (text.contains("下周")) {
            return LocalDate.now().plusDays(7);
        }
        return LocalDate.now().plusDays(3);
    }

    private String cleanTitle(String text, String course) {
        if (text.length() <= 24) {
            return text;
        }
        return course + "：" + text.substring(0, 20) + "...";
    }
}
