# Progress

이번 범위는 M0~M3이다(브랜치 `poc/m0-m3`). M4 이후는 docker, k6, kind가 준비된 뒤에 진행한다.

## 체크리스트

- [x] **M0. 프로젝트 골격**: 멀티모듈, version catalog, convention(ktlint, JVM toolchain), 모든 모듈이 빈 앱으로 기동
- [x] **M1. 인증**: 발급→검증 단위 테스트, 만료·서명 오류·티켓 재사용 거절 테스트
- [x] **M2. ws-backend (direct)**: 정상 연결, 미인증 401(업그레이드 전), echo 왕복, push 주기, 만료 시 4401, BlockHound 통과
- [x] **M3. ws-gateway (relay)**: 게이트웨이 경유 E2E(정상, 401, 스푸핑 헤더 제거), BlockHound 통과
- [ ] M4. 관측성과 로컬 환경 (metrics, Grafana, docker compose, k6 스모크)
- [ ] M5. K8s 배포 (kustomize relay/direct)
- [ ] M6. 실험 E1~E5와 결과 정리

## 기록

### M0 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL. ktlintCheck와 4개 모듈 테스트를 포함한다.
  - `auth-api`, `ws-backend`, `ws-gateway`: `contextLoads` 통과
  - `common-auth`: `BlockHoundSmokeTest` 통과. parallel 스케줄러에서 `Thread.sleep`을 호출하면 `BlockingOperationError`가 발생한다
- 확정 버전은 `decisions.md` D1에 기록했다.

### M1 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL
  - `common-auth` 15 tests
    - `JwtIssuerVerifierTest` 10건: 발급→검증, 만료, 다른 키 서명, 미등록 kid, payload 변조, 형식 오류, typ 불일치, 티켓 재사용 거절, access 재사용 허용, PEM 왕복
    - `JwksKeySourceTest` 4건: 캐시, 키 회전 시 재조회, 재조회 rate limit, JWKS 장애
    - `BlockHoundSmokeTest`
  - `auth-api` 6 tests: JWKS로 토큰과 티켓 검증, ws-ticket 인증 필수, 티켓으로 티켓 발급 거부, blank userId 400, JWKS에 private part 없음, contextLoads
- BlockHound가 발급 경로의 `SecureRandom` 파일 읽기를 잡아냈다. `Dispatchers.IO`로 격리했다(decisions D6).

### M2 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL. ws-backend 17 tests이고, BlockHound가 켜진 상태에서 통과했다.
  - `SelfModeTest` 9건
    - ticket echo 왕복(`srvTs`, `userId`)
    - Bearer 허용
    - **미인증 시 업그레이드 전 HTTP 401**: WebClient로 101이 아닌 401을 받는 것을 확인했고, 세션 생성 수 변화 0
    - 잘못된 ticket과 재사용 ticket 401
    - access token을 ticket 자리에 넣으면 거부
    - push 기본 구독, subscribe 파라미터 반영, seq 연속
    - 만료 시 **4401**(Bearer 경로, ticket→`sexp` 경로)
    - 잘못된 메시지에 error 응답을 보내고 연결 유지
  - `GatewayModeTest` 3건: 헤더 신뢰, 헤더 없으면 401, `X-Auth-Exp` 도달 시 4401
  - `HeartbeatTest` 2건: pong 응답하는 클라이언트는 유지, 무응답 클라이언트는 **4408**로 종료
  - `DrainTest`: 세션 5개 모두 window 안에 **1001**로 종료, drain 중 신규 핸드셰이크 503
  - `AccessLogMaskingTest`: access log에 `ticket=***`만 남고 원문 토큰 없음
- 발견하고 수정한 문제
  - close 프레임 유실(1006). 원인 분석은 decisions D9에 있다. 전체 스위트 16회 반복에서 0 실패를 확인했다.
  - kotlinx-coroutines-reactive 정적 초기화의 ServiceLoader 호출을 BlockHound가 잡았다. 1회성 class init이라 테스트 allow-list에 근거를 달아 추가했다(`WsPocBlockHoundIntegration`).

