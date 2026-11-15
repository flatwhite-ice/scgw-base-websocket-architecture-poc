# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(claude.ai/code)를 위한 안내다.

## 프로젝트

1 vCPU / 4 GB 파드에서 WebSocket을 제공하는 두 가지 방식을 비교하는 PoC다.
- **A. Relay:** 클라이언트 → `ws-gateway`(Spring Cloud Gateway, JWT 검증) → 경로로 고른 WebSocket 서비스
- **B. Direct:** 클라이언트 → WebSocket 서비스. 서비스가 핸드셰이크 중에 JWT를 직접 검증한다.

WebSocket 서비스는 공통 `ws-backend-core` 위에 만든 두 개다: `ws-backend`(echo/push, `/ws/echo`와 `/ws/push`)와 `ws-chat`(방 채팅, `/ws/chat`)(D17, D18). 모듈 폴더는 각각 `modules/ws-backend-service-default`와 `modules/ws-backend-service-chat`이다. 서비스 이름(`spring.application.name`, 게이트웨이 discovery id, 메트릭 `application` 태그, 응답의 `server`)은 `ws-backend`, `ws-chat` 그대로다(D30).

**검증할 가설과 원래 스펙은 `docs/hypotheses.md`다**(가설 H1~H5, 실험 E1~E5. 처음에는 handoff 문서였다). 마일스톤 진행은 `docs/progress.md`에, 결정과 스펙에서 벗어난 점은 `docs/decisions.md`(D1, D2, …)에 기록한다. 변경 전에 두 문서를 모두 읽는다. M0–M3는 `poc/m0-m3` 브랜치에서 끝났다. M4(메트릭, D27)와 M5(ws-console 모니터링 탭, D28)가 끝났다. 마일스톤 번호는 스펙과 다르다(스펙 M4를 M4–M6으로 나눴다. 대응은 `docs/progress.md` 머리말에 있다). M6부터는 docker, k6, kind 또는 k3d가 필요한데 아직 로컬에 설치되어 있지 않다.

스택: JDK 25(Temurin, `~/Library/Java/JavaVirtualMachines/temurin-25.0.5`에 설치), Kotlin 2.3, Spring Boot 4.1, Spring Cloud 2025.1(SCG 5.0, 설정 prefix `spring.cloud.gateway.server.webflux.*`), WebFlux + Reactor Netty + 코루틴, Jackson 3(`tools.jackson.*`). **MVC는 사용 금지다.**

## 명령어

```bash
./gradlew build                                                                               # 컴파일 + ktlintCheck + 전체 테스트(커밋 전에 반드시 통과)
./gradlew ktlintFormat                                                                        # 스타일 자동 수정(ktlint_official, 최대 140자)
./gradlew :modules:ws-backend-service-default:test                                            # 모듈 하나
./gradlew :modules:ws-backend-service-default:test --tests '*SelfModeTest'                    # 클래스 하나
./gradlew :modules:ws-backend-service-default:test --tests '*SelfModeTest.echo round trip*'   # 테스트 하나(백틱 이름)

# 로컬 실행(relay와 direct를 함께)
./gradlew :modules:auth-api:bootRun                                                           # :8070
./gradlew :modules:ws-backend-service-default:bootRun                                         # :8090, 기본값 = relay(게이트웨이 헤더 신뢰)
./gradlew :modules:ws-backend-service-default:bootRun --args='--spring.profiles.active=direct'  # :8092, JWT를 직접 검증
./gradlew :modules:ws-backend-service-chat:bootRun                                            # :8091, 기본값 = relay
./gradlew :modules:ws-backend-service-chat:bootRun --args='--spring.profiles.active=direct'   # :8093, JWT를 직접 검증
./gradlew :modules:ws-gateway:bootRun                                                         # :8080, /ws/echo,/ws/push -> :8090, /ws/chat -> :8091
./gradlew :modules:ws-console:bootRun                                                         # :8060, 브라우저 테스트 콘솔(게이트웨이 경유)
curl -s localhost:8080/actuator/prometheus | grep '^ws_'                                      # 메트릭(게이트웨이 :8080, 서비스 :8090/:8091)
java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080 ws://localhost:8092 ws://localhost:8093   # JDK만으로 하는 E2E smoke
```

