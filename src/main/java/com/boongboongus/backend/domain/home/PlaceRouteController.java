package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreController.userId;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateId;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateKey;

import com.boongboongus.backend.common.api.ApiResponse;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/routes")
@Tag(name = "홈·장소")
class PlaceRouteController {
    private final PlaceRouteService routes;

    PlaceRouteController(PlaceRouteService routes) {
        this.routes = routes;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createRoute", summary = "자동차 경로 생성")
    ApiResponse<PlaceRouteService.RouteView> create(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RouteRequest request) {
        validateKey(key);
        return ApiResponse.success(routes.create(userId(principal), key, request));
    }

    @GetMapping("/{routeId}")
    @Operation(operationId = "getRoute", summary = "자동차 경로 조회")
    ApiResponse<PlaceRouteService.RouteView> get(Principal principal, @PathVariable String routeId) {
        validateId(routeId);
        return ApiResponse.success(routes.get(userId(principal), routeId));
    }

    record RouteRequest(@NotNull @Valid Endpoint origin, @NotNull @Valid Endpoint destination,
            @Size(max = 3) List<@NotNull @Valid Endpoint> waypoints, PlaceRouteService.Preference preference) {
        @JsonAnySetter
        void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown route field");
        }
    }

    record Endpoint(@JsonProperty(value = "placeId", required = true) @Size(min = 1, max = 80) String placeId,
            @NotNull String name, @NotNull String address, @NotNull @Valid Point coordinate) {
        @JsonAnySetter
        void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown route endpoint field");
        }
    }

    record Point(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {
        @JsonAnySetter
        void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown coordinate field");
        }
    }
}
