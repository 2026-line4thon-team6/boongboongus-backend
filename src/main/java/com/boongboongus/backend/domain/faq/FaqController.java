package com.boongboongus.backend.domain.faq;

import com.boongboongus.backend.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/faqs")
@EnableConfigurationProperties(FaqProperties.class)
public class FaqController {

    private final List<FaqResponse> items;

    public FaqController(FaqProperties properties) {
        this.items = properties.items().stream()
                .sorted(Comparator.comparingInt(FaqResponse::order))
                .toList();
    }

    @Operation(summary = "FAQ 목록 조회")
    @GetMapping
    public ApiResponse<List<FaqResponse>> list() {
        return ApiResponse.success(items);
    }
}
