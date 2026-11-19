package com.boongboongus.backend.domain.media;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final Duration TEMPORARY_LIFETIME = Duration.ofHours(24);
    private static final Duration LINK_LIFETIME = Duration.ofMinutes(10);
    private static final int TICKETS_PER_HOUR = 20;
    private static final long TEMPORARY_BYTES_PER_USER = 200L * 1024 * 1024;
    private static final Set<String> ACCEPTED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final RowMapper<Upload> UPLOAD = (row, index) -> new Upload(
            row.getString("id"), row.getString("user_id"), row.getString("couple_id"),
            State.valueOf(row.getString("status")), row.getString("declared_type"), row.getInt("declared_bytes"),
            row.getObject("expires_at", OffsetDateTime.class).toInstant(), row.getString("sha256"),
            row.getString("storage_path"), row.getString("content_type"), row.getInt("content_bytes"), row.getInt("width"), row.getInt("height"),
            row.getBoolean("cleanup_pending"));

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final IdempotentWrite idempotency;
    private final ObjectProvider<CurrentCoupleLookup> couples;
    private final PhotoStorage files;
    private final MediaLinks links;

    MediaService(JdbcClient jdbc, TransactionTemplate transactions, IdempotentWrite idempotency,
            ObjectProvider<CurrentCoupleLookup> couples, PhotoStorage files, MediaLinks links) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.couples = couples;
        this.files = files;
        this.links = links;
    }

    UploadTicket create(Principal principal, String coupleId, String key, MediaController.UploadRequest request) {
        Actor actor = actor(principal, coupleId);
        if (!ACCEPTED_TYPES.contains(request.contentType())) {
            throw new MediaError(HttpStatus.UNPROCESSABLE_CONTENT, "UNSUPPORTED_MEDIA_TYPE",
                    "JPEG, PNG, WebP 형식의 사진만 올릴 수 있습니다.");
        }
        if (request.bytes() > PhotoStorage.MAX_BYTES) {
            throw MediaError.tooLarge();
        }
        return idempotent(actor, key, List.of("media-upload", coupleId, request), UploadTicket.class,
                () -> reserve(actor, request));
    }

    private UploadTicket reserve(Actor actor, MediaController.UploadRequest request) {
        lockCurrent(actor);
        links.requireConfigured();
        files.requireWritable();
        jdbc.sql("INSERT INTO card_photo_upload_owners (user_id) VALUES (:user) ON CONFLICT DO NOTHING")
                .param("user", actor.user()).update();
        jdbc.sql("SELECT user_id FROM card_photo_upload_owners WHERE user_id = :user FOR UPDATE")
                .param("user", actor.user()).query(String.class).single();

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var usage = jdbc.sql("""
                SELECT COUNT(CASE WHEN created_at > :hour_ago THEN 1 END) AS recent,
                       COALESCE(SUM(CASE WHEN status IN ('UPLOADING', 'READY') AND expires_at > :now
                           THEN declared_bytes ELSE 0 END), 0) AS reserved
                FROM card_photo_uploads WHERE user_id = :user AND (created_at > :hour_ago
                    OR (status IN ('UPLOADING', 'READY') AND expires_at > :now))
                """).param("hour_ago", utc(now.minus(Duration.ofHours(1)))).param("now", utc(now))
                .param("user", actor.user())
                .query((row, index) -> new Usage(row.getLong("recent"), row.getLong("reserved"))).single();
        if (usage.recent() >= TICKETS_PER_HOUR || usage.reserved() + request.bytes() > TEMPORARY_BYTES_PER_USER) {
            throw new MediaError(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "잠시 후 사진 업로드를 다시 시도해 주세요.");
        }

        String id = "med_" + UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO card_photo_uploads
                    (id, user_id, couple_id, storage_path, status, declared_type, declared_bytes, created_at, expires_at)
                VALUES (:id, :user, :couple, :path, 'UPLOADING', :type, :bytes, :created, :expires)
                """).param("id", id).param("user", actor.user()).param("couple", actor.couple())
                .param("path", PhotoStorage.relativePath(id))
                .param("type", request.contentType()).param("bytes", request.bytes()).param("created", utc(now))
                .param("expires", utc(now.plus(TEMPORARY_LIFETIME))).update();
        Instant expiry = now.plus(LINK_LIFETIME);
        return new UploadTicket(id, links.issue("PUT", id, actor.user(), expiry), "PUT",
                Map.of("Content-Type", request.contentType()), expiry, PhotoStorage.MAX_BYTES);
    }

    void put(String id, String token, String contentType, long contentLength, InputStream input) {
        String user = links.verify("PUT", id, token);
        transactions.executeWithoutResult(status -> {
            var upload = upload(id, true);
            ensureOwner(connected(user), upload);
            if (upload.state() != State.UPLOADING || upload.expired()) {
                throw MediaError.notFound();
            }
            if (contentLength > PhotoStorage.MAX_BYTES) {
                throw MediaError.tooLarge();
            }
            if (!upload.declaredType().equalsIgnoreCase(contentType)
                    || (contentLength >= 0 && contentLength != upload.declaredBytes())) {
                throw MediaError.invalidPhoto();
            }
            fileOperation(() -> {
                files.put(id, input, upload.declaredBytes());
                return null;
            });
        });
    }

    Media complete(Principal principal, String coupleId, String id, String key, String hash) {
        Actor actor = actor(principal, coupleId);
        var current = upload(id, false);
        ensureCanComplete(actor, current);
        Media result = idempotent(actor, key, List.of("media-complete", coupleId, id, hash), Media.class,
                () -> finish(actor, id, hash));
        cleanQuietly(id);
        return result;
    }

    private Media finish(Actor actor, String id, String hash) {
        lockCurrent(actor);
        var upload = upload(id, true);
        ensureCanComplete(actor, upload);
        if (upload.state() != State.UPLOADING) {
            if (!hash.equals(upload.sha256())) {
                throw MediaError.invalidPhoto();
            }
            return upload.view();
        }
        var photo = fileOperation(() ->
                files.verify(id, upload.storagePath(), upload.declaredType(), upload.declaredBytes(), hash));
        jdbc.sql("""
                UPDATE card_photo_uploads SET status = 'READY', sha256 = :hash, content_type = :type,
                    content_bytes = :bytes, width = :width, height = :height, cleanup_pending = TRUE
                WHERE id = :id
                """).param("id", id).param("hash", hash).param("type", photo.contentType()).param("bytes", photo.bytes())
                .param("width", photo.width()).param("height", photo.height()).update();
        return new Media(id, State.READY, photo.contentType(), photo.bytes(), photo.width(), photo.height());
    }

    DownloadUrl downloadUrl(Principal principal, String id) {
        Actor actor = actor(principal, null);
        var upload = upload(id, false);
        ensureReadable(actor, upload);
        Instant expiry = Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(LINK_LIFETIME);
        if (upload.state() == State.READY && upload.expiresAt().isBefore(expiry)) {
            expiry = upload.expiresAt();
        }
        return new DownloadUrl(id, links.issue("GET", id, actor.user(), expiry), expiry);
    }

    Download download(String id, String token) {
        String user = links.verify("GET", id, token);
        return transactions.execute(status -> {
            var upload = upload(id, true);
            ensureReadable(connected(user), upload);
            return new Download(upload.contentType(),
                    fileOperation(() -> files.read(upload.storagePath(), upload.contentBytes())));
        });
    }

    void delete(Principal principal, String coupleId, String id) {
        Actor actor = actor(principal, coupleId);
        transactions.executeWithoutResult(status -> {
            lockCurrent(actor);
            var upload = upload(id, true);
            ensureOwner(actor, upload);
            if (upload.state() == State.ATTACHED) {
                throw new MediaError(HttpStatus.CONFLICT, "MEDIA_IN_USE", "카드에 첨부된 사진입니다.");
            }
            if (upload.state() != State.DELETED) {
                markDeleted(id);
            }
        });
        cleanQuietly(id);
    }

    public void deleteForWithdrawnOwner(String userId) {
        jdbc.sql("""
                UPDATE card_photo_uploads
                SET status = 'DELETED', card_id = NULL, card_position = NULL, cleanup_pending = TRUE,
                    sha256 = NULL, content_type = NULL, content_bytes = NULL, width = NULL, height = NULL
                WHERE user_id = :id AND status <> 'DELETED'
                """).param("id", userId).update();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    void cleanupExpired() {
        List<String> pending = jdbc.sql("""
                SELECT id FROM card_photo_uploads WHERE cleanup_pending = TRUE
                    OR (status IN ('UPLOADING', 'READY') AND expires_at <= :now)
                ORDER BY expires_at
                """).param("now", utc(Instant.now())).query(String.class).list();
        for (String id : pending) {
            transactions.executeWithoutResult(status -> {
                var upload = upload(id, true);
                if ((upload.state() == State.UPLOADING || upload.state() == State.READY) && upload.expired()) {
                    markDeleted(id);
                }
            });
            cleanQuietly(id);
        }
    }

    // Call inside the transaction holding the card row lock.
    public boolean replaceCardPhotos(String cardId, String coupleId, String userId, List<String> ids, Instant now) {
        var requested = ids.isEmpty() ? List.of("") : ids;
        var locked = jdbc.sql("""
                SELECT id, user_id, couple_id, status, expires_at, card_id FROM card_photo_uploads
                WHERE card_id = :card OR id IN (:requested) ORDER BY id FOR UPDATE
                """).param("card", cardId).param("requested", requested)
                .query((r, index) -> new Attachment(r.getString("id"), r.getString("user_id"),
                        r.getString("couple_id"), r.getString("status"), r.getString("card_id"),
                        r.getObject("expires_at", OffsetDateTime.class).toInstant())).list();
        var eligible = locked.stream().filter(photo -> photo.couple().equals(coupleId)
                && (cardId.equals(photo.card()) || ("READY".equals(photo.status())
                    && userId.equals(photo.owner()) && photo.expiry().isAfter(now))))
                .map(Attachment::id).collect(java.util.stream.Collectors.toSet());
        if (!eligible.containsAll(ids)) {
            return false;
        }
        for (var photo : locked) {
            if (cardId.equals(photo.card()) && !ids.contains(photo.id())) {
                jdbc.sql("""
                        UPDATE card_photo_uploads SET status = 'DELETED', card_id = NULL, card_position = NULL,
                            cleanup_pending = TRUE WHERE id = :id
                        """).param("id", photo.id()).update();
            }
        }
        for (int position = 0; position < ids.size(); position++) {
            jdbc.sql("""
                    UPDATE card_photo_uploads SET status = 'ATTACHED', card_id = :card, card_position = :position
                    WHERE id = :id
                    """).param("card", cardId).param("position", position).param("id", ids.get(position)).update();
        }
        return true;
    }

    public List<DownloadUrl> cardPhotos(String cardId, String userId) {
        Instant expiry = attachedLinkExpiry();
        return jdbc.sql("SELECT id FROM card_photo_uploads WHERE card_id = :card ORDER BY card_position")
                .param("card", cardId).query(String.class).list().stream()
                .map(id -> attachedLink(id, userId, expiry)).toList();
    }

    public Map<String, String> cardThumbnails(List<String> cardIds, String userId) {
        if (cardIds.isEmpty()) {
            return Map.of();
        }
        Instant expiry = attachedLinkExpiry();
        Map<String, String> urls = new HashMap<>();
        jdbc.sql("""
                SELECT m.card_id, m.id FROM card_photo_uploads m
                JOIN drive_cards c ON c.id = m.card_id JOIN drives d ON d.id = c.drive_id
                WHERE m.card_id IN (:cards) AND m.card_position = 0 AND m.status = 'ATTACHED'
                    AND c.status = 'COMPLETED' AND :user IN (d.driver_user_id, d.passenger_user_id)
                """).param("cards", cardIds).param("user", userId)
                .query((row, index) -> urls.put(row.getString("card_id"),
                        attachedLink(row.getString("id"), userId, expiry).url())).list();
        return urls;
    }

    private static Instant attachedLinkExpiry() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(LINK_LIFETIME);
    }

    private DownloadUrl attachedLink(String id, String userId, Instant expiry) {
        return new DownloadUrl(id, links.issue("GET", id, userId, expiry), expiry);
    }

    public boolean hasCardPhotos(String cardId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM card_photo_uploads WHERE card_id = :card)")
                .param("card", cardId).query(Boolean.class).single();
    }

    private record Attachment(String id, String owner, String couple, String status, String card, Instant expiry) {
    }

    private void markDeleted(String id) {
        jdbc.sql("UPDATE card_photo_uploads SET status = 'DELETED', cleanup_pending = TRUE WHERE id = :id")
                .param("id", id).update();
    }

    private void cleanQuietly(String id) {
        try {
            transactions.executeWithoutResult(status -> {
                var upload = upload(id, true);
                if (upload.cleanupPending()) {
                    try {
                        files.clean(id, upload.storagePath(), upload.state() == State.DELETED);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                    jdbc.sql("UPDATE card_photo_uploads SET cleanup_pending = FALSE WHERE id = :id")
                            .param("id", id).update();
                }
            });
        } catch (RuntimeException error) {
            log.warn("Media cleanup will retry for id={} ({})", id, error.getClass().getSimpleName());
        }
    }

    private Upload upload(String id, boolean lock) {
        String sql = "SELECT * FROM card_photo_uploads WHERE id = :id";
        if (lock) {
            sql += " FOR UPDATE";
        }
        return jdbc.sql(sql).param("id", id).query(UPLOAD).optional().orElseThrow(MediaError::notFound);
    }

    private static void ensureOwner(Actor actor, Upload upload) {
        if (!upload.couple().equals(actor.couple()) || !upload.owner().equals(actor.user())) {
            throw MediaError.notFound();
        }
    }

    private static void ensureCanComplete(Actor actor, Upload upload) {
        ensureOwner(actor, upload);
        if (upload.state() == State.DELETED || (upload.state() != State.ATTACHED && upload.expired())) {
            throw MediaError.notFound();
        }
    }

    private static void ensureReadable(Actor actor, Upload upload) {
        if (!upload.couple().equals(actor.couple())) {
            throw MediaError.notFound();
        }
        if (upload.state() == State.ATTACHED) {
            return;
        }
        ensureOwner(actor, upload);
        if (upload.state() != State.READY || upload.expired()) {
            throw MediaError.notFound();
        }
    }

    private Actor actor(Principal principal, String requestedCouple) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new MediaError(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        Actor actor = connected(principal.getName());
        if (requestedCouple != null && !actor.couple().equals(requestedCouple)) {
            throw new MediaError(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
        }
        return actor;
    }

    private void lockCurrent(Actor actor) {
        if (!coupleLookup().lockCurrent(actor.user(), actor.couple())) {
            throw new MediaError(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
        }
    }

    private CurrentCoupleLookup coupleLookup() {
        CurrentCoupleLookup lookup = couples.getIfAvailable();
        if (lookup == null) {
            throw MediaError.unavailable();
        }
        return lookup;
    }

    private Actor connected(String user) {
        var couple = coupleLookup().findCurrent(user)
                .orElseThrow(() -> new MediaError(HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
        if (!user.equals(couple.firstMemberId()) && !user.equals(couple.secondMemberId())) {
            throw new MediaError(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "이 커플에 접근할 수 없습니다.");
        }
        return new Actor(user, couple.id());
    }

    private <T> T idempotent(Actor actor, String key, List<?> intent, Class<T> type, Supplier<T> action) {
        try {
            return idempotency.run(actor.user(), key, intent, type, action);
        } catch (IdempotentWrite.KeyReused error) {
            throw new MediaError(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "요청 키가 다른 요청에 사용되었습니다.");
        }
    }

    private static <T> T fileOperation(FileOperation<T> operation) {
        try {
            return operation.run();
        } catch (IOException error) {
            log.warn("Media storage operation failed ({})", error.getClass().getSimpleName());
            throw MediaError.unavailable();
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private interface FileOperation<T> {
        T run() throws IOException;
    }

    private record Actor(String user, String couple) {
    }

    private record Usage(long recent, long reserved) {
    }

    private record Upload(String id, String owner, String couple, State state, String declaredType, int declaredBytes,
            Instant expiresAt, String sha256, String storagePath, String contentType, int contentBytes, int width, int height,
            boolean cleanupPending) {
        boolean expired() {
            return !expiresAt.isAfter(Instant.now());
        }

        Media view() {
            return new Media(id, state, contentType, contentBytes, width, height);
        }
    }

    enum State {
        UPLOADING, READY, ATTACHED, DELETED
    }

    record UploadTicket(String mediaId, String uploadUrl, String method, Map<String, String> requiredHeaders,
            Instant expiresAt, int maxBytes) {
    }

    record Media(String id, State status, String contentType, int bytes, int width, int height) {
    }

    public record DownloadUrl(String mediaId, String url, Instant expiresAt) {
    }

    record Download(String contentType, byte[] bytes) {
    }

    static final class MediaError extends RuntimeException {
        final HttpStatus status;
        final String code;

        MediaError(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        static MediaError invalidPhoto() {
            return new MediaError(HttpStatus.UNPROCESSABLE_CONTENT, "MEDIA_VALIDATION_FAILED", "사진 파일을 확인해 주세요.");
        }

        static MediaError notFound() {
            return new MediaError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "사진을 찾을 수 없습니다.");
        }

        static MediaError notFinished() {
            return new MediaError(HttpStatus.CONFLICT, "UPLOAD_NOT_FINISHED", "사진 파일을 먼저 올려 주세요.");
        }

        static MediaError tooLarge() {
            return new MediaError(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "사진은 최대 10MiB까지 올릴 수 있습니다.");
        }

        static MediaError unavailable() {
            return new MediaError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "사진을 지금 처리할 수 없습니다.");
        }
    }
}
