package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.RestAreaCatalog.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.ALWAYS)
record RestAreaPage(List<Summary> items, String nextCursor, boolean hasMore) {
    static RestAreaPage slice(List<Summary> areas, String cursor, int limit, String scope) {
        if (limit < 1 || limit > 100) {
            throw new DriveApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "limit은 1~100이어야 합니다.");
        }
        int start = 0;
        String prefix = scope + ":";
        if (cursor != null) {
            String token;
            try {
                if (cursor.length() > 512) {
                    throw new IllegalArgumentException();
                }
                token = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw invalidCursor();
            }
            if (!token.startsWith(prefix)) {
                throw invalidCursor();
            }
            String previousId = token.substring(prefix.length());
            while (start < areas.size() && !areas.get(start).id().equals(previousId)) {
                start++;
            }
            if (start == areas.size()) {
                throw invalidCursor();
            }
            start++;
        }
        int end = Math.min(start + limit, areas.size());
        boolean hasMore = end < areas.size();
        String next = hasMore ? Base64.getUrlEncoder().withoutPadding()
                .encodeToString((prefix + areas.get(end - 1).id()).getBytes(StandardCharsets.UTF_8)) : null;
        return new RestAreaPage(List.copyOf(areas.subList(start, end)), next, hasMore);
    }

    private static DriveApiException invalidCursor() {
        return new DriveApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "유효한 cursor를 보내 주세요.");
    }
}