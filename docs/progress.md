# Progress

M0~M5가 끝났다. M0~M3는 브랜치 `poc/m0-m3`에서 했다. M6 이후는 docker, k6, kind가 준비된 뒤에 진행한다.

마일스톤 번호는 스펙(`docs/hypotheses.md` §7)과 다르다. 스펙의 M4(관측성과 로컬 환경)를 M4(메트릭), M5(콘솔 모니터링), M6(Grafana, compose, k6)으로 나눴다. 그래서 스펙의 M5(K8s)는 M7, M6(실험)은 M8이다.

close 코드는 D13에서 바뀌었다(스펙 4401/4408 → 3000/3008, drain 1001). 아래 기록의 close 코드는 현재 값으로 적었다.

## 체크리스트

- [x] **M0. 프로젝트 골격**: 멀티모듈, version catalog, convention(ktlint, JVM toolchain), 모든 모듈이 빈 앱으로 기동
- [x] **M1. 인증**: 발급→검증 단위 테스트, 만료·서명 오류·티켓 재사용 거절 테스트
- [x] **M2. ws-backend (direct)**: 정상 연결, 미인증 401(업그레이드 전), echo 왕복, push 주기, 만료 시 3000(스펙은 4401, D13), BlockHound 통과
- [x] **M3. ws-gateway (relay)**: 게이트웨이 경유 E2E(정상, 401, 스푸핑 헤더 제거), BlockHound 통과
- [x] **M4. 관측성(metrics)**
- [x] **M5. ws-console 을 이용한 간이 모니터링 콘솔 제공**
- [ ] M6. 관측성과 로컬 환경 (metrics, Grafana, docker compose, k6 스모크).
- [ ] M7. K8s 배포 (kustomize relay/direct)
- [ ] M8. 실험 E1~E5와 결과 정리

## 기록

### M0 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL. ktlintCheck와 4개 모듈 테스트를 포함한다.
  - `auth-api`, `ws-backend`, `ws-gateway`: `contextLoads` 통과
  - `auth-token`: `BlockHoundSmokeTest` 통과. parallel 스케줄러에서 `Thread.sleep`을 호출하면 `BlockingOperationError`가 발생한다
- 확정 버전은 `decisions.md` D1에 기록했다.

### M1 — 완료 (2026-11-15)
- `./gradlew build` → BUILD SUCCESSFUL
  - `auth-token` 15 tests
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
    - 만료 시 **3000**(Bearer 경로, ticket→`sexp` 경로)
    - 잘못된 메시지에 error 응답을 보내고 연결 유지
  - `GatewayModeTest` 3건: 헤더 신뢰, 헤더 없으면 401, `X-Auth-Exp` 도달 시 3000
  - `HeartbeatTest` 2건: pong 응답하는 클라이언트는 유지, 무응답 클라이언트는 **3008**로 종료
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
    - backend가 보낸 **3000이 gateway를 거쳐 클라이언트에 도달**
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
  - M2/M3 기록은 현재 코드로 고쳐 적었다. 당시에는 4401(만료)과 4408(heartbeat)이었다.
- `CloseCodesRfc6455Test`를 추가했다. 서버가 보내는 모든 코드가 RFC 6455에 맞는지 검증한다.
- `./gradlew build` 통과(19 suites, 실패 0)
- 실제 프로세스: gateway를 거친 클라이언트가 backend drain 때 1001을 받는 것을 raw 소켓으로 확인했다.
- 1014 확인 결과: relay에서 backend를 SIGKILL하면 클라이언트는 코드 없는 close 프레임을 받는다. SCG는 1014를 생성하지 않는다(D14).

### gateway drain 구현 (2026-11-15)
- `GatewayDrainCoordinator`를 추가했다. shutdown 시 신규 업그레이드는 503, relay 세션은 window 안에서 지터를 두고 1001로 닫는다. 세션 추적은 SCG `WebSocketService` 데코레이터로 한다(decisions D10).
- `GatewayDrainTest`를 추가했다. `./gradlew build` 통과(20 suites, 실패 0), gateway 테스트 8회 반복에서 실패 0
- 실제 프로세스: 게이트웨이에 SIGTERM을 보내자 클라이언트 2개가 지터 시점에 1001을 받았고, backend 세션도 정리됐다.

