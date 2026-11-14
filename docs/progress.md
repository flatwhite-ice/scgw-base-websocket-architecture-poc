# Progress

이번 범위는 M0~M3이다(브랜치 `poc/m0-m3`). M4 이후는 docker, k6, kind가 준비된 뒤에 진행한다.

## 체크리스트

- [x] **M0. 프로젝트 골격**: 멀티모듈, version catalog, convention(ktlint, JVM toolchain), 모든 모듈이 빈 앱으로 기동
- [ ] **M1. 인증**: 발급→검증 단위 테스트, 만료·서명 오류·티켓 재사용 거절 테스트
- [ ] **M2. ws-backend (direct)**: 정상 연결, 미인증 401(업그레이드 전), echo 왕복, push 주기, 만료 시 4401, BlockHound 통과
- [ ] **M3. ws-gateway (relay)**: 게이트웨이 경유 E2E(정상, 401, 스푸핑 헤더 제거), BlockHound 통과
- [ ] M4. 관측성과 로컬 환경 (metrics, Grafana, docker compose, k6 스모크)
- [ ] M5. K8s 배포 (kustomize relay/direct)
- [ ] M6. 실험 E1~E5와 결과 정리

## 기록

### M0 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL. ktlintCheck와 4개 모듈 테스트를 포함한다.
  - `auth-api`, `ws-backend`, `ws-gateway`: `contextLoads` 통과
  - `common-auth`: `BlockHoundSmokeTest` 통과. parallel 스케줄러에서 `Thread.sleep`을 호출하면 `BlockingOperationError`가 발생한다
- 확정 버전은 `decisions.md` D1에 기록했다.

## 후속 과제
- (없음)
