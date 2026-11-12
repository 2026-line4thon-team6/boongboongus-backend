package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "홈·장소")
public class HomeExploreController {
    private final HomeExploreService service;

    HomeExploreController(HomeExploreService service) {
        this.service = service;
    }

    @GetMapping("/home")
    @Operation(operationId = "getHome", summary = "홈 프로필·배너·미작성 카드·알림 요약")
    public ApiResponse<HomeExploreService.HomeView> home(Principal principal,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude) {
        return ApiResponse.success(service.home(userId(principal), coordinate(latitude, longitude)));
    }

    @GetMapping("/place-categories")
    @Operation(operationId = "getCategories", summary = "스마트 탐색 카테고리 5종")
    public ApiResponse<List<CategoryView>> categories(Principal principal) {
        userId(principal);
        return ApiResponse.success(Arrays.stream(Category.values())
                .map(category -> new CategoryView(category, category.label)).toList());
    }

    @GetMapping("/places/recommendations")
    @Operation(operationId = "getRecommendations", summary = "카테고리 추천 장소")
    public ApiResponse<HomeExploreService.Recommendations> recommendations(Principal principal,
            @RequestParam Category category, @RequestParam(required = false) String refreshToken,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude) {
        return ApiResponse.success(service.recommendations(userId(principal), category, refreshToken,
                coordinate(latitude, longitude)));
    }

    @GetMapping("/routes/popular")
    @Operation(operationId = "getPopularRoutes", summary = "인기 드라이브 목적지 카드")
    public ApiResponse<HomeExploreService.PopularPage> popular(Principal principal,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude) {
        String userId = userId(principal);
        if (limit < 1 || limit > 100) {
            throw badRequest("limit은 1 이상 100 이하여야 합니다.");
        }
        return ApiResponse.success(service.popular(userId, cursor, limit, coordinate(latitude, longitude)));
    }

    @GetMapping("/places/{placeId}")
    @Operation(operationId = "getPlace", summary = "스마트 탐색 장소 상세")
    public ApiResponse<HomeExploreService.PlaceDetail> detail(Principal principal, @PathVariable String placeId,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude) {
        String userId = userId(principal);
        if (placeId.isBlank() || placeId.length() > 80) {
            throw badRequest("placeId를 확인해 주세요.");
        }
        return ApiResponse.success(service.detail(userId, placeId, coordinate(latitude, longitude)));
    }

    static String userId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new HomeExploreException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        return principal.getName();
    }

    static Coordinate coordinate(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return null;
        }
        if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            throw badRequest("유효한 위도와 경도를 함께 보내 주세요.");
        }
        return new Coordinate(latitude, longitude);
    }

    @ExceptionHandler(HomeExploreException.class)
    ResponseEntity<ErrorResponse> handleError(HomeExploreException exception) {
        ErrorResponse body = ErrorResponse.of(exception.code, exception.getMessage(), List.of());
        if (exception.status == HttpStatus.SERVICE_UNAVAILABLE) {
            body = new ErrorResponse(new ErrorResponse.ErrorDetail(
                    exception.code, exception.getMessage(), List.of(), true), body.meta());
        }
        return ResponseEntity.status(exception.status).body(body);
    }

    @ExceptionHandler(RestClientException.class)
    ResponseEntity<ErrorResponse> handleProviderError() {
        return handleError(new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE", "장소 공급자에 연결할 수 없습니다."));
    }

    record CategoryView(Category code, String name) {
    }
}