### 백엔드 서비스 2개 + 경로 라우팅 (2026-11-15)
- 공통 런타임을 `ws-backend-core`로 분리했다(decisions D17). 기존 테스트는 import만 바꿔 그대로 통과했다.
- 채팅 서비스 `ws-chat`(relay 8084 / direct 8085, `/ws/chat`)을 추가했다. 게이트웨이는 `/ws/echo`, `/ws/push` → ws-backend, `/ws/chat/**` → ws-chat으로 보낸다(D18). 이 라우트는 D24에서 `Path=/ws/chat`으로 좁혔다.
- `./gradlew build` 통과
  - 새 테스트: `ChatTest` 5개, `WsChatApplicationTests`, `GatewayE2ETest`에 라우팅 2개(서비스별 분기, 미라우팅 404), `RouteDefinitionTest` 2개 라우트 검증
- 실제 프로세스(1808x 포트, bootJar 5개: auth, ws-backend relay, ws-chat relay·direct, gateway)
  - `java scripts/WsSmoke.java http://localhost:18080 ws://localhost:18082 ws://localhost:18085` → **ALL OK**
  - websocat: 게이트웨이 `/ws/echo` 응답은 `server=ws-backend@...:18081`, `/ws/chat` 응답은 `server=ws-chat@...:18084`. 두 사용자 채팅 전달 확인
  - curl: `/ws/echo`, `/ws/chat`, `/ws/chat/room1` 미인증 401, `/ws/unknown` 404

### 포트 재배치 (2026-11-15)
- auth 8070, gateway 8080, ws-backend 8090, ws-chat 8091, direct는 8092/8093으로 옮겼다(decisions D19). 위 기록의 포트는 당시 값이다.
- `./gradlew build` 통과(25 suites, 실패 0)
- 실제 프로세스: 포트 인자 없이 yml 기본값으로 auth 8070, ws-backend 8090/8092, ws-chat 8091/8093을 띄웠다. 8080은 IDE 앱이 쓰고 있어서 gateway만 `--server.port=18080`으로 띄웠고, discovery 대상과 JWKS URI는 yml 기본값을 그대로 썼다.
  - `java scripts/WsSmoke.java http://localhost:8070 ws://localhost:18080 ws://localhost:8092 ws://localhost:8093` → **ALL OK**
  - 게이트웨이 경유 응답의 `server`: `/ws/echo`는 `ws-backend@...:8090`, `/ws/chat`은 `ws-chat@...:8091`

### 게이트웨이 인증을 라우트 필터로 전환 (2026-11-15)
- `WsAuthFilter`(전역)를 `GatewayEdgeFilter`(전역: 신원 헤더 제거, drain 503)와 라우트 필터 `WsAuth`(인증)로 나눴다. 인증 라우트는 YAML `filters: [WsAuth]`로 선언한다(decisions D20).
- core에 `@WsEndpoint(auth = NONE)`를 추가했다. ws-backend에 익명 읽기 전용 `/ws/public/push`와 게이트웨이 라우트 `ws-backend-public`(WsAuth 없음)을 추가했다.
- `./gradlew build` 통과(28 suites, 80 tests, 실패 0)
  - 새 테스트: `PublicPushTest` 4개, 매핑 익명 경로 1개, `GatewayE2ETest` 2개(공개 라우트, 인증 라우트 401), `RouteDefinitionTest` WsAuth 유무 2개, `MissingAuthFilterTest`(fail-closed), drain 503의 익명 라우트 확인
- 실제 프로세스(기본 포트 그대로: auth 8070, gateway 8080, ws-backend 8090/8092, ws-chat 8091/8093)
  - `java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080 ws://localhost:8092 ws://localhost:8093` → **ALL OK**
  - curl 업그레이드 상태: `/ws/public/push` 101, `/ws/echo`·`/ws/push`·`/ws/chat` 401, `/ws/unknown` 404. WsAuth 라우트의 일반 `GET /ws/echo`도 401

