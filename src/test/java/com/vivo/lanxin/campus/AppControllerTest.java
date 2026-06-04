package com.vivo.lanxin.campus;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
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
                .andExpect(jsonPath("$.priority").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.dueDate").exists());
    }

    @Test
    void profileReturnsUserInfo() throws Exception {
        mockMvc.perform(get("/api/v1/user/profile")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists());
    }

    @Test
    void loginReturnsJwtAndRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.expiresAt").isNumber());
    }

    @Test
    void notesReturnList() throws Exception {
        mockMvc.perform(get("/api/v1/notes")
                        .header("Authorization", auth()))
                .andExpect(status().isOk());
    }

    @Test
    void noteDtoDoesNotExposeInternalFields() throws Exception {
        mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"dto note\",\"rawText\":\"content\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andExpect(jsonPath("$.ragDocumentId").doesNotExist());
    }

    @Test
    void invalidNoteRequestReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/notes")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void nearDeadlineReminderAppearsOnHome() throws Exception {
        String dueDate = LocalDate.now().plusDays(3).toString();
        mockMvc.perform(post("/api/v1/reminders")
                        .header("Authorization", auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"three day task\",\"dueDate\":\"" + dueDate + "\",\"priority\":\"high\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/reminders/today")
                        .header("Authorization", auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].title", hasItem("three day task")));
    }
}
