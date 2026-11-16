package com.boongboongus.backend.domain.legal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.config.import=classpath:legal-documents-test.yml")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalDocumentIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void listReturnsCurrentDocumentsWithContentAndRequiredFlags() throws Exception {
        mvc.perform(get("/api/v1/legal-documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].type").value("TERMS"))
                .andExpect(jsonPath("$.data[0].version").value("v2"))
                .andExpect(jsonPath("$.data[0].title").value("현재 약관"))
                .andExpect(jsonPath("$.data[0].url").value("https://example.invalid/legal/terms/v2"))
                .andExpect(jsonPath("$.data[0].content").value("테스트용 현재 본문"))
                .andExpect(jsonPath("$.data[0].effectiveDate").value("2001-01-01"))
                .andExpect(jsonPath("$.data[0].required").value(true))
                .andExpect(jsonPath("$.data[1].type").value("PRIVACY"))
                .andExpect(jsonPath("$.data[1].required").value(true))
                .andExpect(jsonPath("$.data[2].type").value("OPEN_SOURCE"))
                .andExpect(jsonPath("$.data[2].required").value(false))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());
    }

    @Test
    void detailReturnsCurrentOrRequestedHistoricalVersion() throws Exception {
        mvc.perform(get("/api/v1/legal-documents/TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("v2"));
        mvc.perform(get("/api/v1/legal-documents/TERMS").param("version", "v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("테스트용 이전 본문"));
    }

    @Test
    void detailRejectsMissingVersionsAndInvalidTypes() throws Exception {
        mvc.perform(get("/api/v1/legal-documents/TERMS").param("version", "unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/legal-documents/UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }
}
