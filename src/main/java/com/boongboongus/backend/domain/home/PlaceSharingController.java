package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreController.userId;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateId;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateKey;
import static com.boongboongus.backend.domain.home.PlaceRequests.validateLimit;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "홈·장소")
class PlaceSharingController {
    private final PlaceSharingService service;

    PlaceSharingController(PlaceSharingService service) {
        this.service = service;
    }

    @GetMapping("/me/favorite-places")
    @Operation(operationId = "getFavorites", summary = "내 찜 장소 조회")
    ApiResponse<HomeExploreService.PopularPage> favorites(Principal principal,
            @RequestParam(required = false) Category category, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        validateLimit(limit);
        return ApiResponse.success(service.favorites(userId(principal), category, cursor, limit));
    }

    @PutMapping("/me/favorite-places/{placeId}")
    @Operation(operationId = "favoritePlace", summary = "장소 찜 추가")
    ApiResponse<Accepted> favorite(Principal principal, @PathVariable String placeId) {
        validateId(placeId);
        service.favorite(userId(principal), placeId);
        return ApiResponse.success(new Accepted(true));
    }

    @DeleteMapping("/me/favorite-places/{placeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "unfavoritePlace", summary = "장소 찜 해제")
    void unfavorite(Principal principal, @PathVariable String placeId) {
        validateId(placeId);
        service.unfavorite(userId(principal), placeId);
    }

    @GetMapping("/places/{placeId}/share")
    @Operation(operationId = "getPlaceShareLink", summary = "장소 공유용 공개 링크 조회")
    ApiResponse<PlaceSharingService.ShareLink> link(Principal principal, @PathVariable String placeId) {
        userId(principal);
        validateId(placeId);
        return ApiResponse.success(service.shareLink(placeId));
    }

    @GetMapping("/public/places/{placeId}")
    @Operation(operationId = "getPublicPlace", summary = "공개 장소 조회")
    ApiResponse<PlaceSharingService.PublicPlace> publicPlace(@PathVariable String placeId) {
        validateId(placeId);
        return ApiResponse.success(service.publicPlace(placeId));
    }

    @PostMapping("/places/{placeId}/send-to-partner")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "sendPlaceToPartner", summary = "현재 커플 상대에게 장소 보내기")
    ApiResponse<PlaceSharingService.SentPlace> send(Principal principal, @PathVariable String placeId,
            @RequestHeader("X-Couple-Id") String coupleId, @RequestHeader("Idempotency-Key") String key) {
        validateId(placeId);
        validateId(coupleId);
        validateKey(key);
        return ApiResponse.success(service.send(userId(principal), coupleId, key, placeId));
    }

    record Accepted(boolean accepted) {
    }
}
