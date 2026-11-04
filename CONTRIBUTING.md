# 협업 규칙

붕붕어스 백엔드의 초기 협업 기준입니다. 팀 합의로 조정하며, 규칙 변경도 PR로 공유합니다.

## 브랜치와 PR

- `main`: 배포 기준 브랜치. 검증된 변경을 PR로 반영합니다.
- `develop`: 기능 통합 브랜치. 일반 작업의 PR 대상입니다.
- 작업은 최신 `develop`에서 분기합니다: `feat/drive-start`, `fix/card-expiration`, `docs/api-guide`, `chore/dependency-update`.
- 작업 전 해당 영역의 이슈·진행 중 PR을 확인합니다. 공통 응답·설정·같은 파일을 함께 수정하면 담당자와 범위를 먼저 조율합니다.
- PR은 하나의 목적에 집중합니다. 변경 동작·확인 방법·이슈 링크·프론트 전달 사항을 적습니다.
- 정상적인 병합 기준은 CI 통과와 다른 팀원1명의 승인입니다. 팀원이 부족한 예외는 PR에 사유를 남깁니다.
- 기능 PR은 squash merge를 기본으로 하고, 팀의 릴리스 정책에 맞춰 `develop` → `main` PR을 만듭니다.

```powershell
git switch develop
git pull --ff-only origin develop
git switch -c feat/drive-start
```

커밋 후 작업 브랜치를 push하고 `develop` 대상 PR을 엽니다. 미커밋 변경이 있는 상태에서 브랜치를 강제로 맞추거나 덮어쓰지 않습니다.

## 커밋

형식: `type: 변경 내용`. 예: `feat: 주행 시작 API 구현`

| type | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작을 유지하는 구조 개선 |
| `docs` | 문서 변경 |
| `test` | 테스트 추가·수정 |
| `chore` | 의존성·프로젝트 설정 |
| `ci` | CI 설정 |
| `style` | 포맷·공백 등 동작에 영향 없는 변경 |
| `perf` | 성능 개선 |
| `revert` | 기존 변경 되돌리기 |

## 코드와 API 리뷰

- `.editorconfig`와 기존 코드 스타일을 따릅니다. Java 들여쓰기4칸, YAML2칸, 생성자 주입을 사용합니다.
- API 요청·응답 DTO, 검증 조건, HTTP 상태, 오류 코드, 권한·소유권, 트랜잭션 범위를 확인합니다.
- 프론트 계약을 변경하면 필드·타입·필수/null·페이지네이션·상태별 예시의 변경점을 PR에 적고 프론트 담당자에게 전달합니다.
- DB 컬럼·제약·인덱스를 바꾸면 기존 데이터와 배포 순서에 미치는 영향을 적습니다. 운영 스키마 변경은 합의한 마이그레이션 절차로 적용합니다.
- 테스트·빌드 명령은 `README.md`를 참고합니다. 문서만 변경한 경우 링크·명령어 점검으로 대체하고 PR에 적습니다.
- 기획상 미정인 보상·만료·도착 조건 등은 팀 결정을 기록한 뒤 반영합니다.

## AI 사용

- AI 작업의 공통 기준은 `AGENTS.md`입니다.
- `.claude/` 폴더는 별도 훅·스킬·경로별 규칙 등이 필요할 때 추가합니다. 팀 공통 정책을 도구별 파일에 복제하지 않습니다.
- AI가 만든 코드는 작업자가 이해하고 검증합니다. 존재하지 않는 API·라이브러리, 누락된 권한 검사, 개인정보 노출 여부를 확인합니다.
- AI가 작성한 코드도 동일한 리뷰·CI 기준을 적용합니다. PR에는 수행한 검증과 남은 한계를 적습니다.
- 토큰·비밀번호·실제 사용자 정보를 프롬프트·문서·테스트에 복사하지 않습니다. 개인 AI 설정은 Git에 올리지 않습니다.

## GitHub 설정

이 문서 자체가 병합을 강제하지는 않습니다. 관리자가 GitHub Settings → Rules → Rulesets에서 `main`·`develop`에 PR 필수, 승인1명, `Build and test` 필수, 강제 푸시 차단을 적용해야 합니다. CI가 한 번 실행된 뒤 체크 이름을 선택할 수 있습니다.

AI 지침의 읽기 방식은 [Codex 공식 문서](https://developers.openai.com/codex/guides/agents-md)와 [Claude Code 공식 문서](https://code.claude.com/docs/en/memory)를 참고합니다.