### M3 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL. ws-gateway 10 tests이고, BlockHound가 켜진 상태에서 통과했다.
  - `GatewayE2ETest`: 같은 JVM에 **실제 ws-backend(gateway 모드)** 를 띄우고 SCG로 경유해서 검증했다. 9건:
    - relay echo: 인증된 userId 전달, backend로 가는 URI에 `ticket` 없음, 다른 쿼리는 유지
    - Bearer relay: backend에 `Authorization` 미전달
    - **401이고 upstream 연결 없음**: backend 수신 기록 0건
    - **스푸핑 헤더 교체**: `X-User-Id: evil`, `X-Scopes: admin`을 보내도 토큰 값으로 바뀐다
    - 스푸핑 헤더만 있으면 401
    - ticket 재사용 401
    - push relay
    - backend가 보낸 **4401이 gateway를 거쳐 클라이언트에 도달**
    - 비-WS 요청은 필터가 막지 않음(404)
- 발견하고 수정한 문제: Spring Cloud LoadBalancer가 첫 요청 때 event loop에서 child context를 만들면서 블로킹했다. eager-load로 해결했다(decisions D10).

### 실제 프로세스 E2E (2026-11-15)
- bootJar 4개를 실행했다: auth-api :8080, backend `relay` :8081, gateway :8082, backend `direct` :8083
- `java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082 ws://localhost:8083` → **ALL OK**
  - relay 경로(gateway)와 direct 경로 각각 확인: echo(id, userId, srvTs), 티켓 재사용 401, 미인증 401, push 구독
- 앱 로그 4개에서 원문 티켓(`ticket=eyJ`) 0건
- direct backend에 세션 1개를 연결한 상태로 SIGTERM을 보냈다. 클라이언트는 **18초 후 1001**을 받았다(window 20초). 이어서 `Drain finished, 0 sessions still open` → `Graceful shutdown complete`
- backend가 auth-api보다 먼저 뜨면 초기 JWKS 조회는 실패한다. 첫 핸드셰이크에서 미등록 kid를 보고 재조회해 정상 복구된다. 경고 로그에 예외 타입이 나오도록 개선했다.

### close code 변경 (2026-11-15)
- 최종 코드는 토큰 만료 **3000**, heartbeat **3008**, drain **1001**이다. 모두 RFC 6455 §7.4 기준이다(decisions D13).
  - 중간에 IANA 기준으로 drain을 1012로 바꿨는데, 실측해 보니 SCG가 1012를 1002로 변환했다(D14). 그래서 1001로 되돌렸다.
  - 위 M2/M3 기록의 4401/4408은 당시 기준 값이다.
- `CloseCodesRfc6455Test`를 추가했다. 서버가 보내는 모든 코드가 RFC 6455에 맞는지 검증한다.
- `./gradlew build` 통과(19 suites, 실패 0)
- 실제 프로세스: gateway를 거친 클라이언트가 backend drain 때 1001을 받는 것을 raw 소켓으로 확인했다.
- 1014 확인 결과: relay에서 backend를 SIGKILL하면 클라이언트는 코드 없는 close 프레임을 받는다. SCG는 1014를 생성하지 않는다(D14).

## 후속 과제
- E4에서 relay 재연결을 해석할 때 주의: SCG는 101을 먼저 응답한다. backend가 drain 중이거나 죽어 있으면 클라이언트는 "101 후 즉시 1002"를 받는다(decisions D14).
- (선택) SCG에 이슈 제기: `adaptCloseStatus`가 IANA 1012–1014를 1002로 변환하는 문제(D14).
- gateway drain 시 relay 세션별로 1001과 지터를 적용해 종료하기. 지금은 신규 업그레이드를 503으로 거절하는 것까지만 한다(decisions D10).
- 500 에러 로그에 요청 URI의 `ticket`이 그대로 남는 문제 해결. 에러 핸들러 커스터마이즈가 필요하다(decisions D11).
- M5: relay backend의 ingress를 ws-gateway로만 제한하는 NetworkPolicy. gateway 모드는 헤더를 신뢰하기 때문이다(decisions D8).
- K8s 파드 IP 디스커버리(Spring Cloud Kubernetes 또는 headless Service)는 M5에서 붙인다.
- M4 메트릭(`ws.sessions.active` 등) 연결. `SessionRegistry.activeCount`와 `WsSessionHandler.droppedPushMessages`가 이미 준비되어 있다.
