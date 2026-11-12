package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreController.coordinate;
import static com.boongboongus.backend.domain.home.HomeExploreController.userId;
import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateId;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateKey;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateLimit;

import com.boongboongus.backend.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "홈·장소")
class PlaceSearchController {
    private final PlaceSearchService places;
    private final PlaceSearchHistory history;

    PlaceSearchController(PlaceSearchService places, PlaceSearchHistory history) {
        this.places = places;
        this.history = history;
    }

    @GetMapping("/places")
    @Operation(operationId = "searchPlaces", summary = "목적지 검색")
    ApiResponse<PlaceSearchService.SearchPage> search(Principal principal, @RequestParam String q,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        if (q.isBlank() || q.length() > 100) {
            throw badRequest("검색어를 1자 이상 100자 이하로 보내 주세요.");
        }
        validateLimit(limit);
        return ApiResponse.success(places.search(userId(principal), q.strip(), coordinate(latitude, longitude),
                cursor, limit));
    }

    @GetMapping("/me/search-history")
    @Operation(operationId = "getSearchHistory", summary = "내 최근 검색어 조회")
    ApiResponse<List<PlaceSearchHistory.Entry>> history(Principal principal) {
        return ApiResponse.success(history.list(userId(principal)));
    }

    @PostMapping("/me/search-history")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "saveSearchHistory", summary = "최근 검색어 저장")
    ApiResponse<PlaceSearchHistory.Entry> save(Principal principal, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody SearchHistoryRequest request) {
        validateKey(key);
        return ApiResponse.success(history.save(userId(principal), key, request.query().strip()));
    }

    @DeleteMapping("/me/search-history/{historyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteSearchHistory", summary = "내 최근 검색어 개별 삭제")
    void delete(Principal principal, @PathVariable String historyId) {
        validateId(historyId);
        history.delete(userId(principal), historyId);
    }

    @GetMapping("/locations/reverse-geocode")
    @Operation(operationId = "reverseGeocode", summary = "현재 좌표를 주소로 변환")
    ApiResponse<PlaceSearchService.LocationAddress> reverseGeocode(Principal principal, @RequestParam double latitude,
            @RequestParam double longitude) {
        userId(principal);
        return ApiResponse.success(places.reverseGeocode(coordinate(latitude, longitude)));
    }

    record SearchHistoryRequest(@NotBlank @Size(max = 100) String query) {
    }
}
