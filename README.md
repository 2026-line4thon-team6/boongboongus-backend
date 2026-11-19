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
