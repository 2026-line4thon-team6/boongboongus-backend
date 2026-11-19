package com.boongboongus.backend.domain.game.repository;

import com.boongboongus.backend.domain.game.dto.GameDtos.Choice;
import com.boongboongus.backend.domain.game.dto.GameDtos.Create;
import com.boongboongus.backend.domain.game.dto.GameDtos.Question;
import com.boongboongus.backend.domain.game.dto.GameDtos.Response;
import com.boongboongus.backend.domain.game.dto.GameDtos.Role;
import com.boongboongus.backend.domain.game.dto.GameDtos.Status;
import com.boongboongus.backend.domain.game.dto.GameDtos.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class GameRepository {
    private final JdbcClient jdbc;

    GameRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> driveStatus(String coupleId, String user, String driveId, boolean lock) {
        var sql = """
                SELECT status FROM drives WHERE id = :drive AND couple_id = :couple
                  AND (:user = driver_user_id OR :user = passenger_user_id)
                """;
        return jdbc.sql(lock ? sql + " FOR UPDATE" : sql).param("drive", driveId)
                .param("couple", coupleId).param("user", user).query(String.class).optional();
    }

    public List<String> completedDriveTitles(String coupleId, String user) {
        return jdbc.sql("""
                SELECT title FROM drives WHERE couple_id = :couple AND status = 'ENDED'
                  AND (:user = driver_user_id OR :user = passenger_user_id)
                ORDER BY ended_at DESC, id DESC LIMIT 5
                """).param("couple", coupleId).param("user", user).query(String.class).list();
    }

    public void enqueue(String jobId, Create request) {
        jdbc.sql("""
                INSERT INTO game_generation_requests (job_id, drive_id, game_type)
                VALUES (:job, :drive, :type)
                """).param("job", jobId).param("drive", request.driveId())
                .param("type", request.type().name()).update();
    }

    public List<Pending> pending() {
        return jdbc.sql("""
                SELECT r.job_id, r.drive_id, r.game_type, j.owner_user_id, d.couple_id
                FROM game_generation_requests r JOIN ai_jobs j ON j.id = r.job_id
                JOIN drives d ON d.id = r.drive_id
                WHERE j.status = 'QUEUED' AND j.expires_at > CURRENT_TIMESTAMP
                ORDER BY j.created_at, j.id LIMIT 8
                """).query((row, index) -> new Pending(row.getString("job_id"), row.getString("owner_user_id"),
                        row.getString("couple_id"), row.getString("drive_id"),
                        Type.valueOf(row.getString("game_type")))).list();
    }

    public void create(String id, Pending pending, List<Seed> questions) {
        jdbc.sql("""
                INSERT INTO game_sessions (id, job_id, drive_id, game_type, status, question_number, version, created_at)
                VALUES (:id, :job, :drive, :type, 'ACTIVE', 1, 1, CURRENT_TIMESTAMP)
                """).param("id", id).param("job", pending.jobId()).param("drive", pending.driveId())
                .param("type", pending.type().name()).update();
        for (int index = 0; index < questions.size(); index++) {
            Seed seed = questions.get(index);
            jdbc.sql("""
                    INSERT INTO game_questions (id, session_id, question_number, prompt, option_a, option_b, hint, answer)
                    VALUES (:id, :session, :number, :prompt, :a, :b, :hint, :answer)
                    """).param("id", "qst_" + UUID.randomUUID()).param("session", id).param("number", index + 1)
                    .param("prompt", seed.prompt()).param("a", seed.optionA(), Types.VARCHAR)
                    .param("b", seed.optionB(), Types.VARCHAR).param("hint", seed.hint(), Types.VARCHAR)
                    .param("answer", seed.answer(), Types.VARCHAR).update();
        }
    }

    public Optional<State> find(String id, String coupleId, String user, boolean lock) {
        var sql = """
                SELECT g.* FROM game_sessions g JOIN drives d ON d.id = g.drive_id
                WHERE g.id = :id AND d.couple_id = :couple
                  AND (:user = d.driver_user_id OR :user = d.passenger_user_id)
                """;
        // The drive row lock serializes game changes with drive termination.
        if (lock) {
            var driveId = jdbc.sql(sql).param("id", id).param("couple", coupleId).param("user", user)
                    .query((row, index) -> row.getString("drive_id")).optional();
            if (driveId.isEmpty() || driveStatus(coupleId, user, driveId.get(), true).isEmpty()) {
                return Optional.empty();
            }
        }
        return jdbc.sql(sql).param("id", id).param("couple", coupleId).param("user", user)
                .query((row, index) -> new State(id, row.getString("drive_id"),
                        Type.valueOf(row.getString("game_type")), Status.valueOf(row.getString("status")),
                        row.getInt("question_number"), row.getInt("version"), instant(row, "created_at"),
                        instant(row, "completed_at"))).optional();
    }

    public Question question(State state) {
        return jdbc.sql("""
                SELECT id, question_number, prompt, option_a, option_b, hint_revealed, answer_revealed,
                    CASE WHEN hint_revealed THEN hint END AS visible_hint,
                    CASE WHEN answer_revealed THEN answer END AS visible_answer,
                    CHAR_LENGTH(REPLACE(answer, ' ', '')) AS answer_length
                FROM game_questions WHERE session_id = :session AND question_number = :number
                """).param("session", state.id()).param("number", state.number())
                .query((row, index) -> new Question(row.getString("id"), row.getInt("question_number"),
                        row.getString("prompt"), row.getString("option_a"), row.getString("option_b"),
                        row.getString("visible_hint"), row.getString("visible_answer"),
                        row.getObject("answer_length", Integer.class),
                        row.getBoolean("hint_revealed"), row.getBoolean("answer_revealed"))).single();
    }

    public List<Response> responses(String questionId) {
        return jdbc.sql("""
                SELECT user_id, choice, selected_role, updated_at FROM game_responses
                WHERE question_id = :question ORDER BY user_id
                """).param("question", questionId).query((row, index) -> {
                    String choice = row.getString("choice");
                    String role = row.getString("selected_role");
                    return new Response(row.getString("user_id"), choice == null ? null : Choice.valueOf(choice),
                            role == null ? null : Role.valueOf(role), instant(row, "updated_at"));
                }).list();
    }

    public void reveal(String questionId, boolean answer) {
        String sql = answer ? "UPDATE game_questions SET answer_revealed = TRUE WHERE id = :id"
                : "UPDATE game_questions SET hint_revealed = TRUE WHERE id = :id";
        jdbc.sql(sql).param("id", questionId).update();
    }

    public void saveResponse(String questionId, Response response) {
        String choice = response.choice() == null ? null : response.choice().name();
        String role = response.selectedRole() == null ? null : response.selectedRole().name();
        // The caller holds the drive row lock, so concurrent responses cannot race this update and insert.
        int updated = response("""
                UPDATE game_responses SET choice = :choice, selected_role = :role, updated_at = :time
                WHERE question_id = :question AND user_id = :user
                """, questionId, response, choice, role).update();
        if (updated == 0) {
            response("""
                    INSERT INTO game_responses (question_id, user_id, choice, selected_role, updated_at)
                    VALUES (:question, :user, :choice, :role, :time)
                    """, questionId, response, choice, role).update();
        }
    }

    private JdbcClient.StatementSpec response(String sql, String questionId, Response response, String choice,
            String role) {
        return jdbc.sql(sql).param("question", questionId).param("user", response.authorUserId())
                .param("choice", choice, Types.VARCHAR).param("role", role, Types.VARCHAR)
                .param("time", response.updatedAt().atOffset(ZoneOffset.UTC));
    }

    public void progress(State state, int number, Status status) {
        jdbc.sql("""
                UPDATE game_sessions SET question_number = :number, version = version + 1, status = :status,
                    completed_at = CASE WHEN :status = 'COMPLETED' THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id = :id
                """).param("number", number).param("status", status.name()).param("id", state.id()).update();
    }

    public void abandonForDrive(String driveId) {
        jdbc.sql("""
                UPDATE game_sessions SET status = 'ABANDONED', version = version + 1
                WHERE drive_id = :drive AND status = 'ACTIVE'
                """).param("drive", driveId).update();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime time = row.getObject(column, OffsetDateTime.class);
        return time == null ? null : time.toInstant();
    }

    public record Pending(String jobId, String owner, String coupleId, String driveId, Type type) {
    }

    public record Seed(String prompt, String optionA, String optionB, String hint, String answer) {
    }

    public record State(String id, String driveId, Type type, Status status, int number, int version,
            Instant createdAt, Instant completedAt) {
    }
}