### 세션 열림/종료 로그 (2026-11-15)
- kotlin-logging 8.0.7을 추가했다. SLF4J와 Logback은 Boot BOM 버전 그대로다.
- ws-backend·ws-chat(core `SessionLog`)과 ws-gateway(`RelaySessionLog`)가 `ws.session` 로거에 세션 열림/종료와 active 수를 남긴다. 기본은 켜져 있고, 부하 실험 때는 `logging.level.ws.session=WARN`으로 끈다(decisions D21).
- `./gradlew build` 통과(31 suites, 86 tests, 실패 0). 새 테스트는 `SessionLogTest` 4개, `RelaySessionLogTest` 2개다.
  - 작성 중에 `= runBlocking { ... }`의 마지막 식이 값이면 JUnit이 그 테스트를 조용히 건너뛰는 것을 발견했다. 전체 테스트를 선언 수와 실행 수로 대조해 다른 누락이 없음을 확인했다.
- 실제 프로세스(기본 포트): smoke **ALL OK**
  - 로그는 gateway 16줄, backend 10줄, chat 6줄이다. 정상 종료는 `code=1000 reason=bye closedBy=client`로 남았다.
  - 3개 앱 로그에서 티켓 원문은 0건이었다.

### JWKS 초기 조회 실패 복구 (2026-11-15)
- 첫 JWKS 조회 실패 뒤 5초 동안 핸드셰이크가 401이던 문제를 고쳤다(decisions D22). 실패 뒤 재조회 간격은 0.5초, 동시 miss는 1회만 조회하고, 주기 루프는 1초부터 최대 30초까지 백오프한다.
- `./gradlew build` 통과(31 suites, 90 tests, 실패 0)
- 실제 프로세스: gateway와 direct backend를 먼저 띄우고 auth-api를 나중에 띄웠다. auth-api 기동 직후 smoke를 실행했고 결과는 **ALL OK**였다.

### 브라우저 테스트 콘솔 (2026-11-15)
- 새 모듈 `ws-console`(:8060)을 추가했다. 게이트웨이 경유로 echo, push, 공개 push, chat(여러 참가자), 토큰 만료 3000, 거절 케이스(서버 probe)를 화면에서 확인한다(decisions D23).
- auth-api `/auth/token`에 선택 필드 `ttlSeconds`(1..900)를 추가했다.
- `./gradlew build` 통과
  - 새 테스트: `ConsoleControllerTest` 5개, `WsConsoleApplicationTests`, `AuthControllerTest` ttl 1개(33 suites, 97 tests, 실패 0)
- 실제 프로세스(기본 포트: auth 8070, gateway 8080, ws-backend 8090, ws-chat 8091, console 8060)
  - smoke(게이트웨이) **ALL OK**
  - curl `/api/probe`: echo·chat 티켓 101, Bearer 101, 미인증 401, 재사용 101→401, 위조 헤더 401, `/ws/unknown` 404, 공개 101, 잘못된 경로 400
  - 헤드리스 Chrome(CDP)으로 화면을 조작했다.
    - echo: 응답, RTT, `invalid json`/`unknown type`, 종료 1000
    - push: 자동 수신, 100ms 구독 시 초당 10, unsubscribe 후 정지
    - 공개 push: 수신과 `read only`
    - chat 3명: lobby 두 명 모두 수신, other 방은 미수신, `room required`/`not joined`
    - 토큰 만료: TTL 3초로 3000 `token expired`(만료 시각과 차이 0.0s)
    - probe 전체: 10/10
  - 4개 앱 로그에서 티켓 원문은 0건이었다.

### 코드 검토와 수정 4건 (2026-11-15)
- backend(core, ws-backend, ws-chat)와 gateway를 검토했다. 임시 테스트로 버그 3건을 재현했고, 코드로 drain 누락 1건을 확인해 함께 고쳤다(decisions D24).
  - 프로토콜 예외가 세션을 코드 없이(1005) 끊던 문제 → 오류 응답 또는 **1011**
  - `ChatRooms` join/leave 경합으로 참가자가 방에서 빠지던 문제
  - 게이트웨이 라우트가 서비스 엔드포인트보다 넓어 "101 뒤 1002"가 나던 문제 → `Path=/ws/chat`, `Path=/ws/public/push`
  - drain 시작 직전에 들어온 세션이 1001을 못 받던 문제(backend, gateway)
