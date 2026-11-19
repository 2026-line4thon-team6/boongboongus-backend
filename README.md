# 붕붕어스 백엔드

커플이 함께 달리고, 휴게소 스탬프를 모으고, 사진과 소감으로 드라이브를 기록하는 웹앱의 REST API입니다.

[웹앱](https://2026-line4thon-team6-fe.vercel.app/) · [프론트엔드 저장소](https://github.com/2026-line4thon-team6/2026-line4thon-team6-FE) · [OpenAPI 명세](docs/api/openapi.yaml) · [협업 규칙](CONTRIBUTING.md)

## 서비스 구성

![Vercel 웹앱, AWS EC2의 Spring Boot 서버, PostgreSQL, EBS 및 외부 API 연결 구성](docs/images/service-architecture.png)

하나의 Spring Boot 애플리케이션에서 도메인별 API를 제공합니다. 회원·주행·스탬프·카드와 사진 메타데이터는 PostgreSQL에, 카드 사진 파일은 EC2에 연결한 EBS에 저장합니다. 그림에서 PostgreSQL은 애플리케이션과의 연결만 표시합니다.

**기술 스택:** Java 21 · Spring Boot 4.1.2 · Spring Data JPA/JDBC · PostgreSQL 18 · Flyway · Gradle Wrapper 9.8.2 · Docker

## 주요 기능

| 기능 | 제공 범위 |
| --- | --- |
| 인증·커플 | 카카오 로그인, 회원 프로필·탈퇴, 초대 코드, 커플 연결·해제·복구, 인앱 알림 |
| 홈·장소 | 날씨, 추천 장소, 키워드 검색, 주소 변환, 주변 주차장, 장소 찜·공유 |
| 경로·주행 | 자동차 경로와 교통 요약, 주행 시작·일시정지·종료, GPS 기록과 예상 도착시간 갱신 |
| 휴게소·스탬프 | 경로 주변 휴게소, 메뉴 상세, 접근 알림, GPS 방문 검증 및 도장판 |
| 카드·기록 | 사진 업로드, 커플 소감, 카드 작성·공유, 기록 목록·지도·캘린더 |
| 드라이브 콘텐츠 | DJ 플레이리스트 추천·보관, AI 라디오 대본·음성, 커플 게임 |

## 로컬 실행

JDK 21과 Docker Compose가 필요합니다. 저장소 루트에서 [`.env.example`](.env.example)을 `.env`로 복사하고 `DB_PASSWORD`를 설정한 뒤 실행합니다. 이미 `.env`가 있다면 필요한 값만 수정합니다.

**Windows PowerShell**

```powershell
Copy-Item .env.example .env
# .env에 DB_PASSWORD 설정
docker compose up -d --wait postgres
.\gradlew.bat bootRun
```

**macOS / Linux**

```bash
cp .env.example .env
# .env에 DB_PASSWORD 설정
docker compose up -d --wait postgres
./gradlew bootRun
```

기본 프로필은 `local`이고 서버 포트는 `8080`입니다. 기존 PostgreSQL을 사용하려면 Compose 실행을 생략하고 `.env`의 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`를 맞춥니다. Compose의 PostgreSQL 데이터는 `postgres-data` 볼륨에 보존되며, 초기 계정 설정은 빈 볼륨을 처음 시작할 때 적용됩니다.

| 주소 | 용도 |
| --- | --- |
| [`/api/v1/health`](http://localhost:8080/api/v1/health) · [`/actuator/health`](http://localhost:8080/actuator/health) | API 응답 및 서버 상태 확인 |
| [`/api/v1/app-config`](http://localhost:8080/api/v1/app-config) | 앱 정책, 제한값, 기능 플래그 확인 |
| [`/swagger-ui.html`](http://localhost:8080/swagger-ui.html) · [`/v3/api-docs`](http://localhost:8080/v3/api-docs) | 실행 중인 서버의 API 문서와 OpenAPI JSON |

### 기능별 환경 변수

기능을 확인할 때 필요한 항목만 설정합니다. 실제 키와 비밀번호는 `.env`나 배포 환경에 보관합니다.

### 붕붕이 이름 변경

`PATCH /api/v1/couples/me/mascot-name`에 Bearer 토큰, `X-Couple-Id`, 본문의 `name`과 `expectedVersion`을 보냅니다. 연결된 두 멤버가 수정할 수 있고, 이름은 앞뒤 공백을 제외하고 1~20자입니다. 시작일 수정과 같은 커플 버전을 사용합니다. 성공 시 바뀐 이름과 증가한 버전을 반환하며, 버전이 다르면 `409 VERSION_CONFLICT`를 반환합니다.

### 초대 코드 폐기와 기기 푸시 등록

- `DELETE /api/v1/couple-invites/{inviteId}`는 내 초대 코드의 효력을 없애고 `204`를 반환합니다. 이미 연결에 사용한 코드는 `409 INVITE_ALREADY_USED`입니다. 폐기 후 새 코드를 발급할 때 새 `Idempotency-Key`를 사용합니다.
- `PUT /api/v1/devices/{deviceId}/push-token`에 `token`, `platform`(IOS/ANDROID/WEB), `enabled`를 보냅니다. 동일 기기에서 계정이 바뀌면 등록이 현재 계정으로 이동합니다. 성공 응답은 `200`과 `accepted: true`입니다.
- `DELETE /api/v1/devices/{deviceId}/push-token`은 내 등록만 삭제합니다. 다른 계정에 등록됐거나 등록이 없으면 `204`를 반환합니다. 세 API 모두 Bearer 토큰이 필요합니다.

### 인앱 알림 조회와 읽음 처리

`GET /api/v1/notifications`는 Bearer 토큰으로 본인의 미만료 알림을 최신순으로 반환합니다. `limit` 기본값은 20이며 1~100을 지원합니다. `unreadOnly=true`로 읽지 않은 항목만 조회할 수 있습니다. 다음 페이지에는 응답의 `nextCursor`와 같은 조회 조건을 사용합니다. 알림의 보관 기간은 30일이며 만료 항목을 정리합니다.

`PATCH /api/v1/notifications/{notificationId}/read`는 요청 본문 없이 내 알림을 읽음 처리하고 갱신한 항목을 반환합니다. 이미 읽은 알림의 `readAt`은 유지합니다. 다른 회원의 알림이나 만료된 알림은 `404`입니다. 장소 공유, 커플 연결·복구·해제 시 상대 회원의 인앱 알림을 저장합니다. 홈의 미확인 알림 수에는 읽지 않은 미만료 알림만 포함됩니다. 소감 제출과 실제 푸시 전달은 해당 기능 연동 시 추가합니다.

### AI 생성 작업 조회·취소

`GET /api/v1/jobs/{jobId}`로 로그인한 본인의 생성 상태와 결과 ID를 조회합니다. 진행 중에는 `retryAfterSeconds` 뒤에 다시 조회하고, 종료 상태에서는 조회를 중단합니다. 만료된 작업 조회는 `410 JOB_EXPIRED`를 반환합니다.

`POST /api/v1/jobs/{jobId}/cancel`은 Bearer 토큰과 `Idempotency-Key`(16~128자)를 받습니다. 대기·실행 중인 작업은 `CANCEL_REQUESTED`가 되고 서버가 `CANCELLED`로 마무리합니다. 성공·실패가 먼저 반영된 작업에는 `409 JOB_ALREADY_FINISHED`를 반환합니다. DJ·라디오·게임 생성 작업은 각 기능에서 접수하고 결과를 저장합니다. 생성 작업의 조회 유효기간은 24시간으로 설정했습니다.

### 커플 게임

`POST /api/v1/game-sessions`는 Bearer 토큰, `X-Couple-Id`, `Idempotency-Key`와 `driveId`, `type`을 받습니다. 작업이 성공하면 `resourceId`로 `GET /api/v1/game-sessions/{gameSessionId}`를 호출합니다. 세트는 5문항이며 밸런스는 최소 한 명의 응답, 넌센스는 정답 공개 뒤 진행할 수 있습니다. 추억 게임은 응답 없이도 진행할 수 있습니다. 마지막 문항에서는 `complete`를 사용합니다.

밸런스·넌센스는 서버 문항 목록을 사용합니다. 추억 게임은 현재 커플 구성원이 참여한 종료(`ENDED`) 주행의 제목을 사용하며 기록이 없으면 작업이 `MEMORY_RECORDS_REQUIRED`로 실패합니다. GPS 주행을 종료하거나 준비 주행을 취소하면 진행 중인 게임은 `ABANDONED`가 됩니다.

### 휴게소 대표메뉴

`GET /api/v1/rest-areas/{restAreaId}`는 DB에 저장된 메뉴 이름을 최대 4개 반환합니다. 자동 갱신 시 대표메뉴를 먼저 담고, 남은 자리를 추천메뉴와 일반 메뉴 순으로 채웁니다. 중복 이름은 한 번만 담고, 메뉴가 4개 미만이면 있는 만큼만 반환합니다. 가격과 사진은 저장하지 않습니다. Flyway가 초기 대표메뉴를 넣습니다.

[도로공사 인증키 발급](https://data.ex.co.kr/openapi/apikey/requestKey)에서 이메일로 정식 키를 받아 로컬 `.env` 또는 EC2의 Docker Compose `app.env`에 `EX_REST_AREA_API_KEY`로 설정하고 앱을 다시 시작합니다. 배포 Compose에서 이 값을 백엔드 컨테이너 환경변수로 전달해야 합니다. [푸드메뉴현황 API](https://www.data.go.kr/data/15076643/openapi.do)에서 시작 약 10초 후 첫 갱신을 요청하고 24시간마다 다시 받습니다. 응답을 모두 받은 뒤 노선과 방향이 일치하는 휴게소의 메뉴를 갱신합니다. 키가 없거나 공급자 요청이 실패하면 저장된 메뉴를 계속 제공합니다.

## PostgreSQL 개발 환경

카카오 로그인을 시작하려면 서버의 `.env` 또는 환경변수에 `KAKAO_LOGIN_REST_API_KEY`와 `KAKAO_LOGIN_REDIRECT_URIS`를 설정합니다. 콜백 주소는 카카오디벨로퍼스에 등록한 값과 정확히 일치해야 하며, 여러 주소는 쉼표로 구분합니다. 요청에는 `provider=KAKAO`, `purpose=LOGIN`, `redirectUri`와 앱이 생성한 S256 `codeChallenge`(43자)를 보냅니다. 성공하면 `201`과 카카오 로그인 URL, `authSessionId`, `state`, 10분 후의 만료 시각을 반환합니다. Flyway는 로그인 시도를 저장할 `auth_sessions` 테이블을 생성합니다. 토큰 교환, 만료 세션 정리와 로그인 시작 요청의 호출 제한은 후속 작업입니다.

기본 `local`과 `dev`는 PostgreSQL에 연결합니다. 위에서 만든 `.env`와 DB를 그대로 사용하며, `dev` 프로필로 실행할 때는 다음 명령을 사용합니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=dev'
```

macOS/Linux에서는 `./gradlew`를 사용합니다. 이미 설치된 PostgreSQL에 연결하려면 Docker 실행을 생략하고 `.env`의 호스트·포트·DB 이름·계정을 해당 서버에 맞춥니다. `.env`는 Spring과 Docker Compose가 읽습니다. 값에 따옴표를 붙이지 않습니다. 실제 비밀값은 Git에 올리지 않습니다.

| 프로필 | 데이터베이스 | 스키마 | Swagger |
| --- | --- | --- | --- |
| `local` (기본) | `.env`의 PostgreSQL | `validate` | 켜짐 |
| `dev` | `.env`의 PostgreSQL | `validate` | 켜짐 |
| `test` | 기본 H2 PostgreSQL 모드 / CI는 PostgreSQL | `validate` | 켜짐 |
| `prod` | 환경변수의 PostgreSQL | `validate` | 꺼짐 |

H2는 자동 테스트 의존성에만 포함됩니다. 애플리케이션 시작 시 Flyway가 `src/main/resources/db/migration`의 SQL을 버전 순서대로 적용합니다. 첫 마이그레이션은 `drives`와 `drive_idempotency`를 생성합니다. 운영 배포 전에는 신규 스키마와 마이그레이션 계정 권한을 확인합니다. GitHub CI는 PostgreSQL 서비스에 연결해서 테스트합니다.

### 주행 준비 API

- `POST /api/v1/drives`는 주행 준비 세션을 생성합니다. 커플당 준비·주행·일시중단 상태를 합쳐 한 건만 저장할 수 있습니다.
- `GET /api/v1/drives/active`는 현재 커플의 진행 가능한 세션을 조회합니다. 해당 세션이 없으면 `data.drive`가 `null`입니다.
- `POST /api/v1/drives/{driveId}/cancel`은 `expectedVersion`이 일치하는 준비 세션을 취소합니다.

생성·취소에는 `Idempotency-Key`와 `X-Couple-Id`가 필요합니다. 동일 사용자와 커플이 24시간 안에 같은 키로 같은 요청을 다시 보내면 원래 응답 데이터를 돌려줍니다. 키가 만료되면 새 요청으로 처리합니다.

인증 모듈은 서블릿 `Principal`에 검증한 서비스 사용자 ID를 설정하고, 커플 모듈은 `CurrentCoupleLookup`으로 현재 연결과 두 멤버를 제공해야 합니다. 경로를 지정하는 준비 요청은 `DriveRouteLookup`이 제공하는 접근 가능한 경로를 사용합니다. 현재 통합 테스트는 서버 측 테스트 구현으로 각 연동을 검증합니다.
| 기능 | 설정 |
| --- | --- |
| 카카오 로그인 | `KAKAO_LOGIN_REST_API_KEY`, `KAKAO_LOGIN_REDIRECT_URIS`, `AUTH_TOKEN_KEY`. Client Secret을 사용하면 `KAKAO_LOGIN_CLIENT_SECRET`, 카카오 연결 해제에는 `KAKAO_LOGIN_ADMIN_KEY`가 필요합니다. |
| 장소 검색·길찾기 | 카카오 로컬 `KAKAO_LOCAL_REST_API_KEY`, 카카오모빌리티 `KAKAO_MOBILITY_REST_API_KEY` |
| 홈 날씨 | 기상청 `KMA_SERVICE_KEY` |
| 휴게소 메뉴 자동 갱신 | [한국도로공사 인증키](https://data.ex.co.kr/openapi/apikey/requestKey)를 `EX_REST_AREA_API_KEY`에 설정 |
| 사진 저장·공유 | `MEDIA_STORAGE_ROOT`, `MEDIA_PUBLIC_BASE_URL`, `MEDIA_SIGNING_KEY`. 장소·카드 공유 링크는 `PLACE_SHARE_BASE_URL`, `CARD_SHARE_BASE_URL`로 설정합니다. |
| DJ·AI 라디오 | `GROQ_API_KEY`, `SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`, `ELEVENLABS_API_KEY` |

- 카카오 콜백 주소는 카카오 개발자 콘솔에 등록한 주소와 정확히 일치해야 합니다. `AUTH_TOKEN_KEY`는 무작위 32바이트 이상을 Base64로 인코딩한 값입니다. 약관과 FAQ는 [약관 설정](src/main/resources/legal-documents.yml)과 [FAQ 설정](src/main/resources/faqs.yml)에서 관리합니다. 주행 시작에는 기록 사용자의 `PRIVACY v1` 동의가 필요합니다.
- 휴게소 메뉴는 Flyway가 넣은 초기 데이터로도 조회할 수 있습니다. 키를 설정하면 앱 시작 약 10초 뒤와 이후 24시간마다 도로공사 데이터를 가져옵니다. 대표 → 추천 → 일반 순으로 이름을 최대 4개 저장하며, 조회 실패 시 저장된 메뉴를 유지합니다.
- 운영 사진 저장 디렉터리는 EBS에 만들고 앱 실행 계정에 쓰기 권한을 부여합니다. 컨테이너에 마운트한 경로를 `MEDIA_STORAGE_ROOT`로 설정합니다. `MEDIA_PUBLIC_BASE_URL`은 브라우저에서 접근할 수 있는 API 기본 주소이고, `MEDIA_SIGNING_KEY`는 무작위 32바이트 이상의 서명 키입니다.

## API 문서

[OpenAPI YAML](docs/api/openapi.yaml)이 프론트엔드와 공유하는 설계 계약의 원본입니다. [문서 뷰어](docs/api/index.html)를 로컬 브라우저에서 열면 **로컬 YAML 열기**로 같은 폴더의 `openapi.yaml`을 선택할 수 있습니다. 뷰어가 Swagger UI를 불러오려면 인터넷 연결이 필요합니다.

실제로 구현된 엔드포인트는 실행 중인 로컬 서버의 Swagger UI에서 확인합니다. 운영에서는 Swagger UI와 런타임 OpenAPI를 비활성화합니다. API 수정 시 구현과 OpenAPI 계약을 함께 갱신합니다.

- 공통 경로 접두사는 `/api/v1`입니다. 보호된 API는 `Authorization: Bearer <accessToken>`을 사용합니다.
- 성공 응답은 `{data, meta}`, 오류 응답은 `{error, meta}`입니다. `204` 응답에는 본문이 없습니다.
- 요청 추적에는 `X-Request-Id` 또는 `meta.requestId`를 사용합니다. `X-Couple-Id`, `Idempotency-Key` 등의 필수 조건은 엔드포인트별 명세에 따릅니다.

## 빌드와 배포

```powershell
.\gradlew.bat clean build
```

macOS/Linux에서는 `./gradlew clean build`를 실행합니다. 로컬 자동 테스트는 기본 H2를 사용하고, [GitHub Actions CI](.github/workflows/ci.yml)는 실제 PostgreSQL 18로 빌드와 테스트를 실행합니다. 테스트 보고서는 `build/reports/tests/test/index.html`에서 확인할 수 있습니다.

`main` 빌드가 통과하면 [배포 워크플로](.github/workflows/deploy.yml)가 Docker 이미지를 ECR에 올리고, AWS Systems Manager(SSM)로 [EC2 배포 스크립트](scripts/deploy-ec2.sh)를 실행합니다. EC2는 새 이미지를 받아 Docker Compose로 앱을 교체한 뒤 상태 확인을 수행합니다.

운영은 `prod` 프로필을 사용합니다. EC2의 `app.env`에서 DB 접속 정보, `CORS_ALLOWED_ORIGINS`, 외부 API 키를 Compose를 통해 앱 컨테이너에 전달합니다. Flyway는 앱 시작 시 [마이그레이션](src/main/resources/db/migration)을 적용하며, 기존 DB의 적용 이력과 새 버전의 순서를 배포 전에 확인합니다.

## 저장소 구조

| 경로 | 역할 |
| --- | --- |
| [`domain/`](src/main/java/com/boongboongus/backend/domain) | 인증, 커플, 홈, 주행, 카드, 미디어 등 기능별 코드 |
| [`common/`](src/main/java/com/boongboongus/backend/common) · [`config/`](src/main/java/com/boongboongus/backend/config) | 공통 응답·오류·요청 추적, CORS 및 API 설정 |
| [`docs/api/`](docs/api) | OpenAPI 계약과 문서 뷰어 |
| [`src/test/`](src/test) | 단위 테스트와 API·DB 통합 테스트 |

브랜치, 이슈, PR 작성 방식은 [CONTRIBUTING.md](CONTRIBUTING.md), AI 작업 지침은 [AGENTS.md](AGENTS.md)를 참고합니다.
