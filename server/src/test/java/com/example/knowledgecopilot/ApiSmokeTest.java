package com.example.knowledgecopilot;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSmokeTest {
    @Autowired MockMvc mvc;

    @Test
    void docsAndCrudWorkWithModelsDisabled() throws Exception {
        mvc.perform(get("/doc.html")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/chat']").exists());
        mvc.perform(
                        post("/api/v1/knowledge-bases")
                                .contentType("application/json")
                                .content("{\"name\":\"测试知识库\",\"description\":\"无需模型\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString());
        mvc.perform(
                        post("/api/v1/chat")
                                .contentType("application/json")
                                .content("{\"knowledgeBaseId\":\"1\",\"question\":\"问题\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_NOT_CONFIGURED"));
        mvc.perform(post("/api/v1/chat").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }
}
