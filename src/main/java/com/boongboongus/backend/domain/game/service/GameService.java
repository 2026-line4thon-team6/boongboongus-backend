package com.boongboongus.backend.domain.game.service;

import com.boongboongus.backend.common.api.ErrorResponse.ErrorDetail;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.game.dto.GameDtos.Create;
import com.boongboongus.backend.domain.game.dto.GameDtos.Input;
import com.boongboongus.backend.domain.game.dto.GameDtos.Question;
import com.boongboongus.backend.domain.game.dto.GameDtos.Response;
import com.boongboongus.backend.domain.game.dto.GameDtos.Session;
import com.boongboongus.backend.domain.game.dto.GameDtos.Status;
import com.boongboongus.backend.domain.game.dto.GameDtos.Type;
import com.boongboongus.backend.domain.game.dto.GameDtos.Version;
import com.boongboongus.backend.domain.game.repository.GameRepository;
import com.boongboongus.backend.domain.game.repository.GameRepository.Pending;
import com.boongboongus.backend.domain.game.repository.GameRepository.State;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import com.boongboongus.backend.domain.job.service.JobService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GameService {
    private static final Logger log = LoggerFactory.getLogger(GameService.class);
    private static final List<String> PLAYABLE = List.of("PREPARING", "ACTIVE", "PAUSED");

    private final GameRepository games;
    private final CurrentCoupleLookup couples;
    private final JobService jobs;
    private final IdempotentWrite idempotency;
    private final TransactionTemplate transaction;

    GameService(GameRepository games, CurrentCoupleLookup couples, JobService jobs,
            IdempotentWrite idempotency, TransactionTemplate transaction) {
        this.games = games;
        this.couples = couples;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.transaction = transaction;
    }

    public JobResponse create(String user, String couple, String key, Create request) {
        return write(user, couple, () -> idempotency.run(user, key, List.of("game-create", couple, request),
                JobResponse.class, () -> {
                    String status = games.driveStatus(couple, user, request.driveId(), true)
                            .orElseThrow(GameService::notFound);
                    if (!PLAYABLE.contains(status)) {
                        throw reject(HttpStatus.CONFLICT, "DRIVE_STATE_CONFLICT", "현재 주행에서 게임을 시작할 수 없습니다.");
                    }
                    JobResponse job = jobs.enqueue(user, JobResponse.Kind.GAME_GENERATION);
                    games.enqueue(job.id(), request);
                    return job;
                }));
    }

    public Session get(String user, String id) {
        String couple = currentCouple(user);
        return write(user, couple, () -> view(state(user, couple, id, true)));
    }

    public Question reveal(String user, String couple, String key, String id, String questionId, boolean answer) {
        return write(user, couple, () -> idempotency.run(user, key,
                List.of("game-reveal", couple, id, questionId, answer), Question.class, () -> {
                    State state = active(user, couple, id);
                    currentQuestion(state, questionId);
                    if (state.type() != Type.NONSENSE) {
                        throw unsupported();
                    }
                    games.reveal(questionId, answer);
                    return games.question(state);
                }));
    }

    public Response save(String user, String couple, String id, String questionId, Input input) {
        return write(user, couple, () -> {
            State state = active(user, couple, id);
            currentQuestion(state, questionId);
            if (state.type() != input.type()) {
                throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "RESPONSE_TYPE_MISMATCH", "응답의 게임 유형을 확인해 주세요.");
            }
            if (state.type() == Type.NONSENSE) {
                throw unsupported();
            }
            if (state.type() == Type.BALANCE ? input.choice() == null || input.selectedRole() != null
                    : input.selectedRole() == null || input.choice() != null) {
                throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "게임 유형에 맞는 응답을 입력해 주세요.");
            }
            var existing = games.responses(questionId).stream().filter(response -> user.equals(response.authorUserId()))
                    .filter(response -> response.choice() == input.choice() && response.selectedRole() == input.selectedRole())
                    .findFirst();
            if (existing.isPresent()) {
                return existing.get();
            }
            var response = new Response(user, input.choice(), input.selectedRole(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            games.saveResponse(questionId, response);
            return response;
        });
    }

    public Session progress(String user, String couple, String key, String id, Version request, Action action) {
        return write(user, couple, () -> idempotency.run(user, key,
                List.of("game-progress", couple, id, request, action), Session.class, () -> {
                    State state = active(user, couple, id);
                    if (state.version() != request.expectedVersion()) {
                        throw reject(HttpStatus.CONFLICT, "VERSION_CONFLICT", "게임을 다시 조회해 주세요.");
                    }
                    int number = state.number();
                    if (action == Action.ABANDON) {
                        games.progress(state, number, Status.ABANDONED);
                    } else {
                        verifyProgress(state, action);
                        games.progress(state, action == Action.NEXT ? number + 1 : number,
                                action == Action.NEXT ? Status.ACTIVE : Status.COMPLETED);
                    }
                    return view(state(user, couple, id, false));
                }));
    }

    private void verifyProgress(State state, Action action) {
        boolean last = state.number() == GameQuestions.COUNT;
        if (last && action == Action.NEXT) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "LAST_QUESTION", "마지막 문항에서는 완료를 호출해 주세요.");
        }
        var question = games.question(state);
        String missingCondition = switch (state.type()) {
            case BALANCE -> games.responses(question.id()).isEmpty() ? "ANSWER_REQUIRED" : null;
            case NONSENSE -> question.answerRevealed() ? null : "ANSWER_NOT_REVEALED";
            case MEMORY -> null;
        };
        if (action == Action.COMPLETE && (!last || missingCondition != null)) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, "GAME_NOT_COMPLETABLE", "아직 게임을 완료할 수 없습니다.");
        }
        if (missingCondition != null) {
            throw reject(HttpStatus.UNPROCESSABLE_CONTENT, missingCondition, "현재 문항의 진행 조건을 충족해 주세요.");
        }
    }

    @Scheduled(fixedDelay = 2000)
    void generateQueued() {
        for (Pending pending : games.pending()) {
            try {
                transaction.executeWithoutResult(status -> generate(pending));
            } catch (RuntimeException exception) {
                log.error("Game job {} could not be generated ({})", pending.jobId(), exception.getClass().getSimpleName());
                transaction.executeWithoutResult(status -> {
                    if (jobs.start(pending.jobId())) {
                        fail(pending, "SERVICE_UNAVAILABLE", "게임을 생성하지 못했습니다.", true);
                    }
                });
            }
        }
    }

    private void generate(Pending pending) {
        if (!jobs.start(pending.jobId())) {
            return;
        }
        boolean current = couples.lockCurrent(pending.owner(), pending.coupleId());
        boolean playable = current && games.driveStatus(pending.coupleId(), pending.owner(), pending.driveId(), true)
                .filter(PLAYABLE::contains).isPresent();
        if (!playable) {
            fail(pending, "DRIVE_STATE_CONFLICT", "현재 주행에서 게임을 생성할 수 없습니다.", false);
            return;
        }
        var questions = switch (pending.type()) {
            case BALANCE -> GameQuestions.balance();
            case NONSENSE -> GameQuestions.nonsense();
            case MEMORY -> GameQuestions.memories(games.completedDriveTitles(pending.coupleId(), pending.owner()));
        };
        if (questions.isEmpty()) {
            fail(pending, "MEMORY_RECORDS_REQUIRED", "추억 게임에 사용할 완료 주행 기록이 없습니다.", false);
            return;
        }
        String id = "game_" + UUID.randomUUID();
        if (jobs.succeed(pending.jobId(), id)) {
            games.create(id, pending, questions);
        }
    }

    private void fail(Pending pending, String code, String message, boolean retryable) {
        jobs.fail(pending.jobId(), new ErrorDetail(code, message, List.of(), retryable));
    }

    private <T> T write(String user, String couple, Supplier<T> action) {
        return transaction.execute(status -> {
            if (!couple.equals(currentCouple(user)) || !couples.lockCurrent(user, couple)) {
                throw reject(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
            }
            return action.get();
        });
    }

    private String currentCouple(String user) {
        return couples.findCurrent(user).map(CurrentCoupleLookup.Couple::id).orElseThrow(() ->
                reject(HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
    }

    private State active(String user, String couple, String id) {
        State state = state(user, couple, id, true);
        if (state.status() != Status.ACTIVE) {
            throw reject(HttpStatus.CONFLICT, "GAME_ALREADY_FINISHED", "이미 종료된 게임입니다.");
        }
        return state;
    }

    private State state(String user, String couple, String id, boolean lock) {
        return games.find(id, couple, user, lock).orElseThrow(GameService::notFound);
    }

    private void currentQuestion(State state, String id) {
        if (!games.question(state).id().equals(id)) {
            throw reject(HttpStatus.CONFLICT, "QUESTION_NOT_CURRENT", "현재 문항을 다시 조회해 주세요.");
        }
    }

    private Session view(State state) {
        Question question = games.question(state);
        return new Session(state.id(), state.driveId(), state.type(), state.status(), GameQuestions.COUNT,
                question, games.responses(question.id()), state.version(), state.createdAt(), state.completedAt());
    }

    private static Rejected notFound() {
        return reject(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "게임 또는 주행을 찾을 수 없습니다.");
    }

    private static Rejected unsupported() {
        return reject(HttpStatus.UNPROCESSABLE_CONTENT, "GAME_ACTION_UNSUPPORTED", "이 게임 유형에서 사용할 수 없는 기능입니다.");
    }

    private static Rejected reject(HttpStatus status, String code, String message) {
        return new Rejected(status, code, message);
    }

    public enum Action { NEXT, COMPLETE, ABANDON }

    public static final class Rejected extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        Rejected(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
