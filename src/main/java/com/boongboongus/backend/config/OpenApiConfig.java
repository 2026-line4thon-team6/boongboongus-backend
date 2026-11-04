package com.boongboongus.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "붕붕어스 API",
        version = "v1",
        description = "구현된 API를 표시합니다. 기획 API 전체 명세와 구분합니다."))
public class OpenApiConfig {
}