- 회귀 테스트 8개를 먼저 쓰고 수정 전 코드에서 모두 실패하는 것을 확인했다: `ProtocolErrorTest` 4, `LateSessionDrainTest` 1, `ChatRoomsTest` 1, `GatewayLateSessionDrainTest` 1, `GatewayE2ETest` 1.
- 검토 중 문제없음을 확인한 것: 게이트웨이 `response-timeout: 10s`는 유휴 WebSocket을 끊지 않는다(13초 유휴 뒤 echo 정상). 인코딩되지 않은 non-ASCII query는 500이 아니라 400이다.
- `./gradlew build` 결과: 통과(37 suites, 105 tests, 실패 0). 캐시 없이(`cleanTest`) 한 번 더 돌려 같은 결과를 확인했다.
- 실제 프로세스 확인은 하지 않았다(자동 테스트만).

### ws-console 화면 재구성 (2026-11-15)
- 시나리오마다 화면과 프로토콜을 함께 보는 타임라인(나란히/교차/화면/프로토콜), 메신저 형태의 채팅과 데모 대화, 데이/나이트 토글을 넣었다(decisions D25). 서버 쪽 코드는 바꾸지 않았다.
- `./gradlew build` 결과: 통과(37 suites, 105 tests, 실패 0)
- 실제 프로세스(기본 포트: auth 8070, gateway 8080, ws-backend 8090, ws-chat 8091, console 8060)를 띄우고 헤드리스 Chrome(CDP)으로 주소(`#tab=…&demo=1`)를 열어 스크린샷으로 확인했다.
  - echo: 데모(정상 2건, `invalid json`, `unknown type`, 그 뒤 정상)와 RTT 타일, 짝 강조 2줄, 프레임 펼침
  - push: 기본 구독 → `fast` 200ms 구독 → unsubscribe, 실시간 위젯
  - 공개 push: 토큰 없이 수신, `read only`
  - chat: 데모 대화 3명. 전달 표시, `#dev` 방 격리, alice 퇴장 뒤 미수신
  - 토큰 만료: TTL 6초로 3000 `token expired`(만료 시각 대비 +0.0초)
  - 거절 케이스: 11/11 통과
  - 보기 방식 4가지, 데이/나이트 전환, 폭 430px(모바일) 배치
  - 페이지 콘솔 오류 0건, 앱 로그에 티켓 원문 0건

### ws-console 프로토콜 상세와 curl (2026-11-15)
- 프레임을 누르면 상세 패널이 열린다. HTTP 호출(토큰, 티켓, 업그레이드 핸드셰이크)은 요청·응답 원문과 curl 명령을 보여 주고, 거절 케이스 탭은 케이스마다 재현용 curl을 보여 준다(decisions D26). `/api/config`에 `authBase`를 추가했다.
- `./gradlew build` 결과: 통과(37 suites, 105 tests, 실패 0)
- 실제 프로세스 + 헤드리스 Chrome으로 확인했다. 8060~8091은 IDE에서 띄워 둔 인스턴스가 쓰고 있어, 확인용 콘솔만 18060으로 따로 띄웠다.
  - 상세 패널: echo(HTTP 2건, OPEN, TEXT 송수신), chat(OPEN), 토큰 만료(CLOSE 3000, `ttlSeconds`가 들어간 토큰 curl), 거절 케이스(curl 펼침). 페이지 콘솔 오류 0건
  - **화면이 만든 명령을 페이지에서 뽑아 그대로 실행했다(17개).**
    - 토큰 발급 → 200 JSON, 티켓 발급 → 200 JSON
    - echo·chat 핸드셰이크 → `HTTP/1.1 101 Switching Protocols`
    - 거절 케이스 11개 → 101, 101, 401, 401, 101, 401, `101 401`(재사용), 401, 404, 404, 101. 모두 기대와 같다.
    - websocat 명령은 실행하지 않았다(준비 명령은 curl과 같다).
- 정적 파일 캐시 수정: 화면을 고친 뒤에도 브라우저가 예전 `core.js`·`app.js`를 써서 curl이 보이지 않았다. 정적 파일과 `/`에 `Cache-Control: no-cache`를 붙였다(`spring.web.resources.cache.cachecontrol.no-cache`, `NoCacheIndexFilter`). `ConsoleControllerTest`가 헤더를 확인한다(decisions D26).