smoke 스크립트는 주소마다 그 주소가 제공하는 서비스만 확인하고, 핸드셰이크가 404인 서비스는 건너뛴다. websocat/curl 수동 명령(토큰, 티켓, echo/push, chat, 라우팅, 401과 위조 헤더 확인)은 `docs/ws-cli-testing.md`를 본다.

두 서비스의 기본값은 relay(gateway 인증 모드)다: ws-backend는 :8090, ws-chat은 :8091이고 게이트웨이의 discovery 대상이다. 이 모드에서는 서비스가 주입된 헤더를 신뢰하므로 게이트웨이를 통해서만 접근할 수 있어야 한다. `direct` 프로필은 JWKS를 쓰는 self 모드로 바꾸고, ws-backend는 :8092, ws-chat은 :8093에서 뜬다. JWKS 빈은 self 모드이고 URI가 비어 있지 않을 때만 생긴다.

## 아키텍처

- **build-logic/**: convention 플러그인
  - `wspoc.kotlin-conventions`: toolchain, ktlint, JUnit, BlockHound JVM 플래그
  - `wspoc.spring-app-conventions`: Boot, WebFlux, 코루틴
  - `wspoc.spring-library-conventions`: bootJar가 없는 Spring 라이브러리용(`ws-backend-core`)
- 버전은 `gradle/libs.versions.toml`에 있다. Boot와 Cloud BOM은 Gradle `platform()` 의존성으로 들어온다. dependency-management 플러그인은 쓰지 않는다.
- **auth-token**(Spring 비의존):
  - `JwtIssuer`: `suspend`. SecureRandom이 `/dev/urandom`을 읽으므로 `Dispatchers.IO`에서 실행한다.
  - `JwtVerifier`: event loop에서 써도 안전하다. `verifyHandshake(ticket, authorizationHeader)`가 핸드셰이크 자격증명 규칙(티켓이 있으면 티켓, 없으면 Bearer, 둘 다 없으면 `AuthResult.MISSING`)이다. 서비스(self 모드)와 게이트웨이가 함께 쓴다.
  - `StaticKeySource` / `JwksKeySource` / `RefreshingJwksKeySource`(갱신 루프를 스스로 돌리고 `close()`에서 멈춘다. Spring 빈으로 쓴다): 논블로킹 `HttpClient.sendAsync`. 모르는 `kid`가 오면 다시 조회한다. 재조회 간격 제한은 성공 뒤 5초, 실패 뒤 0.5초이고, 동시에 들어온 miss는 한 번만 조회한다. 주기 루프는 실패하면 1초부터 30초까지 백오프하므로 auth-api보다 먼저 떠도 된다(D22).
  - `TicketReplayGuard`: Caffeine. 티켓은 1회용이고, 티켓을 소비하는 쪽이 강제한다.
  - `GatewayHeaders`: `X-User-Id`, `X-Scopes`, `X-Auth-Exp`
  - `TicketMasking`
- **ws-metrics**(Spring 비의존, Micrometer만, D27): 서비스와 게이트웨이가 함께 쓰는 메트릭 정의
  - `WsMeters`: `ws.sessions.active`, `ws.handshake`, `ws.messages`, `ws.messages.dropped`, `ws.close`, `ws.session.duration`, `ws.relay.upstream.connect`. 세션을 묶는 태그는 서비스가 `endpoint`, 게이트웨이가 `route`다.
  - `HandshakeTrace`(`Handshake.kt`): 핸드셰이크 한 건의 측정. 필터가 exchange attribute `HandshakeTrace.ATTRIBUTE`에 넣고, 결과가 정해지는 곳에서 `record`를 부른다(처음 기록만 남는다).
  - `EventLoopLagProbe`: `ws.eventloop.lag`. 예약한 작업이 늦게 실행된 시간을 잰다(`pool` = `netty`/`parallel`).
  - `/actuator/prometheus`로 내보낸다(ws-backend, ws-chat, ws-gateway). 프로세스는 `application` 태그로 구분한다.
- **auth-api**: `/auth/token`(선택 `ttlSeconds`, 1..access-token-ttl, 3000 만료 테스트용, D23), `/auth/ws-ticket`(Bearer access token 필요. 티켓에는 access token 만료 시각을 담은 `sexp` 클레임이 있고, 이것이 WS 세션 수명의 상한이 된다), `/.well-known/jwks.json`
- **ws-backend-core**(라이브러리, 패키지 `com.example.wspoc.wscore`, AutoConfiguration으로 등록, D17): 모든 WebSocket 서비스가 공유하는 런타임
  - 엔드포인트는 `@WsEndpoint("/ws/...")`를 붙인 `WebSocketHandler` 빈이다(클래스 또는 `@Bean` 메서드). `auth = WsEndpoint.Auth.NONE`이면 익명 엔드포인트가 된다: 핸드셰이크 인증과 3000 만료가 없지만 heartbeat와 drain은 그대로 적용된다(D20). `WsEndpointHandlerMapping`이 기동 시 이들을 모으고 경로가 중복되면 바로 실패한다(D16). WebFlux의 raw WebSocket에는 `@RequestMapping` 모델이 없고, STOMP는 Servlet 전용이다.
  - `WsHandshakeAuthFilter`는 **업그레이드 전에** 401(drain 중이면 503)로 거절한다. principal은 `exchange.mutate().principal()`로 넘기고, 핸들러는 `HandshakeInfo.principal`로 읽는다.
  - `ManagedWsHandler`는 세션마다 `ManagedSession`을 만들어 코루틴 하나로 돌린다. 수명 관리 코드는 모두 `ManagedSession.kt`에 있다(송신, 수신, 수명, 프로토콜 예외, 종료 기록 순). 서비스는 세션별 `WsSessionProtocol`(`onOpen`/`onText`/`onClose`)만 제공하면 된다. 보통은 `JsonWsProtocol`을 쓰며, 모든 서비스가 같은 `{"type":...}` 분기와 오류 응답을 갖게 된다. 수명 관리에 포함되는 것:
    - 크기 제한이 있는 송신 `Channel`: 응답은 `send`로 backpressure를 걸고, push와 broadcast는 `trySend`로 가득 차면 버린다(`ws.messages.dropped` 메트릭으로 집계)
    - heartbeat 타임아웃이면 3008(Timeout)로 닫는다
    - 토큰이 만료되면 3000(Unauthorized)으로 닫는다
    - 프로토콜 콜백이나 `session.scope`에 띄운 코루틴이 예외를 던지면 그 세션만 1011(Internal Error)로 닫는다(D24)
    - 메트릭(D27): 엔드포인트별 활성 세션, 메시지 수, 버린 메시지 수, close 코드, 세션 수명. 핸드셰이크는 필터(거절)와 `HandshakeMeteringWebSocketService`(성공)가 기록한다
    - `DrainCoordinator`(SmartLifecycle)가 종료 시 세션을 1001(Going Away)과 jitter로 닫는다. 핸드셰이크 검사 뒤에 drain이 시작돼 snapshot에 못 든 세션은 등록 직후 스스로 1001로 닫는다(D24)
  - 모든 close 코드는 `CloseCodes`에 있고 RFC 6455 §7.4에 맞아야 한다: 1000–1003, 1007–1011, 3000–4999(D13). `CloseCodesRfc6455Test`가 강제한다. SCG는 relay 경로에서 그 밖의 코드(예: 1012–1014)를 1002로 바꾼다(D14).
- **ws-backend**(`modules/ws-backend-service-default`): `EchoPushProtocol`(echo/subscribe/unsubscribe). 서비스 설정은 `PushProperties`(`ws.push`)다.
- **ws-chat**(`modules/ws-backend-service-chat`): `ChatProtocol`(join/say/leave). `ChatRooms`의 join/leave는 모두 `compute` 안에서 set을 바꾼다(밖에서 바꾸면 빈 방 삭제와 경합한다, D24). `ChatRooms`는 인스턴스별 메모리라 여러 대를 띄우면 방이 쪼개진다. 인스턴스 간 fan-out은 후속 과제다(D18).
- **ws-gateway**:
  - 인증은 라우트 단위다(D20). `GatewayEdgeFilter`는 최우선 순위 GlobalFilter이고 라우팅되는 모든 요청에 적용된다: 클라이언트가 보낸 신원 헤더를 항상 제거하고, drain 중에는 업그레이드에 503을 준다. 라우트 필터 `WsAuth`(`WsAuthGatewayFilterFactory`, `filters: [WsAuth]`로 선언)는 그 라우트의 모든 요청을 인증하고, 신원 헤더를 넣고, `ticket`과 `Authorization`을 제거한다. 401이면 chain을 호출하지 않으므로 upstream 연결이 열리지 않는다. 순서는 Edge + 1로 고정해서 헤더 제거가 항상 주입보다 먼저 실행된다.
  - 인증이 필요한 새 라우트는 반드시 `WsAuth`를 선언한다. 빠뜨려도 backend가 거절하지만(fail-closed), 클라이언트는 401 대신 핸드셰이크 실패를 보게 된다.
  - 라우트는 YAML `spring.cloud.gateway.server.webflux.routes`에 선언한다(`RouteLocator` 빈 없음, D15). 서비스마다 라우트 하나이고 경로는 겹치지 않는다(D18):
    - `Path=/ws/echo,/ws/push` → `lb:ws://ws-backend`, `WsAuth` 있음
    - `Path=/ws/chat` → `lb:ws://ws-chat`, `WsAuth` 있음
    - `Path=/ws/public/push` → `lb:ws://ws-backend`, 인증 없음(익명 읽기 전용 push)
    - 라우트 경로는 서비스가 실제로 여는 `@WsEndpoint` 경로만 적는다. `/**`로 넓히면 서비스에 없는 경로가 404 대신 "101 뒤 1002"가 되고 티켓도 소모된다(D24).
    - 그 밖의 `/ws/...`는 인증 전에 404다. GlobalFilter는 매칭된 라우트에서만 실행되기 때문이다.
  - 서비스를 추가할 때는 `loadbalancer.eager-load.clients`와 simple-discovery 인스턴스에도 추가한다.
  - `static` 프로필(`application-static.yml`)은 실험 E3용으로 `${WS_BACKEND_URI}`와 `${WS_CHAT_URI}`로 보낸다. 프로필은 라우트 목록 전체를 교체하므로, 덮어쓸 때는 모든 라우트를 다시 정의한다.
  - relay 세션을 다루는 코드는 `gateway.relay` 패키지에 있다. `RelaySessionTracking`(BeanPostProcessor)이 SCG의 빈 두 개를 감싼다: `WebSocketService` → `TrackingWebSocketService`(세션 등록, drain, 프레임 수, close 코드, 로그), `WebSocketClient` → `TrackingWebSocketClient`(upstream 연결).
  - 메트릭(D27): 위 두 클래스가 잰다. SCG는 101을 먼저 응답하므로 upstream 실패는 `ws.handshake`가 아니라 `ws.relay.upstream.connect{result=error}`에 나온다.
  - `GatewayDrainCoordinator`는 종료 시 relay 중인 세션을 1001과 jitter로 닫는다. SCG는 세션을 외부에 노출하지 않으므로 `TrackingWebSocketService`가 세션을 등록한다. SCG 자체 필터는 건드리지 않는다. 503 검사 뒤에 drain이 시작된 업그레이드는 upstream을 열지 않고 바로 1001로 닫는다(D24).
- **ws-console**(:8060, 개발 도구, D23): 빌드 없는 vanilla JS 화면(`src/main/resources/static/`)을 서빙하는 WebFlux 앱이다. 게이트웨이를 대상으로 ws-backend/ws-chat의 모든 케이스를 눌러 볼 수 있다.
  - 브라우저는 `console.ws-base`에 `?ticket=`으로 WebSocket을 직접 연다.
  - 화면 구조(D25): `core.js`(타임라인, 연결, 패널, 프레임 표시)와 `app.js`(시나리오별 규칙과 데모)다. 타임라인의 한 줄은 "화면에 보이는 것(view)"과 "오간 프레임(wire)"을 함께 갖고, 보기 방식(나란히/교차/화면/프로토콜)은 `data-mode`와 CSS만으로 바뀐다. 색은 `style.css`의 토큰이고 `html[data-theme]`으로 데이/나이트를 바꾼다.
  - 프레임을 누르면 상세 패널이 열린다(D26). HTTP 호출(토큰, 티켓, 업그레이드 핸드셰이크)은 요청·응답 원문과 재현용 curl을 보여 준다. curl에는 토큰과 티켓 값을 넣지 않고 앞줄에서 새로 받아 `$TOKEN`, `$TICKET`으로 넘긴다. `/api/config`의 `authBase`는 이 curl의 대상 주소로만 쓴다.
  - 주소로 화면을 지정할 수 있다: `#tab=chat&mode=split&theme=dark&demo=1`. 헤드리스 Chrome으로 화면을 확인할 때 쓴다.
  - 토큰과 티켓은 `/api/token`, `/api/ticket`이 auth-api로 중계한다. 그래서 auth-api에 CORS가 필요 없다.
  - 모니터링 탭(D28): `/api/metrics`가 `console.monitor.targets`의 `/actuator/prometheus`를 대신 긁어 지금 값만 JSON으로 준다(`MetricsScraper`, `PrometheusText`). 저장소는 없고, 기록과 계산(초당 값, 구간 합계, p99)은 `monitor.js`가 브라우저에서 한다. 전체 수치는 게이트웨이 기준이다(relay에서는 연결이 게이트웨이와 서비스에 한 번씩 잡힌다). 차트는 라이브러리 없는 SVG이고 색은 대상 순서로 고정이다(`--s1`…). 새 메트릭 제언은 `docs/metrics-proposals.md`에 있다.
  - `/api/metrics` 응답은 `MetricsSnapshot.toJson`이 직접 쓴다. Jackson 바인딩은 kotlin-reflect가 event loop에서 jar를 읽게 만든다(D28).
  - `/api/probe`는 서버에서 핸드셰이크를 시도해 실제 HTTP 상태를 돌려준다. 브라우저는 업그레이드가 거절돼도 1006만 보기 때문이다. `/ws/...` 경로만 받고, 호스트와 query는 서버가 정한다.

## 규칙과 주의할 점

- **event loop를 절대 막지 않는다.** 모든 테스트 JVM에서 BlockHound가 돈다. 피할 수 없는 블로킹은 `Dispatchers.IO`/`boundedElastic`으로 격리하고 이유를 주석으로 남긴다. 1회성 클래스 초기화 예외는 `modules/auth-token/src/testFixtures/.../WsPocBlockHoundIntegration.kt`에 항목마다 이유와 함께 넣는다.
- 핸들러는 `suspend`/`Flow`로 작성하고, Reactor와의 변환은 경계에서만 한다. `WebSocketMessage` payload는 `receive().map{}` 안에서 동기적으로 변환한다. Reactor Netty가 `onNext` 직후 버퍼를 해제하기 때문이다.
- `session.close()`는 `withContext(NonCancellable)`로 감싼다. 쓰는 도중 취소되면 close 프레임이 사라지고 클라이언트는 1006을 받는다(D9).
- 로깅: 메시지 단위 로그는 남기지 않는다. 세션 열림/종료 로그는 전용 `ws.session` 로거에 INFO로 남기고 기본은 켜져 있다(core의 `SessionLog`, gateway의 `RelaySessionLog`, D21). 연결 단위 로그는 결과를 왜곡하므로 부하 테스트에서는 `logging.level.ws.session=WARN`으로 끈다. URI는 경로만 남기고 query(`ticket=`)는 절대 남기지 않는다. access log는 선택(`ws.access-log.enabled`)이고 `ticket=`을 마스킹한다.
- 메트릭 태그에는 값의 개수가 한정된 것만 쓴다(D27). userId, 방 이름, 세션 id, 요청 경로 원문은 쓰지 않는다. 경로는 매칭된 `@WsEndpoint` 선언 경로나 라우트 id로, close 코드는 `WsMeters.closeCodeTag`로 바꾼다. 메시지마다 하는 계측은 Counter 증가까지만 한다.
- WebSocket 업그레이드에서 필터 체인은 101이 나간 시점에 완료된다(세션 종료 시점이 아니다). 응답 객체의 상태 코드는 101이 되지 않는다.
- drain 상태는 `start()`에서 꺼진다. Spring 테스트가 컨텍스트를 멈췄다가 재사용하기 때문이다(D27).
- 새 로그 코드는 kotlin-logging(`KotlinLogging.logger`, lazy 람다)을 쓴다. SLF4J와 Logback은 Spring Boot와 BOM에서 오므로 따로 선언하거나 버전을 고정하지 않는다.
- `= runBlocking { ... }`로 끝나는 Spring 테스트는 `Unit`을 반환해야 한다. 마지막 식이 값이면 메서드가 void가 아니게 되어 JUnit이 조용히 건너뛴다.
- 설정은 `@ConfigurationProperties` data class(`WsProperties`, `GatewayProperties`, `AuthProperties`)에 둔다. 테스트에서 JWKS를 끄려면 URI를 `""`로 둔다. 조건은 빈 값인지로 판단한다.
- SCG는 `GlobalFilter`의 순서를 `Ordered` 구현으로만 읽는다. `@Bean` 메서드의 `@Order`는 무시되고, 순서 없는 필터는 `WebsocketRoutingFilter` 뒤로 밀려 WebSocket 요청에서는 실행되지 않는다.
- 테스트:
  - `ws-backend-core`의 `testFixtures`가 `TestKeys`(auth-api 대역)와 `WsTestClient`를 제공한다. 서비스와 게이트웨이 테스트가 재사용한다.
  - 게이트웨이 E2E 테스트는 같은 JVM에서 실제 서비스를 띄운다: `RelayBackend`와 `RelayChat`이고 둘 다 `RelayApp`의 인스턴스다. 각자 받은 핸드셰이크를 기록하므로 테스트가 라우팅을 확인할 수 있다. 게이트웨이 yml을 읽지 않도록 `spring.config.name=ws-backend-e2e`를 쓴다.
  - 서비스들이 게이트웨이 테스트 classpath에 있으므로, 게이트웨이의 `src/test/resources/application.properties`가 core AutoConfiguration(`WsCoreAutoConfiguration`, `WsMetricsAutoConfiguration`, `MaskedAccessLogConfig`)을 게이트웨이 컨텍스트에서 제외한다. core에 AutoConfiguration을 추가하면 여기에도 추가한다. 스캔되는 테스트 패키지의 보조 Spring 설정은 `@TestConfiguration`이어야 한다.
  - KDoc 안에 `/**`를 쓰지 않는다. Kotlin 중첩 주석이 열린다.
- 모든 코드 주석(KDoc, `//`, yml/toml의 `#`)은 한국어로 쓴다. 식별자, 설정 키, close 코드, RFC 절 이름은 그대로 둔다.
- 현재 마일스톤 범위 안에서 작업한다. 후속 과제는 `docs/progress.md`의 "후속 과제"에 기록한다.
