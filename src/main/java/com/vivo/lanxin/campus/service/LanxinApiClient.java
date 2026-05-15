package com.vivo.lanxin.campus.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class LanxinApiClient {
    private final RestClient restClient;
    private final String apiKey;
    private final String apiUrl;
    private final String model;

    public LanxinApiClient(
            RestClient.Builder builder,
            @Value("${lanxin.api-key:}") String apiKey,
            @Value("${lanxin.api-url:}") String apiUrl,
            @Value("${lanxin.model:}") String model
    ) {
        this.restClient = builder.build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim();
        this.model = model == null ? "" : model.trim();
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
                "model", model.isBlank() ? "not-set" : model
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
        } catch (Exception ex) {
            System.err.println("[LanxinApiClient] API call failed: " + ex.getMessage());
            return Optional.empty();
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
        if (!configured()) {
            return Optional.empty();
        }

        Map<String, Object> request = Map.of(
                "model", model,
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
        } catch (Exception ex) {
            System.err.println("[LanxinApiClient] Embedding call failed: " + ex.getMessage());
            return Optional.empty();
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