### M4 메트릭 (2026-11-15)
- 공통 `ws.*` 메트릭과 `/actuator/prometheus`를 ws-backend, ws-chat, ws-gateway에 넣었다(decisions D27). Grafana, compose, k6는 M6에서 한다.
- 새 모듈 `ws-metrics`(`WsMeters`, `EventLoopLagProbe`). core는 `endpoint`, 게이트웨이는 `route` 태그로 세션을 묶는다.
- 메트릭: `ws.sessions.active`, `ws.handshake`, `ws.messages`, `ws.messages.dropped`, `ws.close`, `ws.session.duration`, `ws.eventloop.lag`, 게이트웨이의 `ws.relay.upstream.connect`. Reactor Netty 내장 메트릭도 켰다.
- 스펙의 `ws.message.relay.latency`는 만들지 않았다. k6의 relay/direct 왕복 시간 차이로 본다(D27).
- drain 상태를 다시 시작할 수 있게 했다(테스트 컨텍스트 재사용 시 503, D27).
- 테스트: `WsMetersTest`, `EventLoopLagProbeTest`(ws-metrics), `MetricsTest`(ws-backend), `GatewayMetricsTest`(ws-gateway. 죽은 upstream의 "101 뒤 upstream connect error" 포함). `./gradlew build` 통과.
- 실제 실행 확인(auth-api :18070, ws-backend :18090, ws-gateway :18080, ws-chat은 띄우지 않음):
  - `WsSmoke`를 게이트웨이로 실행한 뒤 두 프로세스의 `/actuator/prometheus`를 확인했다. echo/push는 통과했고 chat 단계는 ws-chat이 없어 실패했다(의도한 구성).
  - 게이트웨이: `ws_handshake`의 `ok`/`unauthorized{reason=missing|replayed}`, `ws_messages_total`, `ws_close_total{code=1000|1002|1006}`, `ws_relay_upstream_connect{route=ws-chat,result=error,exception=AnnotatedConnectException}`가 나왔다.
  - 서비스: 엔드포인트 3개의 gauge가 연결 전부터 0으로 보이고, `ws_eventloop_lag{pool=netty|parallel}`가 나왔다.
  - 내장: `reactor_netty_eventloop_pending_tasks`, `reactor_netty_connection_provider_*`, `reactor_netty_bytebuf_allocator_*`, `process_files_open_files`, `jvm_gc_pause`가 나왔다. `uri` 태그 값은 한정돼 있다.
  - ws-chat은 직접 띄워 확인하지 않았다(ws-backend와 같은 core 코드다. 테스트는 통과했다).

### M5 모니터링 콘솔 (2026-11-15)
- ws-console에 "모니터링" 탭을 넣었다(decisions D28). 있는 메트릭(D27)만 쓴다. 추가로 있으면 좋을 메트릭은 `docs/metrics-proposals.md`에 제언으로만 적었다.
- 서버: `GET /api/metrics`가 `console.monitor.targets`(기본 ws-gateway :8080, ws-backend :8090, ws-chat :8091)의 `/actuator/prometheus`를 긁어 JSON으로 준다(`MetricsScraper`, `PrometheusText`). 저장소는 없다.
- 화면(`monitor.js`): 요약 수치 6개, 프로세스 카드, 추이 차트 6개(SVG, 십자선 툴팁), 분류 표 3개, 구간 1분/5분/15분, 일시정지, 데모 트래픽 버튼.
- BlockHound가 잡은 것: `/api/metrics` 응답을 Jackson으로 직렬화하면 kotlin-reflect가 event loop에서 jar를 읽는다. 응답 JSON을 직접 쓰도록 바꿨다(D28).
- 테스트: `PrometheusTextTest` 4개, `MonitorMetricsTest` 2개(정상 대상, 404 대상, 죽은 대상, 결과 재사용). `./gradlew build` 통과.
- 실제 실행 확인: IDE에서 떠 있는 auth 8070, gateway 8080, ws-backend 8090, ws-chat 8091을 대상으로, 확인용 콘솔만 18060으로 띄우고 헤드리스 Chrome으로 봤다.
  - `#tab=monitor&demo=1`: 데모 트래픽 동안 활성 세션 12(게이트웨이 `ws-backend-public` 12, 서비스 `/ws/public/push` 12), 보낸 메시지 약 12/s, 핸드셰이크 성공률과 `인증 실패 · 자격증명 없음` 줄, 종료 코드 1000이 늘어나는 것을 확인했다.
  - 데이/나이트, 폭 1440·1680·430px 배치, 차트 툴팁. 가로 넘침 없음. 페이지 오류 없음(의도한 401 연결 실패 로그만 있다).
  - 대상이 죽은 상태와 direct 대상 구성은 화면으로 보지 않았다.

