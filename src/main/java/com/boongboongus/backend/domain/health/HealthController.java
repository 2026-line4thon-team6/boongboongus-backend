package com.boongboongus.backend.domain.health;

import com.boongboongus.backend.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Operation(summary = "API 서버 응답 확인", description = "DB 연결 상태는 /actuator/health에서 확인합니다.")
    @GetMapping
    public ApiResponse<HealthStatus> health() {
        return ApiResponse.success(new HealthStatus("UP"));
    }

    public record HealthStatus(String status) {
    }
}
