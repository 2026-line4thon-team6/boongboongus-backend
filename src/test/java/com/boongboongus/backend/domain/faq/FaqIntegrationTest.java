package com.boongboongus.backend.domain.faq;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.config.import=classpath:faqs-test.yml")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FaqIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void listReturnsFaqsInDisplayOrderWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/faqs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("faq-a"))
                .andExpect(jsonPath("$.data[0].question").value("첫 번째 질문"))
                .andExpect(jsonPath("$.data[0].answer").value("첫 번째 답변"))
                .andExpect(jsonPath("$.data[0].order").value(10))
                .andExpect(jsonPath("$.data[1].id").value("faq-b"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }
}