### 모듈 이름 변경 (2026-11-15)
- `common-auth`를 `auth-token`으로, 패키지를 `com.example.wspoc.auth.token`으로 바꿨다(decisions D29). 위 기록의 모듈 이름도 새 이름으로 고쳐 적었다.

### 서비스 모듈 이름 변경 (2026-11-15)
- `modules/ws-backend` → `modules/ws-backend-service-default`, `modules/ws-chat` → `modules/ws-backend-service-chat`(decisions D30). 서비스 이름(`ws-backend`, `ws-chat`)과 패키지는 그대로다. 위 기록의 서비스 이름은 런타임 이름이라 고치지 않았다.
- `./gradlew clean build` 통과(43 suites, 130 tests, 실패 0)
- 실제 실행: 새 이름의 bootJar로 auth 18070, ws-backend relay 18090·direct 18092, ws-chat relay 18091·direct 18093, gateway 18080을 띄웠다. `WsSmoke`(게이트웨이 + direct 둘) **ALL OK**. 메트릭의 `application` 태그는 `ws-backend`, `ws-chat`, 게이트웨이 `route`는 `ws-backend`, `ws-backend-public`, `ws-chat` 그대로다. 로그에 티켓 원문 0건.

### 커밋 전 정리 (2026-11-15)
- 주석·문서와 코드를 대조했다. 고친 것:
  - 서비스가 받은 BINARY 프레임을 `ws.messages{direction=in}`에 세지 않았다. 메트릭 설명(D27 "TEXT/BINARY")과 게이트웨이는 BINARY도 센다. core도 세도록 고치고 `MetricsTest`에 회귀 테스트를 추가했다(수정 전 코드에서 실패 확인).
  - 콘솔 `console.monitor.refresh` 주석이 실제 재사용 시간(`refresh`의 절반)과 달랐다. 주석과 D28을 고쳤다.
  - 게이트웨이 `ws.gateway.metrics.netty`가 upstream 커넥션 풀 메트릭까지 끄는 것처럼 읽혔다. 풀 메트릭은 yml `httpclient.pool.metrics`로 따로 켜고 끈다고 주석에 적었다.
  - CLAUDE.md 명령 목록의 주석 열을 맞췄다(모듈 이름 변경 뒤 어긋남).
- `./gradlew clean build` 통과(43 suites, 131 tests, 실패 0)

### 읽기 쉽게 정리 (2026-11-15)
- ws-console을 뺀 모듈에서 중복을 모으고 큰 파일을 나눴다(decisions D31). 외부 동작은 그대로다.
  - 중복 제거: 핸드셰이크 자격증명 검증(`JwtVerifier.verifyHandshake`), JWKS 갱신 수명(`RefreshingJwksKeySource`), scope 파싱, trace attribute 이름, event loop 측정 연결
  - 파일 분리: `ManagedSession`, `WsMetricsAutoConfiguration`, 게이트웨이 `TrackingWebSocketService`·`TrackingWebSocketClient`
  - 게이트웨이 패키지 `gateway.drain` → `gateway.relay`
- `./gradlew clean build` 통과(43 suites, 131 tests, 실패 0). 테스트는 import와 클래스 이름만 바꿨다.
- 실제 실행: 새 bootJar로 auth 18070, ws-backend 18090·18092, ws-chat 18091·18093, gateway 18080을 띄웠다. `WsSmoke`(게이트웨이 + direct 둘) **ALL OK**. 게이트웨이 메트릭에 `ok`, `unauthorized{reason=missing|replayed}`, 라우트별 close 코드가 이전과 같게 나왔고, 서비스의 `ws_eventloop_lag`도 `netty`·`parallel` 두 풀에서 나왔다. 게이트웨이 SIGTERM 때 drain 로그 확인, 로그에 티켓 원문 0건.

