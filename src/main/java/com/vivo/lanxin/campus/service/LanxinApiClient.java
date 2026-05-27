package com.vivo.lanxin.campus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivo.lanxin.campus.web.AiServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class LanxinApiClient {
    private final RestClient restClient;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final String embeddingModel;

    public LanxinApiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${lanxin.api-key:}") String apiKey,
            @Value("${lanxin.api-url:}") String apiUrl,
            @Value("${lanxin.model:}") String model,
            @Value("${lanxin.embedding-model:}") String embeddingModel
    ) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.embeddingModel = embeddingModel == null ? "" : embeddingModel.trim();
    }

    public boolean configured() {
        return !apiKey.isBlank() && !apiUrl.isBlank() && !model.isBlank();
    }

    public Map<String, Object> status() {
        return Map.of(
                "configured", configured(),
                "apiKeyPresent", !apiKey.isBlank(),
                "apiKeyLength", apiKey.length(),
                "apiUrlPresent", !apiUrl.isBlank(),
                "modelPresent", !model.isBlank(),
                "model", model.isBlank() ? "not-set" : model,
                "embeddingModelPresent", !embeddingModel.isBlank(),
                "embeddingModel", embeddingModel.isBlank() ? "not-set" : embeddingModel
        );
    }

    public Optional<String> chat(String systemPrompt, String userPrompt) {
        if (!configured()) {
            return Optional.empty();
        }

        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "temperature", 0.3,
                "thinking", Map.of("type", "disabled"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return callApi(request);
    }

    public Optional<String> chatWithImage(String systemPrompt, String textPrompt, String base64Image, String mimeType) {
        if (!configured()) {
            return Optional.empty();
        }

        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", textPrompt));
        userContent.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + mimeType + ";base64," + base64Image)
        ));

        Map<String, Object> request = Map.of(
                "model", model,
                "stream", false,
                "temperature", 0.3,
                "max_tokens", 1024,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                )
        );

        return callApi(request);
    }

    public void streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        if (!configured()) {
            mockStream(onToken);
            return;
        }

        Map<String, Object> request = Map.of(
                "model", model,
                "stream", true,
                "temperature", 0.3,
                "thinking", Map.of("type", "disabled"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize request", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .timeout(java.time.Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try {
            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                throw new AiServiceException(response.statusCode(), "AI 服务调用失败，状态码：" + response.statusCode());
            }
            try (java.util.stream.Stream<String> lines = response.body()) {
                lines.forEach(line -> handleSseLine(line, onToken));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(null, "AI 服务请求被中断");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AiServiceException(504, "AI 服务响应超时");
        } catch (java.io.IOException e) {
            throw new AiServiceException(503, "AI 服务网络异常：" + e.getMessage());
        }
    }

    private void handleSseLine(String line, Consumer<String> onToken) {
        if (line.startsWith("data:") && !"data: [DONE]".equals(line)) {
            String data = line.substring(5).trim();
            try {
                JsonNode node = objectMapper.readTree(data);
                JsonNode delta = node.at("/choices/0/delta");
                JsonNode content = delta.get("content");
                JsonNode reasoning = delta.get("reasoning_content");
                String text = null;
                if (content != null && content.isTextual() && !content.asText().isEmpty()) {
                    text = content.asText();
                } else if (reasoning != null && reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                    text = reasoning.asText();
                }
                if (text != null) {
                    onToken.accept(text);
                }
            } catch (Exception ignored) {
                // skip unparseable SSE lines
            }
        }
    }

    private void mockStream(Consumer<String> onToken) {
        String mock = "\n\n## 补课建议\n\n建议优先补齐核心定义和课堂例题。\n\n### 知识点要点\n- 核心概念\n- 典型题型\n- 易错点\n\n### 自测方向\n1. 用自己的话解释核心定义\n2. 完成一道基础题\n3. 指出一个可能考点";
        String[] chunks = mock.split("(?<=\\n)");
        for (String chunk : chunks) {
            onToken.accept(chunk);
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private Optional<String> callApi(Map<String, Object> request) {

        try {
            JsonNode response = restClient.post()
                    .uri(chatCompletionsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return extractContent(response);
        } catch (RestClientResponseException ex) {
            System.err.println("[LanxinApiClient] API call failed: " + ex.getStatusCode() + " " + ex.getMessage());
            int status = ex.getStatusCode().value();
            throw new AiServiceException(status, "AI 服务调用失败，状态码：" + status);
        } catch (ResourceAccessException ex) {
            System.err.println("[LanxinApiClient] API network error: " + ex.getMessage());
            throw new AiServiceException(503, "AI 服务网络异常：" + ex.getMessage());
        } catch (Exception ex) {
            System.err.println("[LanxinApiClient] API call failed: " + ex.getMessage());
            throw new AiServiceException(null, "AI 服务返回异常：" + ex.getMessage());
        }
    }

    private String chatCompletionsUrl() {
        String normalized = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String separator = normalized.contains("?") ? "&" : "?";
        if (normalized.endsWith("/chat/completions")) {
            return normalized + separator + "requestId=" + UUID.randomUUID();
        }
        return normalized + "/chat/completions?requestId=" + UUID.randomUUID();
    }

    public Optional<float[]> embedding(String input) {
        if (apiKey.isBlank() || apiUrl.isBlank() || embeddingModel.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> request = Map.of(
                "model", embeddingModel,
                "input", input
        );

        try {
            JsonNode response = restClient.post()
                    .uri(embeddingsUrl())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return extractEmbedding(response);
        } catch (RestClientResponseException ex) {
            System.err.println("[LanxinApiClient] Embedding call failed: " + ex.getStatusCode() + " " + ex.getMessage());
            int status = ex.getStatusCode().value();
            throw new AiServiceException(status, "AI 向量服务调用失败，状态码：" + status);
        } catch (ResourceAccessException ex) {
            System.err.println("[LanxinApiClient] Embedding network error: " + ex.getMessage());
            throw new AiServiceException(503, "AI 向量服务网络异常：" + ex.getMessage());
        } catch (Exception ex) {
            System.err.println("[LanxinApiClient] Embedding call failed: " + ex.getMessage());
            throw new AiServiceException(null, "AI 向量服务返回异常：" + ex.getMessage());
        }
    }

    private String embeddingsUrl() {
        String normalized = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        String separator = normalized.contains("?") ? "&" : "?";
        if (normalized.endsWith("/embeddings")) {
            return normalized + separator + "requestId=" + UUID.randomUUID();
        }
        return normalized + "/embeddings?requestId=" + UUID.randomUUID();
    }

    private Optional<float[]> extractEmbedding(JsonNode response) {
        if (response == null) {
            System.err.println("[LanxinApiClient] embedding response is null");
            return Optional.empty();
        }

        JsonNode embeddingNode = response.at("/data/0/embedding");
        if (embeddingNode.isArray() && embeddingNode.size() > 0) {
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = embeddingNode.get(i).floatValue();
            }
            return Optional.of(embedding);
        }

        JsonNode altNode = response.at("/embedding");
        if (altNode.isArray() && altNode.size() > 0) {
            float[] embedding = new float[altNode.size()];
            for (int i = 0; i < altNode.size(); i++) {
                embedding[i] = altNode.get(i).floatValue();
            }
            return Optional.of(embedding);
        }

        System.err.println("[LanxinApiClient] no embedding found in response");
        return Optional.empty();
    }

    private Optional<String> extractContent(JsonNode response) {
        if (response == null) {
            System.err.println("[LanxinApiClient] response is null");
            return Optional.empty();
        }

        JsonNode content = response.at("/choices/0/message/content");
        if (content.isTextual() && !content.asText().isBlank()) {
            return Optional.of(content.asText());
        }

        JsonNode text = response.at("/choices/0/text");
        if (text.isTextual() && !text.asText().isBlank()) {
            return Optional.of(text.asText());
        }

        JsonNode answer = response.at("/answer");
        if (answer.isTextual() && !answer.asText().isBlank()) {
            return Optional.of(answer.asText());
        }

        System.err.println("[LanxinApiClient] no content found in response");
        return Optional.empty();
    }
}
