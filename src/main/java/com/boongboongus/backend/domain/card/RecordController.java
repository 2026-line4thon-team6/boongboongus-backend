package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.card.CardService.CardError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "카드·기록")
@SecurityRequirement(name = "bearerAuth")
class RecordController {
    private final RecordService records;

    RecordController(RecordService records) {
        this.records = records;
    }

    @GetMapping("/records")
    @Operation(operationId = "getRecords")
    ResponseEntity<ApiResponse<RecordService.Records>> list(Principal principal,
            @RequestParam(required = false) String q, @RequestParam(required = false) String date,
            @RequestParam(required = false) String fromDate, @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return privateResponse(records.list(principal, new RecordService.Query(q, date, fromDate, toDate, cursor, limit)));
    }

    @GetMapping("/records/map")
    @Operation(operationId = "getRecordMap")
    ResponseEntity<ApiResponse<RecordService.RecordMap>> map(Principal principal,
            @RequestParam(required = false) String q, @RequestParam(required = false) String date,
            @RequestParam(required = false) String fromDate, @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String bbox) {
        return privateResponse(records.map(principal, new RecordService.Query(q, date, fromDate, toDate, cursor, limit), bbox));
    }

    @GetMapping("/records/calendar")
    @Operation(operationId = "getRecordCalendar")
    ResponseEntity<ApiResponse<RecordService.Calendar>> calendar(Principal principal, @RequestParam String month) {
        return privateResponse(records.calendar(principal, month));
    }

    @GetMapping("/cards/{cardId}/share")
    @Operation(operationId = "getCardShareLink")
    ResponseEntity<ApiResponse<RecordService.ShareLink>> share(Principal principal, @PathVariable String cardId) {
        return privateResponse(records.share(principal, cardId));
    }

    @ExceptionHandler(CardError.class)
    ResponseEntity<ErrorResponse> error(CardError error) {
        return ResponseEntity.status(error.status).body(ErrorResponse.of(error.code, error.getMessage(), List.of()));
    }

    private static <T> ResponseEntity<ApiResponse<T>> privateResponse(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
