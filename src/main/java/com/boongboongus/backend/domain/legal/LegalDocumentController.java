package com.boongboongus.backend.domain.legal;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.domain.legal.LegalDocumentResponse.Type;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/legal-documents")
@EnableConfigurationProperties(LegalDocumentProperties.class)
public class LegalDocumentController {

    private final LegalDocumentService documents;

    public LegalDocumentController(LegalDocumentService documents) {
        this.documents = documents;
    }

    @Operation(summary = "현재 약관 문서 목록 조회")
    @GetMapping
    public ApiResponse<List<LegalDocumentResponse>> list() {
        return ApiResponse.success(documents.listCurrent());
    }

    @Operation(summary = "약관 문서 상세 조회")
    @GetMapping("/{type}")
    public ApiResponse<LegalDocumentResponse> get(
            @PathVariable Type type, @RequestParam(required = false) String version) {
        var document = documents.find(type, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ApiResponse.success(document);
    }
}
