package com.boongboongus.backend.domain.auth.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountCleanupRepository {
    private final JdbcClient jdbc;

    AccountCleanupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockActiveAccount(String userId) {
        return jdbc.sql("SELECT id FROM auth_users WHERE id = :id AND deleted_at IS NULL FOR UPDATE")
                .param("id", userId).query(String.class).optional().isPresent();
    }

    public void anonymizeAccount(String userId) {
        update("""
                UPDATE auth_users SET deleted_at = CURRENT_TIMESTAMP, kakao_id = NULL, nickname = '',
                    birth_date = NULL, profile_image_url = NULL, gender = NULL,
                    couple_start_date = NULL, mascot_name = NULL, onboarding_completed = FALSE
                WHERE id = :id
                """, userId);
    }

    public void removePersonalData(String userId) {
        update("DELETE FROM device_push_tokens WHERE user_id = :id", userId);
        update("DELETE FROM card_completion_push_events WHERE recipient_user_id = :id", userId);
        update("DELETE FROM playlist_bookmarks WHERE user_id = :id", userId);
        update("DELETE FROM favorite_places WHERE user_id = :id", userId);
        update("DELETE FROM place_search_history WHERE user_id = :id", userId);
        update("DELETE FROM card_reflections WHERE author_id = :id", userId);
        update("DELETE FROM ai_jobs WHERE kind = 'RADIO_GENERATION' AND owner_user_id = :id", userId);
        update("DELETE FROM notifications WHERE recipient_user_id = :id", userId);
        update("DELETE FROM api_idempotency WHERE user_id = :id", userId);
    }

    public void removeRecordedCoordinates(String userId) {
        update("""
                DELETE FROM drive_gps_samples
                WHERE drive_id IN (SELECT id FROM drives WHERE recorder_user_id = :id)
                """, userId);
        update("""
                UPDATE drives SET initial_coordinate = NULL,
                    gps_purged_at = COALESCE(gps_purged_at, CURRENT_TIMESTAMP)
                WHERE recorder_user_id = :id
                """, userId);
    }

    private void update(String sql, String userId) {
        jdbc.sql(sql).param("id", userId).update();
    }
}
