package com.vivo.lanxin.campus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AppControllerTest {
    @Autowired
    private MockMvc mockMvc;

    private String token;

    @BeforeEach
    void login() throws Exception {
        String response = mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        token = response.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private String auth() {
        return "Bearer " + token;
    }

    @Test
    void dashboardReturnsData() throws Exception {
        mockMvc.perform(get("/api/v1/stats/dashboard")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noteCount", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.offlineReady").value(true));
    }

    @Test
    void noteProcessingCreatesStructuredNote() throws Exception {
        mockMvc.perform(post("/api/v1/ai/note/process")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rawText\":\"data structure binary tree complexity O(n)\",\"offline\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.offlineCreated").value(true))
                .andExpect(jsonPath("$.keyPoints").isArray());
    }

    @Test
    void reminderParsingCreatesTask() throws Exception {
        mockMvc.perform(post("/api/v1/reminders/parse")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"submit data structure lab in 2 days\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.priority").value("medium"));
    }

    @Test
    void profileReturnsUserInfo() throws Exception {
        mockMvc.perform(get("/api/v1/user/profile")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());
    }

    @Test
    void notesReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/notes")
                        .header("Authorization", auth()))
                .andExpect(status().isOk());
    }
}
