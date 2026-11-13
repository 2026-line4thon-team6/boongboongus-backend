package com.boongboongus.backend.domain.appconfig;

import com.boongboongus.backend.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/app-config")
@EnableConfigurationProperties(AppConfigResponse.class)
public class AppConfigController {

    private final AppConfigResponse config;

    public AppConfigController(AppConfigResponse config) {
        this.config = config;
    }

    @Operation(summary = "앱 기능·제약·정책 버전 조회")
    @GetMapping
    public ApiResponse<AppConfigResponse> getAppConfig() {
        return ApiResponse.success(config);
    }
}
