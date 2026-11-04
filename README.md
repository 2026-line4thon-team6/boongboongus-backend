# 붕붕어스 Backend

커플이 함께하는 드라이브와 여행 기록 서비스 **붕붕어스**의 백엔드입니다.

Java 21 · Spring Boot 4.1.2 · Gradle Wrapper 9.8.2 · Spring Data JPA · PostgreSQL 18 · Swagger UI

## 바로 실행

JDK 21과 PostgreSQL이 필요합니다. Docker Desktop을 실행하고 다음 순서로 시작합니다.

```powershell
# Windows PowerShell
Copy-Item .env.example .env
# .env의 DB_PASSWORD 수정
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

```bash
# macOS / Linux
cp .env.example .env
# .env의 DB_PASSWORD 수정
docker compose up -d --wait postgres
chmod +x gradlew
./gradlew bootRun
```

| 주소 | 용도 |
| --- | --- |
| http://localhost:8080/api/v1/health | API 응답 및 공통 응답 형식 확인 |
| http://localhost:8080/actuator/health | 서버·DB 상태 확인 |
| http://localhost:8080/swagger-ui.html | 구현된 API 문서 |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |

현재 구현된 업무 외 엔드포인트는 상태 확인 API입니다. 주행·인증·커플 등 서비스 기능은 이후 도메인별로 구현합니다. Swagger에는 실제 구현된 API만 표시됩니다.

## PostgreSQL 개발 환경

기본 `local`과 `dev`는 PostgreSQL에 연결합니다. 위에서 만든 `.env`와 DB를 그대로 사용하며, `dev` 프로필로 실행할 때는 다음 명령을 사용합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

macOS/Linux에서는 `./gradlew`를 사용합니다. 이미 설치된 PostgreSQL에 연결하려면 Docker 실행을 생략하고 `.env`의 호스트·포트·DB 이름·계정을 해당 서버에 맞춥니다. `.env`는 Spring과 Docker Compose가 읽습니다. 값에 따옴표를 붙이지 않습니다. 실제 비밀값은 Git에 올리지 않습니다.

| 프로필 | 데이터베이스 | 스키마 | Swagger |
| --- | --- | --- | --- |
| `local` (기본) | `.env`의 PostgreSQL | `update` | 켜짐 |
| `dev` | `.env`의 PostgreSQL | `update` | 켜짐 |
| `test` | 기본 H2 PostgreSQL 모드 / CI는 PostgreSQL | 테스트용 생성·삭제 | 켜짐 |
| `prod` | 환경변수의 PostgreSQL | `validate` | 꺼짐 |

H2는 자동 테스트 의존성에만 포함됩니다. PostgreSQL 전용 쿼리·제약은 실제 PostgreSQL에서도 검증합니다. GitHub CI는 PostgreSQL 서비스에 연결해서 테스트합니다. 운영 스키마는 배포 전에 별도 마이그레이션 절차를 정해야 합니다.

PostgreSQL 데이터는 Docker의 `postgres-data` 볼륨에 보존됩니다. PostgreSQL18 공식 이미지의 볼륨 경로는 `/var/lib/postgresql`입니다. 초기 DB·계정·비밀번호 환경변수는 빈 볼륨을 처음 초기화할 때 적용됩니다. [공식 이미지 안내](https://hub.docker.com/_/postgres)를 참고하세요.

운영 환경은 `SPRING_PROFILES_ACTIVE=prod`, `DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `CORS_ALLOWED_ORIGINS`를 설정합니다. `DB_PORT` 기본값은5432, `SERVER_PORT`는8080입니다. DB 세션·JDBC 시간대는 UTC입니다. 프론트 허용 Origin은 쉼표로 구분합니다. 인증·접근 제어는 아직 구현 전이므로 서비스 공개 전에 추가합니다.

## 빌드와 테스트

```powershell
.\gradlew.bat clean build
java -jar build/libs/app.jar
```

로컬 빌드·자동 테스트는 별도 DB 실행 없이 수행할 수 있습니다. 통합 테스트는 상태 확인·DB 연결, 공통 오류, 입력 검증, CORS, OpenAPI를 확인합니다. 결과는 `build/reports/tests/test/index.html`에 생성됩니다. JAR 직접 실행에는 위에서 구성한 PostgreSQL과 `.env`가 필요합니다.

컨테이너 이미지는 `docker build -t boongboongus-backend .`로 만듭니다. 컨테이너 기본 프로필은 `prod`이며 운영 환경변수를 전달해야 합니다. 컨테이너에서 `DB_HOST=localhost`는 DB 컨테이너를 가리키지 않으므로 실제 DB 호스트를 사용합니다.

## 코드 구조와 API 규칙

```text
com.boongboongus.backend
├── common/api        성공·오류 응답
├── common/exception  공통 예외 처리
├── common/web        요청 ID 필터
├── config            CORS·OpenAPI
└── domain/health     상태 확인 API
```

기능 추가 시 `domain/{기능명}` 아래에 controller/service/repository/entity/dto를 필요한 만큼 만듭니다. JSON 성공 응답은 `{data, meta}`, 오류는 `{error, meta}`입니다. `meta`는 `requestId`와 UTC `serverTime`을 포함합니다. 입력 검증 오류는422, 잘못된 JSON은400, 없는 경로는404입니다. `X-Request-Id` 응답 헤더로 요청을 추적합니다.

기본 프론트 Origin은 `http://localhost:3000`, `http://localhost:5173`입니다. 쿠키 인증 대신 향후 Bearer 인증을 연결하는 구성이며, 인증 구현에 맞춰 CORS를 조정합니다.

## GitHub 협업

팀 작업 기준은 [CONTRIBUTING.md](CONTRIBUTING.md), AI 공통 지침은 [AGENTS.md](AGENTS.md)를 참고합니다.

- `main`·`develop` 대상 push/PR: GitHub Actions가 Java21·PostgreSQL18로 `clean build` 실행
- 작업 흐름: 이슈 생성 → 번호가 포함된 작업 브랜치 → PR → 리뷰·CI → 머지 → 이슈 완료 확인
- 브랜치 예시: `feat/12-drive-start`, `fix/34-card-expiration`
- 커밋·PR 제목 예시: `feat: 주행 시작 API 구현 (#12)`
- PR 본문에 `Refs #12`로 이슈 참조. 일반 PR은 `develop` 대상이며 완료 조건 확인 후 이슈 종료
- 기능 개발·버그 수정·일반 작업 이슈 양식과 PR 템플릿 포함

브랜치 보호는 저장소 관리자가 GitHub Settings → Rules → Rulesets에서 별도로 설정합니다. PR 승인1명과 `Build and test` 체크 필수를 권장합니다. 이 저장소 파일만으로 GitHub의 보호 규칙이나 Secrets가 설정되지는 않습니다.