## 후속 과제
- (검토, D31) core와 게이트웨이의 drain coordinator, access log 설정이 거의 같은 코드로 두 벌 있다. 함께 쓸 모듈을 둘지 정한다.
- (검토, D31) 프로토콜 콜백이 오래 걸릴 때 heartbeat가 PONG을 놓치지 않는지 확인하는 테스트가 없다.
- (검토, D29) BlockHound 테스트 설정(`WsPocBlockHoundIntegration`)이 `auth-token`의 testFixtures에 있다. 인증과 관계없으므로 별도 테스트 지원 모듈로 옮길지 정한다.
- (검토) `docs/metrics-proposals.md`의 추가 메트릭 제언 채택 여부를 정한다(세션 수명 버킷, drain 상태, upstream 인스턴스별 세션, `ws.bytes`, heartbeat RTT 등).
- (M6) Prometheus가 붙으면 모니터링 탭이 Prometheus를 조회하도록 바꾸는 것을 검토한다. 지금은 브라우저가 열려 있는 동안만 기록이 남는다(D28).
- M6: Grafana 대시보드 JSON, docker compose(prometheus 포함), k6 스모크. 중계 지연은 k6에서 relay와 direct의 echo 왕복 시간 차이로 잰다(D27).
- (M7) actuator 노출 범위: 게이트웨이의 `/actuator`가 서비스 포트에 있다. management 포트를 분리하거나 Ingress에서 막는다(D27).
- (E1/E2) Reactor Netty 내장 메트릭의 연결당 비용을 켠 것과 끈 것(`ws.metrics.netty=false`)으로 비교한다(D27).
- E4에서 relay 재연결을 해석할 때 주의: SCG는 101을 먼저 응답한다. backend가 drain 중이거나 죽어 있으면 클라이언트는 "101 후 즉시 1002"를 받는다(decisions D14).
- (선택) SCG에 이슈 제기: `adaptCloseStatus`가 IANA 1012–1014를 1002로 변환하는 문제(D14).
- 500 에러 로그에 요청 URI의 `ticket`이 그대로 남는 문제 해결. 에러 핸들러 커스터마이즈가 필요하다(decisions D11).
- M7: relay backend의 ingress를 ws-gateway로만 제한하는 NetworkPolicy. gateway 모드는 헤더를 신뢰하기 때문이다(decisions D8).
- K8s 파드 IP 디스커버리(Spring Cloud Kubernetes 또는 headless Service)는 M7에서 붙인다.
- ws-chat 인스턴스 간 방 fan-out(Redis pub/sub 등). 지금은 방이 인스턴스 로컬이라 여러 대를 띄우면 방이 쪼개진다(decisions D18).
- M6 compose / M7 매니페스트에 ws-chat 추가(스펙의 "ws-backend x2"와 별도 서비스).
- `WsAuth` 라우트 필터에 설정 항목 추가 검토(예: 필요한 scope). 지금은 인자 없는 `- WsAuth`다(decisions D20).
- ws-console: direct(:8092/:8093) 대상 선택, drain(1001) 시나리오 버튼(게이트웨이 종료 연동) 검토(decisions D23).
- (검토, D24) close 전송에 타임아웃이 없다. `ManagedWsHandler.close`는 `NonCancellable`로 close 완료를 기다리는데, 상대가 죽어 소켓 버퍼가 찬 경우 TCP가 포기할 때까지 세션이 레지스트리에 남을 수 있다. 재현은 하지 않았다.
- (검토) 송신 큐가 가득 차면 heartbeat ping도 버려져, 느린 소비자는 ping을 받지 못한 채 3008로 끊긴다. 의도한 동작인지 정한다.
- (검토) 게이트웨이에 upstream 생존 확인이 없다. 정적 discovery라 죽은 backend도 라운드로빈 대상이 되고 그 몫은 "101 뒤 1002"가 된다. E4(backend 파드 종료) 전에 정리한다.
- (검토) 로드밸런싱이 라운드로빈이다. 스펙 §10의 least-connections가 아직 반영되지 않았다(스케일아웃 뒤 쏠림, E1·E4).
- (검토) ws-chat 입력 한도가 없다: 세션당 참여 방 수, 방 이름 길이(프레임 한도 64KB까지).
