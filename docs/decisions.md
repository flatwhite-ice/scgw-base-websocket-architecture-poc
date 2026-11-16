# Decisions

설계 결정, 확정 버전, 그리고 이유와 대안을 기록한다.

## D1. 확정 버전 (M0, 2026-11-15)

| 항목 | 버전 | 근거 |
|---|---|---|
| JDK | Temurin 25.0.5 (LTS) | 스펙상 25 LTS 우선. `~/Library/Java/JavaVirtualMachines/temurin-25.0.5`에 설치했다. Homebrew cask는 sudo가 필요해서 Adoptium tarball을 SHA-256 검증 후 사용자 디렉터리에 풀었다 |
| Gradle | 9.8.2 (wrapper) | Initializr 기본값 |
| Kotlin | 2.3.21 | Initializr 기본값, 2.x 최신 안정판 |
| Spring Boot | 4.1.2 | Boot 4.x 최신 안정판 |
| Spring Cloud | 2025.1.4 | Boot 4.1 호환 릴리스 트레인 |
| Spring Cloud Gateway | 5.0.4, `spring-cloud-starter-gateway-server-webflux` | 프로퍼티 prefix는 `spring.cloud.gateway.server.webflux.*`이다. jar의 configuration metadata에서 확인했다 |
| Spring Framework / Reactor Netty | 7.0.10 / 1.3.8 | Boot BOM 관리 |
| kotlinx-coroutines | 1.10.2 | Boot BOM 관리 |
| nimbus-jose-jwt | 10.10 | Maven Central 최신 |
| Caffeine | 3.2.4 | Boot BOM 관리 |
| BlockHound | 1.0.18.RELEASE | Maven Central 최신 |
| ktlint-gradle | 14.2.0 | Gradle Plugin Portal 최신, `ktlint_official` 스타일 |
| kotlin-logging | 8.0.7 | Maven Central 최신(2026-11-10). D21에서 추가 |
| SLF4J / Logback | 2.0.20 / 1.5.38 | Boot BOM 관리. 따로 고정하지 않는다(D21) |

모든 버전은 `gradle/libs.versions.toml`에 고정했다. BOM이 관리하는 라이브러리는 catalog에 버전을 적지 않는다.

## D2. 빌드 구조

- 공통 설정은 `build-logic/`(included build)의 precompiled script plugin으로 둔다.
  - `wspoc.kotlin-conventions`: toolchain, ktlint, JUnit/BlockHound를 담당한다.
  - `wspoc.spring-app-conventions`: Boot, WebFlux, coroutines를 담당한다.
- `io.spring.dependency-management` 플러그인을 쓰지 않는다. 대신 Boot와 Cloud BOM을 Gradle `platform()`으로 가져온다. 이렇게 하면 `auth-token`도 Spring을 런타임 의존성으로 갖지 않으면서 버전을 정렬할 수 있다.
- MVC 의존성(`spring-boot-starter-webmvc*`)은 제거했다. 웹 스택은 WebFlux와 Reactor Netty만 쓴다. gateway 클래스패스에 있는 `tomcat-embed-el`은 Bean Validation의 EL 구현체이며 서블릿 컨테이너가 아니다.

## D3. BlockHound × JDK 25

- 테스트 JVM에 `-XX:+AllowRedefinitionToAddDeleteMethods`(JDK 13+에서 필요, 25에서도 deprecated 상태로 존재)와 `-XX:+EnableDynamicAgentLoading`을 준다.
- 설치는 `blockhound-junit-platform`의 TestExecutionListener가 자동으로 한다. 활성 여부는 `auth-token`의 `BlockHoundSmokeTest`가 매 빌드마다 확인한다.
- 리스크: 향후 JDK에서 이 플래그가 제거되면 BlockHound가 동작하지 않는다. 그때는 BlockHound 후속 버전이 나왔는지 확인한다.

## D4. WS 티켓 1회용 검증 위치 (M1)

- **결정:** 티켓을 소비하는 쪽(relay는 `ws-gateway`, direct는 `ws-backend`)이 `auth-token`의 `TicketReplayGuard`로 검증한다. 소비된 `jti`는 Caffeine에 티켓 만료 시각까지 보관한다.
- **이유:** 핸드셰이크 경로에 auth-api 왕복 홉이 생기지 않는다. 그 홉이 있으면 E1~E4 측정에 섞여 들어간다.
- **한계:** 캐시가 인스턴스마다 따로 있다. 같은 티켓을 *다른* 인스턴스에서는 TTL(30초) 동안 다시 쓸 수 있다.
- **대안(2단계):** ① auth-api에 `POST /auth/ws-ticket/redeem`을 두어 중앙에서 소비한다. 홉이 추가되고 auth-api가 SPOF가 된다. ② Redis `SET jti NX EX ttl`로 처리한다.

## D5. 키 관리 (M1)

- auth-api는 기동할 때 RSA 2048 키를 생성한다(kid `poc-1`). `auth.signing-key.pem-path`로 PKCS#8 PEM을 지정하면 그 키를 쓴다. 재기동해도 키를 유지해야 할 때 필요하다.
- PEM 파싱은 JDK `KeyFactory`로 한다. nimbus의 PEM 파서는 BouncyCastle을 요구하는데, 의존성을 추가하지 않으려고 쓰지 않았다.
- 검증 측(backend, gateway)은 `JwksKeySource`를 쓴다. JDK `HttpClient.sendAsync`로 주기적으로 갱신하고, 모르는 `kid`가 오면 즉시 다시 가져온다(최소 간격 제한 있음). 테스트에서는 `StaticKeySource`를 쓴다.

## D6. 토큰 발급 시 SecureRandom 격리 (M1)

- `UUID.randomUUID()`(jti)와 RSA 서명은 `SecureRandom`을 사용한다. macOS와 Linux의 기본 구현인 NativePRNG는 `/dev/urandom`을 `FileInputStream`으로 읽는다. 이 호출을 BlockHound가 event loop에서 잡아냈다(auth-api 테스트).
- **결정:** `JwtIssuer.issue`를 `suspend`로 바꾸고 내부에서 `Dispatchers.IO`로 전환한다. 발급은 auth-api에서만 일어나므로 부하 실험의 핵심 경로가 아니다.
- 검증 경로(`JwtVerifier`)에는 이 호출이 없다. RSA 공개키 검증은 난수를 쓰지 않는다. 따라서 event loop에서 그대로 실행한다.

## D7. 티켓 세션의 만료 시각 = access token 만료 (M2)

- 티켓의 `exp`(30초)는 **교환 가능 기간**일 뿐이다. 이 값을 세션 만료로 쓰면 티켓으로 연결한 모든 세션이 30초 뒤에 토큰 만료 코드로 끊긴다.
- **결정:** auth-api가 티켓을 발급할 때 원래 access token의 만료 시각을 `sexp` 클레임에 담는다. `JwtVerifier`는 티켓 검증 시 `AuthPrincipal.expiresAt = sexp`로 설정한다. 그래서 ticket으로 연결하든 Bearer로 연결하든 세션은 access token 만료 시각에 3000(D13)으로 종료된다.
- JWT `exp`는 초 단위이므로 `IssuedToken.expiresAt`도 초 단위로 절삭해 claim 값과 일치시켰다.

## D8. 세션 처리 구조 (M2)

- **핸드셰이크 인증:** `CoWebFilter`(`WsHandshakeAuthFilter`)가 업그레이드 전에 401 또는 503을 응답하고 chain을 호출하지 않는다. 인증 결과는 `exchange.mutate().principal(...)`로 넘기고, 핸들러는 `HandshakeInfo.principal`로 받는다.
  - `HandshakeWebSocketService`의 attribute predicate는 *WebSession* 속성만 복사한다. 이 경로를 쓰면 연결마다 세션 스토어 항목이 생기므로 쓰지 않는다.
- **relay 모드 헤더:** `X-User-Id`, `X-Scopes`, `X-Auth-Exp`(epoch seconds). `X-Auth-Exp`는 스펙에 없지만 relay 모드에서도 토큰 만료 종료(3000)를 처리하려면 필요해서 추가했다.
- **세션 코루틴 dispatcher:** `Schedulers.parallel().asCoroutineDispatcher()`
  - `delay()`가 kotlinx의 단일 DefaultExecutor 스레드 대신 Reactor 타이머(코어 수만큼)를 쓴다. 2만 세션 × 1초 push에서 타이머 스레드 하나가 병목이 되는 것을 피하기 위해서다.
  - parallel 스레드는 non-blocking 스레드라 BlockHound 검사 대상이다.
- **outbound:** 세션마다 bounded `Channel`(`ws.outbound-buffer-size`, 기본 256)을 둔다.
  - push는 `trySend`로 보내고, 큐가 가득 차면 드롭하고 `droppedPushMessages`에 집계한다(E2의 "드롭 여부"). D17 이후 이름은 `ManagedWsHandler.droppedMessages`이고 채팅 broadcast의 드롭도 함께 센다. D27에서 이 카운터를 없애고 메트릭 `ws.messages.dropped`(엔드포인트별)로 바꿨다.
  - echo 응답은 `send`로 보내 수신 루프에 backpressure를 건다.
- **inbound:** `receive().map(...)`에서 텍스트를 동기적으로 추출한 뒤 Flow로 넘긴다. Reactor Netty는 `onNext` 직후 프레임 버퍼를 해제하기 때문이다.
- **heartbeat:** `ws.heartbeat.interval`마다 ping을 보내고, pong 없이 연속 `max-missed`(2)회가 되면 **3008 Timeout**으로 종료한다(D13).
  - 서버 업그레이드는 `handlePing(false)`로 설정해 PONG 프레임이 세션까지 전달되게 했다.
- **drain:** `DrainCoordinator`(SmartLifecycle, phase=`DEFAULT_PHASE`)를 둔다.
  - 웹서버 graceful shutdown(phase `DEFAULT_PHASE-1024`)보다 **먼저** stop된다.
  - 신규 핸드셰이크는 503으로 거절하고, 기존 세션은 window 안의 랜덤 지터 시점에 1001 Going Away로 닫는다(D13. 한때 1012였다).
  - `spring.lifecycle.timeout-per-shutdown-phase`(35초)는 drain window(20초)보다 크게 잡았다.
- **실행 프로필**
  - 기본값(프로필 없음) = **relay**: gateway 모드, 8090. 게이트웨이의 기본 라우팅 대상(`localhost:8090`)과 맞물리고 JWKS가 필요 없다. `relay` 프로필은 명시용이며 기본값과 같다.
  - `direct`: self 모드, 8092, JWKS=`http://localhost:8070/.well-known/jwks.json`. relay 인스턴스와 같은 머신에서 함께 띄울 수 있다.
  - JWKS 빈은 `self` 모드이고 URI가 비어 있지 않을 때만 만든다. relay에서는 JWKS 갱신 루프가 돌지 않는다.
  - **변경 이력**
    1. 처음에는 JWKS URI를 `direct` 프로필에만 넣었다. 그래서 프로필 없이 기동하면 self 모드에 키가 없어 실패했다.
    2. 이를 고치려고 기본값을 self로 바꿨다. 그러자 기본 포트 8081이 게이트웨이 대상인데 모드가 맞지 않게 됐다. 게이트웨이가 `ticket`과 `Authorization`을 제거해 forward하므로, self 모드 backend는 401을 낸다.
    3. 최종적으로 기본값을 relay로 정했다. `StartupConfigTest`가 프로필 없음, direct, relay 세 가지 기동을 회귀 검증한다.
  - **보안 전제:** gateway 모드 backend는 `X-User-Id` 등을 그대로 신뢰한다. 게이트웨이를 우회해 직접 접근할 수 있으면 신원 위조가 가능하다. 로컬 PoC에서는 허용한다. K8s(M7)에서는 NetworkPolicy로 backend ingress를 ws-gateway 파드로만 제한해야 한다.

## D9. close 프레임 유실 (1006) 원인과 수정 (M2)

- **증상:** 전체 스위트를 반복 실행하면 약 1/14 확률로 클라이언트가 의도한 close code(당시 1001/4401/4408, 현재 1001/3000/3008) 대신 **1006**(close 프레임 없이 끊김)을 받았다. 이때 서버에는 `Failed to release a message: CloseWebSocketFrame(...freed)` 경고가 남았다.
- **원인:** Reactor Netty의 `sendClose()`는 inbound를 즉시 종료하고, 구독이 취소되면(`doOnCancel`) close 프레임을 해제한다.
  1. 세션 코루틴에서 `session.close()`를 await한다.
  2. inbound가 끝나면서 수신 루프가 종료된다.
  3. `finally { cancelChildren() }`가 close를 await 중인 코루틴까지 취소한다.
  4. 쓰기 중인 프레임이 해제된다.
- **수정:** close 쓰기를 `withContext(NonCancellable)`로 감쌌다. 수정 후 전체 스위트를 16회 반복 실행했고 실패 0건, 경고 0건이었다.
  - 처음에는 "outbound를 먼저 비우고 close"로 고쳤는데 오히려 11/16 실패가 났다. 서스펜션 지점이 늘어나 취소 창이 넓어졌기 때문이며, 이 결과로 원인을 확정했다.
- **실험 영향:** 이 버그가 남아 있었다면 E4(재연결 폭주)에서 drain 시 의도한 코드 대신 1006이 섞여, 클라이언트 재연결 정책 비교가 왜곡됐을 것이다.

## D10. ws-gateway (M3)

- **라우트:** ~~`RouteLocatorBuilder` 빈 + `ws.gateway.backend.mode`~~ → D15에서 YAML(`spring.cloud.gateway.server.webflux.routes`)로 옮겼다.
  - 기본: `lb:ws://ws-backend`
  - `static` 프로필: 고정 URI(`WS_BACKEND_URI`). ClusterIP VIP로 보내는 E3용이다.
  - D18에서 경로별 서비스 2개(ws-backend, ws-chat) 라우트로 늘렸다.
  - 로컬과 compose는 `SimpleDiscoveryClient` 정적 목록을 쓴다. K8s 파드 IP 디스커버리는 M7에서 붙인다.
- **`WsAuthFilter`:** GlobalFilter이고 order는 HIGHEST_PRECEDENCE다. (D20에서 전역 `GatewayEdgeFilter`와 라우트 필터 `WsAuth`로 나눴다. 아래는 당시 구조다.)
  - 모든 요청에서 `X-User-Id`, `X-Scopes`, `X-Auth-Exp`를 제거한다.
  - Upgrade 요청에서만 인증한다.
  - 성공하면 identity 헤더를 주입하고, `ticket` 쿼리와 `Authorization` 헤더를 제거한 뒤 forward한다.
  - 실패하면 401 `setComplete()`로 끝내고 chain을 호출하지 않는다. 따라서 upstream 연결이 생기지 않으며, E2E 테스트에서 backend 수신 기록이 0건인 것으로 확인했다.
- **LoadBalancer eager-load:** `spring.cloud.loadbalancer.eager-load.clients=ws-backend`
  - Spring Cloud LoadBalancer는 서비스별 child ApplicationContext를 **첫 요청 때 event loop에서** 만들고, 이때 class 파일을 파싱한다.
  - BlockHound가 이 호출을 잡았다. 첫 핸드셰이크 지연의 원인이기도 해서, 기동 시점에 미리 만들도록 바꿨다.
- **Drain:** `GatewayDrainCoordinator`(SmartLifecycle, phase=`DEFAULT_PHASE`)가 backend의 drain과 같은 방식으로 동작한다.
  - 신규 업그레이드를 503으로 거절하고, relay 중인 클라이언트 세션을 `ws.gateway.drain.window`(20초) 안의 랜덤 지터 시점에 **1001**로 닫는다.
  - **세션 추적:** SCG는 클라이언트 세션을 private `ProxyWebSocketHandler` 안에 두고, `WebSocketService` 빈에는 `@ConditionalOnMissingBean`이 없다.
    - 그래서 빈을 교체하거나 필터를 복사하지 않고, BeanPostProcessor로 `WebSocketService`를 감싼다(`TrackingWebSocketService`).
    - SCG가 넘기는 핸들러를 래핑해서 세션 시작 시 `RelaySessionRegistry`에 등록하고 종료 시 해제한다. subprotocol은 그대로 위임한다. relay 동작 자체는 바꾸지 않는다.
  - **backend 정리:** 클라이언트 쪽만 닫으면 된다. SCG의 양방향 close 전파(D14)가 1001을 backend로 넘기고, 1001은 RFC 6455 코드라 변환되지 않는다.
  - **검증:** `GatewayDrainTest`(클라이언트 3개 1001, drain 중 503, 게이트웨이와 backend 세션 0)와 8회 반복 실행(실패 0, close 프레임 해제 경고 0)
  - **실측:** 실제 프로세스로 게이트웨이에 SIGTERM을 보냈다. 클라이언트 2개가 0.9초, 7.8초에 1001을 받았고, backend 세션도 전파로 정리됐다. 이어서 graceful shutdown이 완료됐다.
  - `spring.lifecycle.timeout-per-shutdown-phase`는 35초로, drain window(20초)보다 크게 잡았다.
- **SCG 설정 prefix:** `spring.cloud.gateway.server.webflux.httpclient.*`
  - 업스트림 커넥션 풀: `pool.max-connections`, `pool.acquire-timeout`
  - 연결/응답 타임아웃: `connect-timeout`, `response-timeout`
  - WS 프레임 크기: `websocket.max-frame-payload-length`

## D11. 티켓 로그 마스킹 (M2/M3)

- Reactor Netty access log는 기본값이 off다. `ws.access-log.enabled=true`일 때만 켜지며, 켜면 URI의 `ticket=` 값을 `***`로 바꾼다. 구현은 `TicketMasking`과 `MaskedAccessLogConfig`(backend, gateway)이고, 테스트로 원문 토큰이 출력에 없음을 확인했다.
- gateway는 backend로 forward할 때 `ticket`을 제거하므로, relay 구조에서는 backend 로그에 티켓이 남지 않는다.
- **남은 위험:** Spring의 500 에러 로그(`AbstractErrorWebExceptionHandler`)는 요청 URI를 쿼리까지 출력한다. 개발 중 500이 났을 때 실제로 원문 티켓이 찍혔다.
  - 티켓은 30초 만료에 1회용이라 영향이 작다.
  - 완전히 막으려면 에러 핸들러를 커스터마이즈해야 한다. 이 작업은 후속 과제로 남긴다.

## D12. 응답에 처리 서버 표시 (`server` 필드)

- backend가 보내는 모든 메시지(echo, push, error)에 `server` 필드를 넣는다. 형식은 `<spring.application.name>@<host>:<실제 바인딩 포트>`이고, `ws.instance-id`로 덮어쓸 수 있다.
  - 이 값으로 relay 경유 시 어느 backend가 처리했는지, 로드밸런싱이 어떻게 분배됐는지 확인한다.
  - 실제로 backend 2대에서 라운드로빈 분배를 확인했다.
- 호스트 결정 순서: `HOSTNAME`(K8s 파드 이름) → `InetAddress.getLocalHost()`
  - 후자는 DNS 조회로 블로킹될 수 있다. 그래서 빈 생성 시점(main 스레드)에 한 번만 계산한다.
  - 포트는 `WebServerInitializedEvent`에서 받는다. 랜덤 포트(테스트)에서도 실제 값이 들어간다.
- gateway는 응답에 자기 정보를 넣지 않는다. 프레임을 파싱하고 수정하면 relay 홉의 비용이 늘어서, 측정하려는 대상(H5)이 왜곡된다.
- 비용: push 메시지 크기가 약 40B 늘어난다. E2에서는 payloadBytes(256B) 외의 오버헤드로 감안한다.

## D13. close code는 RFC 6455 §7.4 기준

**최종 결정**(사용자 결정): 서버가 보내는 모든 close code를 RFC 6455 §7.4에 맞춘다. 근거로 삼은 RFC 원문은 rfc-editor.org의 `rfc6455.txt`다.

| 용도 | 코드 | RFC 6455 근거 |
|---|---|---|
| 토큰 만료 | **3000** | §7.4.2 "3000-3999 … reserved for use by libraries, frameworks, and applications … registered directly with IANA" (IANA 이름 Unauthorized) |
| heartbeat 타임아웃 | **3008** | §7.4.2 동일 (IANA 이름 Timeout) |
| drain(종료/배포) | **1001** Going Away | §7.4.1 "an endpoint is "going away", such as a server going down" |
| (방어 코드) principal 없는 세션 | 1008 Policy Violation | §7.4.1 |
| 프로토콜 코드의 예외(D24) | 1011 Internal Error | §7.4.1 |

- **규칙:** 서버가 보내는 코드는 §7.4.1에서 프레임에 실을 수 있는 코드(1000–1003, 1007–1011) 또는 §7.4.2 애플리케이션 범위(3000–4999)만 쓴다.
  - 모든 코드는 `CloseCodes` 한 곳에 정의한다.
  - `CloseCodesRfc6455Test`가 이 규칙을 검증한다.
- **이 기준이 필요한 이유:** SCG가 relay할 때 그대로 전달하는 집합과 정확히 같기 때문이다(D14). 이 범위를 벗어나면 relay 클라이언트는 1002 Protocol error를 받는다.
- **변경 이력**
  1. 스펙(`docs/hypotheses.md`, 당시 handoff 문서)대로 4401, 4408, 1001
  2. IANA 등록 코드로 통일하면서 3000, 3008, **1012** Service Restart로 변경
  3. 실제 프로세스로 확인했더니 1012가 SCG에서 1002로 바뀌는 것을 발견했다. RFC 6455 기준으로 재정렬해 drain을 1001로 되돌렸다.
  - 스펙(4401)과의 편차는 토큰 만료 3000 하나만 남았다.
- **검증**
  - 테스트: backend(Self, Gateway mode, Heartbeat, Drain), gateway E2E(3000 relay)
  - 실제 프로세스: gateway를 거친 클라이언트가 backend drain(SIGTERM) 때 **1001**을 받는 것을 raw 소켓으로 확인했다.
- **참고:** 1005는 테스트 클라이언트가 "close 프레임 없이 끊김"을 표시하는 데만 쓰고 전송하지 않는다. §7.4.1이 애플리케이션 표시용으로 지정한 값이다.

## D14. SCG `WebsocketRoutingFilter`의 relay 동작 (5.0.4 소스와 실측)

소스는 `spring-cloud-gateway-server-webflux-5.0.4-sources.jar`의 `org/springframework/cloud/gateway/filter/WebsocketRoutingFilter.java`이고, 아래 줄 번호는 이 파일 기준이다. 실측은 raw 소켓 클라이언트로 close 프레임 바이트를 직접 확인했다.

1. **close code 변환(`adaptCloseStatus`, 216–253행) = RFC 6455 §7.4 원문 기준**

   | backend가 보낸 코드 | 클라이언트에 전달되는 코드 |
   |---|---|
   | 1000–1003, 1007–1011, 3000–4999 | 그대로 |
   | 1004–1006, 1015 (RFC가 프레임 사용을 금지) | 1002 |
   | **1012, 1013**(주석 `// Not in RFC6455`), **1014**, 그 외 | 1002 |

   - 변환은 양방향이다. 클라이언트→backend 방향에도 같은 변환이 적용된다.
   - IANA에 나중에 추가된 1012–1014는 참조 문서가 RFC가 아니라 hybi 메일링 리스트다. 그래서 RFC 원문만 신뢰하는 보수적 해석으로 막혀 있다.
   - 프로토콜 계층은 문제가 아니다. Netty 4.2 `isValidStatusCode`는 1007–1014를 유효로 보고, 실제로 direct 경로에서는 1012가 정상 전달됐다.
   - 우회하려면 이 필터를 끄고(`spring.cloud.gateway.server.webflux.global-filter.websocket-routing.enabled=false`) 소스를 복사해 수정한 필터로 교체해야 한다. 익명 클래스의 private 메서드라 상속으로는 고칠 수 없다. 유지보수 부담과 실험 대상 변경 때문에 채택하지 않았다.
2. **101 선응답**
   - `webSocketService.handleRequest`(111행)가 클라이언트를 먼저 업그레이드한다. backend 연결(`client.execute`, 214행)은 그 뒤에 비동기로 맺는다.
   - 그래서 backend가 drain 중(503)이거나 죽어 있으면, 클라이언트는 **101을 받은 뒤 1002로 종료**된다(실측). direct 구조라면 핸드셰이크 단계에서 503을 받는다.
   - **E4 영향:** relay에서는 재연결 실패가 "핸드셰이크 성공 + 즉시 1002"로 보인다. 재연결 성공률과 핸드셰이크 지표를 해석할 때 구분해야 한다.
3. **backend 비정상 종료(SIGKILL) → 클라이언트는 코드 없는 빈 close 프레임을 받는다**(JDK 클라이언트에서는 1005로 보임). 1014 Bad Gateway는 생성되지 않는다.
   - backend가 close 프레임 없이 끊으면 `proxySession.closeStatus()`가 값 없이 끝난다. 그러면 close 전파(257–260행)가 일어나지 않고, 메시지 펌프 종료와 함께 기본 close가 나간다.
   - direct 구조에서는 같은 상황이 close 프레임 없는 TCP 종료(클라이언트 1006)로 보인다.
4. **메시지 중계(266–291행):** 프레임을 파싱하지 않고 `retain()`한 버퍼를 그대로 반대편 세션으로 보낸다. gateway가 응답 내용(예: D12의 `server` 필드)을 바꾸지 않는 근거다.

## D15. 게이트웨이 라우트를 YAML로 관리

- **변경:** Kotlin `RouteLocator` 빈과 `ws.gateway.backend.{mode,service-id,uri}`를 없애고, SCG 표준 방식인 `spring.cloud.gateway.server.webflux.routes`(RouteDefinition 리스트)로 옮겼다.
  - SCG 5.0에서 키 이름이 `spring.cloud.gateway.routes` → `spring.cloud.gateway.server.webflux.routes`로 바뀌었다(jar metadata에서 확인).
  - 이유: 라우트를 코드 수정·재빌드 없이 설정(ConfigMap, 프로필, 환경 변수)으로 바꿀 수 있고, 일반적인 SCG 운영 방식과 맞는다.
- **대상 전환:** 기본 `application.yml`은 `lb:ws://ws-backend`(discovery). `static` 프로필(`application-static.yml`)은 `${WS_BACKEND_URI:ws://localhost:8090}`로 보낸다.
  - 주의: 리스트 프로퍼티는 프로필 사이에서 항목 단위로 병합되지 않고 **통째로 교체**된다. 그래서 `routes[0].uri`만 덮어쓰면 id·predicates가 사라진다. static 프로필은 라우트 전체를 다시 정의한다.
- **D18 이후:** 라우트가 서비스마다 하나씩(ws-backend, ws-chat) 있다. static 프로필은 `WS_BACKEND_URI`, `WS_CHAT_URI`를 쓴다.
- **검증:** `RouteDefinitionTest`가 두 경우의 바인딩 결과(uri)를 확인하고, `GatewayE2ETest`/`GatewayDrainTest`가 YAML 라우트로 실제 relay를 확인한다.

## D16. WebSocket 엔드포인트를 `@WsEndpoint`로 등록

- **변경:** `WsConfig`에 경로를 직접 적던 `SimpleUrlHandlerMapping` 빈을 없앴다. 대신 `WebSocketHandler` 빈에 `@WsEndpoint("/ws/...")`를 붙이면 `WsEndpointHandlerMapping`이 기동 시점에 모아서 등록한다.
  - 붙이는 위치: `WebSocketHandler`를 구현한 클래스, 또는 `@Bean` 메서드. echo와 push는 같은 핸들러 클래스를 설정만 달리 쓰므로 `@Bean` 메서드에 붙였다(D17 이후 `ManagedWsHandler` + `EchoPushProtocol`).
  - 잘못된 구성(WebSocketHandler가 아닌 빈, 경로 없음, 경로 중복)은 기동 시점에 실패한다(`WsEndpointHandlerMappingTest`).
- **왜 `@RequestMapping`/`@MessageMapping`이 아닌가:**
  - WebFlux의 순수 WebSocket에는 어노테이션 매핑 모델이 없다. URL 매핑은 업그레이드 때 한 번뿐이고, 연결 안의 메시지 분기(`type`)는 핸들러가 직접 한다.
  - STOMP `@MessageMapping`은 Servlet 스택 전용이라 쓸 수 없다(MVC 금지).
  - RSocket `@MessageMapping`은 WebFlux에서 되지만 와이어 프로토콜이 바뀌어 PoC 측정 대상이 달라진다.
- **매핑 순서:** order `-1`로 어노테이션 컨트롤러(order 0)보다 먼저 본다. 이전과 같다.

## D17. 공통 런타임 `ws-backend-core` 분리

- **배경:** 실전처럼 서로 다른 WebSocket 서비스 두 개(ws-backend, ws-chat)를 두기로 했다(D18). 두 서비스는 핸드셰이크 인증, 세션 수명 관리, drain이 같고 메시지 처리만 다르다.
  - 스펙(`docs/hypotheses.md`)은 ws-backend 하나를 전제로 한다. 이 결정과 D18이 그 차이다.
- **모듈:** `modules/ws-backend-core`(라이브러리, bootJar 없음, 패키지 `com.example.wspoc.wscore`). 새 convention 플러그인 `wspoc.spring-library-conventions`를 쓴다.
  - 옮긴 것: `WsHandshakeAuthFilter`, `HandshakeAuthenticator`, `SessionRegistry`, `DrainCoordinator`, `ServerIdentity`, `CloseCodes`, `@WsEndpoint`/`WsEndpointHandlerMapping`, `WsProperties`(`ws.*`), access log 마스킹, testFixtures(`TestKeys`, `WsTestClient`)
  - `ws.push` 설정은 ws-backend 전용 `PushProperties`로 분리했다. 설정 키는 그대로다.
- **등록 방식: AutoConfiguration.** 서비스 앱의 component scan(`com.example.wspoc.backend`, `com.example.wspoc.chat`)에 기대지 않는다. 의존성을 넣으면 붙는다.
  - 부작용: ws-gateway 테스트 클래스패스에는 E2E용으로 서비스 앱이 들어 있어 core AutoConfiguration이 게이트웨이 컨텍스트에도 붙으려 한다(`webSocketService` 빈 충돌). 게이트웨이 `src/test/resources/application.properties`에서 `spring.autoconfigure.exclude`로 뺀다. 운영 게이트웨이에는 core가 없다.
- **세션 런타임과 프로토콜 분리:** 기존 `WsSessionHandler`를 둘로 나눴다.
  - `ManagedWsHandler`(core): 등록, bounded 송신 큐, heartbeat 3008, 토큰 만료 3000, drain 1001, 취소되지 않는 close(D9). 엔드포인트마다 하나.
  - `WsSessionProtocol`(서비스): `onOpen` / `onText` / `onClose`. 세션마다 새로 만든다. `WsSessionContext`로 `send`(backpressure)와 `trySend`(드롭)를 쓴다.
  - `JsonWsProtocol`(core): `{"type":...}` 분기와 오류 응답(`invalid json`, `unknown type`) 형식을 서비스 간에 맞춘다.
- **검증:** 리팩터링 커밋에서는 기존 ws-backend·gateway 테스트를 수정 없이(import만 변경) 모두 통과시켰다.

## D18. 서비스별 경로 라우팅과 ws-chat

- **서비스:** ws-chat(`modules/ws-chat`, D30 이후 `modules/ws-backend-service-chat`, relay 8091 / direct 8093)을 추가했다. 엔드포인트는 `/ws/chat` 하나이고 방 단위 채팅이다(join / say / leave).
- **게이트웨이 라우트:**
  - `ws-backend`: `Path=/ws/echo,/ws/push` → `lb:ws://ws-backend`
  - `ws-chat`: `Path=/ws/chat/**` → `lb:ws://ws-chat` (`/ws/chat` 자신도 매칭). D24에서 `Path=/ws/chat`으로 좁혔다.
  - ws-backend 경로를 `/ws/**`가 아니라 **명시적인 목록**으로 적었다. 경로가 겹치지 않으면 라우트 `order`에 결과가 좌우되지 않고, 새 서비스를 추가할 때 기존 라우트를 건드리지 않아도 된다.
  - 어느 라우트에도 맞지 않는 `/ws/...`는 게이트웨이가 404로 응답한다. GlobalFilter(당시 `WsAuthFilter`, 지금은 `GatewayEdgeFilter`)는 매칭된 라우트에서만 실행되므로 인증 전에 404가 나고, upstream 연결도 없다.
  - LoadBalancer eager-load 대상과 simple discovery 목록에 `ws-chat`을 추가했다.
- **경로 prefix를 떼지 않는다(StripPrefix 없음):** 클라이언트가 보는 경로와 서비스의 `@WsEndpoint` 경로가 같다. 기존 `/ws/echo` 클라이언트가 그대로 동작한다.
- **한계: 방은 인스턴스 로컬이다.** `ChatRooms`는 프로세스 메모리에 있어서 ws-chat을 여러 대 띄우면 같은 방 이름이라도 인스턴스마다 멤버가 따로다. 인스턴스 간 fan-out(Redis pub/sub 등)은 후속 과제로 둔다.
  - broadcast는 `trySend`로 보낸다. 느린 멤버 하나 때문에 다른 멤버가 막히지 않는 대신, 큐가 가득 찬 멤버의 메시지는 버려진다.
- **검증:** `ChatTest`(방 전달, 방 분리, leave/disconnect, 오류, 401, `/ws/echo` 404), `GatewayE2ETest`(경로별로 해당 서비스만 핸드셰이크를 받음, 미라우팅 404), `RouteDefinitionTest`(discovery·static 각 2개 라우트)

## D19. 로컬 포트 재배치

| 앱 | 포트 | 이전 |
|---|---|---|
| auth-api | 8070 | 8080 |
| ws-gateway | 8080 | 8082 |
| ws-backend (relay, 기본) | 8090 | 8081 |
| ws-chat (relay, 기본) | 8091 | 8084 |
| ws-backend (`direct`) | 8092 | 8083 |
| ws-chat (`direct`) | 8093 | 8085 |

- 클라이언트 진입점인 게이트웨이를 8080에 두고, 인증은 8070, WebSocket 서비스는 809x 대역에 모았다. direct 인스턴스는 relay 인스턴스 바로 뒤 번호를 쓴다.
- 스펙(`docs/hypotheses.md` §5: auth 8080, backend 8081, gateway 8082)과 다르다. 바뀐 것은 로컬 기본 포트뿐이고, K8s에서는 Service가 포트를 정한다.
- 바꾼 곳: 각 모듈 `application*.yml`(server.port, JWKS URI, 게이트웨이 discovery 목록과 static 기본 URI), `scripts/WsSmoke.java` 기본값, 문서. 이 문서와 progress.md의 과거 기록에 나오는 포트는 당시 값이다.

## D20. 게이트웨이 인증을 라우트 필터(`WsAuth`)로 전환하고 익명 엔드포인트 추가

- **배경:** 인증 없이 제공해야 하는 WebSocket이 생길 수 있다. 전역 필터는 모든 라우트를 인증하므로, 인증 여부를 라우트마다 YAML로 선언하게 바꿨다.
  - 스펙(`docs/hypotheses.md` §5.4)은 GlobalFilter `WsAuthFilter`를 명시한다. 이 결정이 그 스펙과 다른 점이다.
- **게이트웨이 필터 두 개**
  - `GatewayEdgeFilter`(GlobalFilter, HIGHEST_PRECEDENCE, 모든 라우트): 클라이언트 신원 헤더(`X-User-Id`, `X-Scopes`, `X-Auth-Exp`) 제거와 drain 중 업그레이드 503. 익명 라우트에서도 위조 헤더가 backend로 가면 안 되고, drain은 인스턴스 단위라서 전역에 둔다.
  - `WsAuthGatewayFilterFactory`(라우트 필터 `WsAuth`): `routes[].filters: [WsAuth]`로 선언한 라우트만 인증한다. 검증, 신원 헤더 주입, `ticket`·`Authorization` 제거, 실패 시 chain 없이 401은 이전과 같다.
  - **순서:** SCG는 global과 route 필터를 합쳐 order로 정렬한다. WsAuth는 `OrderedGatewayFilter(HIGHEST_PRECEDENCE + 1)`로 고정했다. 헤더 제거(Edge)가 항상 주입(WsAuth)보다 먼저 실행된다. 기본 라우트 필터 order(선언 순서 1, 2, …)에 맡기면 다른 필터와 섞일 때 순서가 흔들린다.
  - **동작 변화:** 예전에는 Upgrade 요청만 인증했다. 이제 WsAuth 라우트는 일반 HTTP 요청도 인증한다(예: `GET /ws/echo` → 401). 인증이 필요하다고 선언한 라우트는 요청 종류와 무관하게 인증한다.
- **라우트:** `ws-backend-public`(`/ws/public/**`, WsAuth 없음), `ws-backend`(`/ws/echo,/ws/push`, WsAuth), `ws-chat`(`/ws/chat/**`, WsAuth). static 프로필도 같다. D24에서 경로를 `/ws/public/push`, `/ws/chat`으로 좁혔다.
- **backend 익명 엔드포인트(core):** `@WsEndpoint(paths, auth = NONE)`
  - `WsHandshakeAuthFilter`가 그 경로의 인증을 건너뛰고 익명 principal(`WsPrincipal.anonymous()`)로 연다. 경로 판단은 핸들러 매핑과 같은 PathPattern 규칙이다.
  - 자격증명이나 신원 헤더가 와도 쓰지 않는다. drain 503은 익명에도 적용한다.
  - 익명 세션에는 토큰 만료 종료(3000)가 없다. heartbeat(3008)와 drain(1001)은 같다.
  - 예시는 ws-backend `/ws/public/push`다. 읽기 전용이라 클라이언트 메시지에 `read only` 오류로 응답한다. 익명 사용자가 주기나 payload를 키워 부하를 만들지 못하게 한다.
- **fail-closed:** 인증 엔드포인트로 가는 라우트에 WsAuth를 빠뜨려도, Edge가 신원 헤더를 지우므로 backend(gateway 모드)가 401로 거절해 세션이 열리지 않는다.
  - `MissingAuthFilterTest`에서 확인했다. 클라이언트는 핸드셰이크 실패로 끝났고, 위조 `X-User-Id`를 보내도 backend 세션 수는 늘지 않았다.
  - 다만 이 경우 클라이언트가 받는 응답은 backend 401이 깔끔하게 전달된 것이 아니다(SCG의 101 선응답, D14). 그래서 이것은 보조 방어선이다. 1차 방어는 라우트 선언이고, `RouteDefinitionTest`가 라우트별 WsAuth 유무를 고정한다.
- **참고:** 서비스(direct)에서 존재하지 않는 `/ws/` 경로는 인증이 먼저라 자격증명이 없으면 401이고, 있으면 404다. 게이트웨이에서는 라우트가 없으면 항상 404다.

## D21. 세션 열림/종료 로그

- **목적:** 연결이 생기고 끊길 때 세션 단위로 무엇이 일어났는지와 현재 active 세션 수를 로그로 본다. 대상은 ws-backend, ws-chat, ws-gateway다.
- **라이브러리:** `io.github.oshai:kotlin-logging-jvm` 8.0.7(SLF4J 위의 Kotlin 파사드). 메시지를 람다로 넘기므로 로거가 꺼져 있으면 문자열을 만들지 않는다.
  - SLF4J와 Logback은 Spring Boot starter가 이미 넣고 BOM이 버전을 관리한다(2.0.20 / 1.5.38). 따로 선언하거나 고정하지 않는다. 예시로 받은 `logback-classic:1.5.5`를 고정하면 오히려 다운그레이드가 된다.
  - 기존 코드는 그대로 둔다(SLF4J 직접 사용, auth-token의 JUL). 새 로그만 kotlin-logging을 쓴다.
- **로거와 수준:** 전용 로거 `ws.session`에 INFO로 남기고 **기본은 켜 둔다**(각 앱 `application.yml`의 `logging.level.ws.session: INFO`).
  - 부하 실험에서는 `--logging.level.ws.session=WARN`(또는 `LOGGING_LEVEL_WS_SESSION=WARN`)으로 끈다. 2만 세션 규모에서 연결 단위 로그는 결과를 왜곡한다. 이전 규칙("연결 단위 INFO 로그 금지")을 "부하 실험 때는 끈다"로 바꿨다.
- **backend(core `ManagedWsHandler` → `SessionLog`):**
  - `ws session opened id path user anonymous remote active`
  - `ws session closed id path user code reason closedBy durationMs active`
  - `closedBy=server`면 서버가 보낸 코드(3000/3008/1001 등)다. `client`면 상대가 보낸 close 프레임의 코드이고, 프레임 없이 끊기면 1005다. relay에서는 상대가 게이트웨이다.
  - `active`는 그 인스턴스의 `SessionRegistry.activeCount`(모든 엔드포인트 합계)다. 열림은 등록 직후, 종료는 해제 직후의 값이다.
- **gateway(`TrackingWebSocketService` → `RelaySessionLog`):**
  - `ws relay opened id route path user upstream remote active`
  - `ws relay closed id route path user upstream code reason durationMs active`
  - `user`는 WsAuth가 넣은 `X-User-Id`이고, 익명 라우트는 `-`다. `upstream`은 LoadBalancer가 고른 `host:port`다. `active`는 relay 중인 클라이언트 세션 수다.
- **민감 정보:** URI는 경로만 남긴다. 익명 라우트는 쿼리를 지우지 않으므로, `?ticket=`이 붙어 와도 로그에 남지 않게 하기 위해서다(`RelaySessionLogTest`).
- **종료 코드를 읽는 법:** relay에서 클라이언트가 close 프레임 없이 끊으면 게이트웨이는 1006, backend는 1002로 남는다. SCG가 1006을 1002로 바꿔 전달하기 때문이다(D14).
- **검증:** `SessionLogTest`(열림/종료, 서버 종료 3000, 익명, WARN이면 꺼짐), `RelaySessionLogTest`(route/user/upstream, 쿼리 미기록)

## D22. JWKS 초기 조회 실패 후 빠른 복구

- **문제:** 게이트웨이나 direct 서비스가 auth-api보다 먼저 뜨면 첫 JWKS 조회가 실패한다. 이후 모르는 `kid`가 와도 재조회 간격 제한(`minRefetchInterval` 5초) 때문에 조회하지 않았다. 그래서 auth-api가 떠 있어도 최대 5초 동안 정상 티켓이 401이었다. 주기 루프도 실패 후 `refreshInterval`(5분)을 그대로 기다렸다.
- **수정(`JwksKeySource`):**
  - 재조회 간격을 직전 조회 결과에 따라 나눴다. 성공 뒤에는 `minRefetchInterval`(5초)을 유지한다. 엉뚱한 `kid`를 계속 보내는 요청에 auth-api가 두들겨 맞지 않게 하기 위해서다. 실패 뒤에는 `failureRetryInterval`(0.5초)이다.
  - 재조회 여부는 lock 안에서 다시 판단한다. 동시에 들어온 miss는 한 번만 조회하고, 기다리는 동안 다른 코루틴이 받아 온 키를 그대로 쓴다.
  - 주기 루프는 실패하면 1초부터 두 배씩, 최대 30초까지 백오프하며 다시 시도한다. 핸드셰이크가 없어도 auth-api가 뜨면 곧 키를 받는다.
  - 실패 뒤 첫 성공 때 `JWKS refresh ... recovered` INFO 로그를 남긴다.
- **검증:**
  - `JwksKeySourceTest`에 4개를 추가했다. 실패 직후 miss 즉시 재조회, 실패 후 재시도 빈도 제한, 동시 miss 1회 조회, 주기 루프 백오프 복구다.
  - 실제 프로세스: gateway와 direct backend를 auth-api보다 먼저 띄우고, auth-api 기동 직후 바로 smoke를 실행했다. 결과는 **ALL OK**였고, 첫 핸드셰이크 시점에 `recovered`가 기록됐다.

## D23. 브라우저 테스트 콘솔(ws-console)과 auth-api `ttlSeconds`

- **목적:** ws-backend(echo, push, 공개 push)와 ws-chat의 각 케이스를 브라우저 화면에서 눌러 보고 정상 동작을 눈으로 확인한다. 지금까지는 CLI(websocat, `WsSmoke.java`)로만 확인할 수 있었다.
- **구성:** 새 모듈 `modules/ws-console`(:8060)이다. 스펙에 없는 개발 도구다.
  - Spring Boot WebFlux 앱이 빌드 없는 정적 화면(vanilla JS, `static/index.html`·`app.js`·`style.css`)을 서빙한다. 로컬에 node가 없고, Gradle 빌드 하나로 끝나게 하려는 것이다.
  - 대상은 게이트웨이(relay, `console.ws-base`, 기본 `ws://localhost:8080`)만이다. 브라우저 WebSocket이 `?ticket=`으로 게이트웨이에 직접 연결한다(브라우저는 `Authorization` 헤더를 붙일 수 없다).
- **auth-api 중계:** 화면은 콘솔 서버의 `/api/token`, `/api/ticket`만 호출하고, 콘솔 서버가 auth-api로 그대로 중계한다(상태와 본문 그대로). 같은 origin이라 auth-api에 CORS를 넣지 않았다.
- **서버 측 probe(`/api/probe`):** 브라우저 WebSocket API는 핸드셰이크가 거절돼도 HTTP 상태(401/404/503)를 알려 주지 않고 close 1006만 준다. 그래서 거절 케이스는 콘솔 서버가 `ReactorNettyWebSocketClient`로 대신 핸드셰이크를 시도하고, Netty `WebSocketClientHandshakeException`의 응답 상태를 돌려준다.
  - 자격증명 종류: `none`, `ticket`, `bearer`, `spoof`(위조 `X-User-Id`/`X-Scopes`만), `replay`(티켓 하나로 두 번)
  - 경로만 받는다. `^/ws(/[A-Za-z0-9._~-]+)+$`이고 `..`가 없어야 하며, 호스트와 query는 서버가 정한다. 콘솔이 임의 주소로 접속하는 도구가 되지 않게 하기 위해서다.
  - 로그에는 경로, 자격증명 종류, 상태만 남긴다(토큰과 티켓은 남기지 않는다).
- **auth-api `ttlSeconds`(스펙과 다름):** `POST /auth/token`에 선택 필드 `ttlSeconds`를 추가했다. 화면에서 토큰 만료 종료(3000)를 몇 초 안에 보기 위해서다.
  - 범위는 `1..auth.access-token-ttl`(기본 900초)이고, 벗어나면 400이다. 기본보다 긴 토큰은 만들 수 없다.
  - 티켓의 `sexp`는 원래 access token 만료를 따르므로, 짧은 토큰으로 받은 티켓의 세션은 그 시각에 3000으로 닫힌다.
- **화면에서 확인하는 케이스:**
  - Echo: 응답의 `userId`, `server`, 왕복 지연. `invalid json`, `unknown type` 오류
  - Push: 자동 구독, subscribe/unsubscribe, 수신 수와 초당 수신
  - 공개 Push: 토큰 없이 수신, `read only` 거절
  - Chat: 참가자 여러 명, 같은 방 전달과 방 격리, `members`, `not joined`/`room required`
  - 토큰 만료: 짧은 TTL로 연결하면 3000 `token expired`
  - 거절 케이스(probe): 미인증 401, 티켓 재사용 101→401, 위조 헤더 401, 미라우팅 404, 공개 101
  - close 코드마다 의미를 표시한다(1001 drain, 1002 relay 백엔드 실패, 1006 비정상/핸드셰이크 거절 등). drain은 게이트웨이를 내려야 해서 버튼으로 만들지 않았다.
- **테스트:** `ConsoleControllerTest`가 auth-api와 게이트웨이 대신 Reactor Netty stub 하나를 띄우고 확인한다. 중계 상태 그대로, probe 101/401/404, replay, 경로 검증 400, 정적 화면 서빙이다. 화면 JS는 자동 테스트 대상이 아니라 실제 프로세스에서 확인한다.
- **BlockHound:** ws-console에서 kotlin-reflect가 내장 타입 메타데이터를 처음 읽는 일이 첫 suspend 컨트롤러 호출 때 event loop에서 일어났다. JVM당 한 번이라 `WsPocBlockHoundIntegration` 허용 목록에 이유와 함께 추가했다.

## D24. 코드 검토에서 나온 수정 4건 (2026-11-15)

M0–M3 코드를 검토하면서 임시 테스트로 재현한 버그 3건과, 코드로 확인한 drain 누락 1건을 고쳤다. 각각 회귀 테스트를 먼저 쓰고, 수정 전 코드에서 실패하는 것을 확인했다.

1. **프로토콜 예외가 세션을 코드 없이 끊던 문제**
   - **증상:** `{"type":{}}`, `{"type":[1]}`을 보내면 오류 응답 없이 연결이 1005로 끊겼다. `{"type":"subscribe","intervalMs":10000000000000}`도 push 1건 뒤에 끊겼다. 세션 로그에는 `closedBy=client code=1005`로 남아 서버 쪽 원인임을 알 수 없었다.
   - **원인:** Jackson 3의 인자 없는 `asString()`은 컨테이너 노드에서 예외를 던진다. `intervalMs`에는 상한이 없어 다음 전송 시각 계산(`Duration.toNanos`)이 overflow했다. 그리고 `ManagedWsHandler`가 프로토콜 예외를 처리하지 않아, 예외가 세션 코루틴을 그대로 끝냈다.
   - **수정:**
     - `JsonWsProtocol`은 `asString("")`을 쓴다. 문자열이 아닌 `type`은 `unknown type` 오류로 응답한다.
     - `ws.push.max-interval-ms`(기본 1시간)를 추가하고 `intervalMs`를 min–max로 자른다.
     - `ManagedWsHandler`는 `onOpen`/`onText`의 예외를 잡아 그 세션만 **1011 Internal Error**로 닫고, 원인을 WARN으로 한 번 남긴다. `WsSessionContext.scope`는 세션 Job 아래의 `SupervisorJob` + `CoroutineExceptionHandler`로 바꿨다. 프로토콜이 띄운 코루틴(push 루프 등)이 실패해도 같은 방식으로 1011이 나간다. 세션 로그는 `closedBy=server code=1011`이 된다.
     - 1011은 RFC 6455 §7.4.1 코드라 SCG가 그대로 전달한다(D13, D14). `CloseCodes.SERVER_ERROR`로 추가했다.
   - **범위 밖:** 송신 쪽(`session.send`) 오류는 이전처럼 세션 코루틴을 끝낸다. 연결이 이미 깨진 경우라 close 프레임을 보낼 수 없다.
   - **테스트:** `ProtocolErrorTest` 4개(콜백 예외 1011이고 다른 세션은 유지, scope 코루틴 예외 1011, 문자열이 아닌 type, 큰 intervalMs)

2. **ChatRooms의 join/leave 경합**
   - **증상:** 마지막 멤버의 `leave`와 다른 세션의 `join`이 겹치면, 새 참가자가 `joined` 응답은 받지만 방의 메시지를 받지 못했다. 20만 회 반복에서 36회 재현됐다.
   - **원인:** `join`이 `computeIfAbsent`로 꺼낸 set에 compute 밖에서 `add`했다. 그 사이 `leave`가 빈 set을 맵에서 지우면, 참가자는 지워진 set에 들어간다.
   - **수정:** `join`도 `compute` 안에서 set을 만들거나 고쳐서 넣는다.
   - **테스트:** `ChatRoomsTest`(방을 계속 비웠다 채우는 스레드와 겹쳐 30만 회 join → broadcast 수신 확인)

3. **게이트웨이 라우트가 서비스 엔드포인트보다 넓던 문제**
   - **증상:** `/ws/chat/**`, `/ws/public/**` 라우트는 서비스에 없는 경로(`/ws/chat/room1`, `/ws/public/nope`)도 받았다. SCG는 101을 먼저 응답하므로(D14) 클라이언트는 404가 아니라 "101 뒤 1002"를 받았고, WsAuth 라우트에서는 티켓도 소모됐다.
   - **수정:** 라우트를 실제 `@WsEndpoint` 경로로 좁혔다. `Path=/ws/chat`, `Path=/ws/public/push`다(기본과 static 프로필 모두). 서비스에 없는 경로는 업그레이드 전에 404가 되고 필터도 실행되지 않는다. D18의 "ws-backend 경로는 명시적인 목록으로 적는다"와 같은 원칙이다.
   - **대안:** 서비스가 하위 경로(`/ws/chat/{room}`)를 받게 하는 방법. 지금 프로토콜은 방을 메시지(`join`)로 고르므로 필요 없다. 그런 엔드포인트가 생기면 라우트와 `@WsEndpoint`를 함께 넓힌다.
   - **규칙:** 서비스에 엔드포인트를 추가하면 게이트웨이 라우트의 `Path`에도 그 경로를 추가한다.
   - **테스트:** `GatewayE2ETest`의 `sub-paths the services do not serve are not routed`(둘 다 404, 서비스 수신 0건)

4. **drain 시작 직전에 들어온 세션 누락**
   - **문제:** 핸드셰이크 검사(503)를 통과한 뒤, 세션이 레지스트리에 등록되기 전에 drain이 시작되면 그 세션은 coordinator의 snapshot에 없다. 1001을 받지 못한 채 남아 있다가 서버가 내려갈 때 close 프레임 없이(1006) 끊긴다. backend와 gateway 모두 같다.
   - **수정:**
     - backend(`ManagedWsHandler`): 등록 직후 `DrainState`를 다시 확인하고, 켜져 있으면 바로 1001로 닫는다. `WsSessionRuntime`에 `drainState`를 추가했다.
     - gateway(`TrackingWebSocketService`): 등록 직후 `GatewayDrainState`를 확인하고, 켜져 있으면 SCG 핸들러를 호출하지 않고(upstream 연결 없음) 바로 1001로 닫는다.
     - coordinator는 "drain 켜기 → snapshot" 순서이고 세션은 "등록 → 확인" 순서이므로, 어느 세션이든 둘 중 한쪽에는 반드시 걸린다. 양쪽에 모두 걸려도 close는 한 번만 나간다.
     - 이 세션들은 jitter 없이 즉시 닫는다. 경합 구간에 든 소수라 재연결 폭주를 만들지 않는다.
   - **테스트:** `LateSessionDrainTest`, `GatewayLateSessionDrainTest`. 인증 필터 뒤에서 drain을 켜는 테스트 필터로 경합 구간을 그대로 만든다.
   - **참고:** SCG는 `GlobalFilter`의 순서를 `Ordered` 구현으로만 읽는다. `@Bean` 메서드의 `@Order`는 보지 않으며, 순서 없는 필터는 `WebsocketRoutingFilter`(체인을 이어 가지 않는다) 뒤로 밀려 WebSocket 요청에서는 실행되지 않는다. 테스트 필터를 쓰다가 확인했다.

검토에서 나왔지만 고치지 않은 것은 progress.md의 후속 과제에 적었다.

## D25. ws-console 화면 재구성: 화면과 프로토콜 교차 보기, 데이/나이트 (2026-11-15)

- **목적:** 시나리오마다 "사용자 화면에 보이는 것"과 "그때 서버와 실제로 오간 메시지"를 함께 본다. 이전 화면은 연결마다 로그 한 줄짜리 목록만 있어서, 프레임이 화면에서 무엇이 되는지를 읽는 사람이 머릿속으로 맞춰야 했다.
- **타임라인 한 줄 = view + wire.** 연결 하나의 모든 일을 시간순으로 한 타임라인에 쌓는다. 한 줄(entry)은 화면 쪽 표현(view: 말풍선, 안내 줄, push 틱)과 프로토콜 쪽 표현(wire: 프레임)을 함께 갖는다. 둘 중 하나만 있는 줄도 있다(예: 내 말이 broadcast로 되돌아온 프레임은 화면에 새로 그릴 것이 없다).
  - **보기 방식 4가지:** 나란히(좌 화면 · 가운데 시각 · 우 프로토콜, 같은 시각끼리 한 행), 교차(화면 한 줄 아래에 그 프레임), 화면(사용자가 보는 것만), 프로토콜(프레임만). 탭마다 기억한다.
  - 네 방식 모두 **같은 DOM**이고 `data-mode`와 CSS grid 배치만 다르다. 방식을 바꿔도 기록이 유지되고, 방식마다 렌더링 코드를 따로 두지 않는다.
  - "나란히"는 패널 폭이 620px 이상일 때만 3열이 된다(container query). 좁은 패널(참가자 여러 명, 모바일)에서는 교차 배치로 보인다.
  - **짝 강조:** 보낸 말풍선과 돌아온 응답 프레임처럼 서로 대응하는 줄은 같은 `link`를 갖고, 마우스를 올리면 함께 강조된다. echo는 메시지 `id`로 맞춘다. chat은 프로토콜에 요청 id가 없으므로, 한 세션의 요청마다 응답이 순서대로 하나씩 온다는 점(joined / left / 내 message / error)을 이용해 FIFO로 맞춘다.
- **프로토콜 쪽에 남기는 것:** 토큰·티켓 발급(HTTP, 콘솔 서버 중계), 핸드셰이크(101 또는 거절), TEXT 프레임(방향, 바이트 수, JSON 색 구분, 누르면 펼침), CLOSE(코드, reason, 의미, 누가 닫았는지).
  - 토큰과 티켓 값은 화면에 남기지 않는다(`Bearer ***`, `?ticket=***`). 이전과 같다.
  - ping/pong은 브라우저가 스스로 처리해 JS에서 볼 수 없다. 화면의 "읽는 법"에 적어 두었다.
- **채팅을 메신저처럼:** 내 말은 오른쪽, 남의 말은 왼쪽(이름, 색 아바타, 연속 발화 묶기), 입장·퇴장·오류는 가운데 안내 줄이다. 내 말은 보내자마자 그리고(`보내는 중…`), broadcast로 되돌아오면 `전달됨 ✓`으로 바꾼다. 거절되면 `전송 실패`다. 방은 칩으로 고르고 나간다.
  - **데모 대화:** alice, bob, carol이 실제 WebSocket으로 주고받는 짧은 대화다. 뒤쪽에서 방 격리(carol만 `#dev`)와 퇴장 뒤 미수신(alice)을 보여 준다. 다른 탭에도 "데모 실행"이 있다.
- **데이/나이트:** 색을 모두 CSS 토큰으로 두고 `html[data-theme]`으로 바꾼다. 처음에는 시스템 설정을 따르고, 토글로 바꾸면 기억한다. 첫 화면이 그려지기 전에 정해야 깜빡이지 않으므로 `index.html` 머리의 인라인 스크립트가 정한다.
- **구성:** 여전히 빌드 없는 vanilla JS다. `core.js`(타임라인, 연결, 패널, 프레임 표시)와 `app.js`(시나리오 규칙, 데모)로 나눴다. 외부 라이브러리와 웹폰트는 쓰지 않는다(오프라인에서도 같은 화면).
- **주소로 화면 지정:** `#tab=chat&mode=split&theme=dark&demo=1`. 헤드리스 Chrome으로 화면을 찍어 확인할 때 쓴다.
- **그 밖의 변경:** 연결과 종료를 한 버튼으로 합쳤다. `alert` 대신 토스트를 쓴다. 거절 케이스는 "기대 / 실제 HTTP 응답 / 브라우저가 보는 것"을 나란히 보여 주고, D24의 하위 경로 404 케이스(`/ws/chat/room1`)를 추가했다(11개).
- **서버 쪽 변경 없음.** 콘솔 서버(`/api`)와 auth-api, 게이트웨이, 서비스의 프로토콜은 그대로다.
- **검증:** `ConsoleControllerTest`가 `core.js`를 포함한 정적 파일 서빙을 확인한다. 화면 동작은 자동 테스트 대상이 아니라 실제 프로세스 + 헤드리스 Chrome(CDP)으로 확인했다(progress.md).

## D26. ws-console 프로토콜 상세 패널과 curl 명령 (2026-11-15)

- **목적:** 프로토콜 줄 하나를 눌러 그 호출의 전체 내용을 본다. 특히 WebSocket 업그레이드 전의 HTTP 호출은 터미널에서 그대로 재현할 수 있는 curl 명령까지 보여 준다. D25의 프레임 줄은 한 줄 요약(누르면 JSON만 펼침)이었다.
- **상세 패널:** 프레임을 누르면(또는 Enter) 그 줄 아래에 열린다. 처음 열 때 한 번만 만든다.
  - **HTTP(토큰·티켓 발급):** 대상(auth-api)과 경유(브라우저 → 콘솔 `/api/...` → auth-api), 요청 원문, 응답 본문, curl
  - **OPEN(업그레이드 핸드셰이크):** 브라우저가 보낸 요청, 101 응답, 협상된 subprotocol·extensions, curl(상태와 헤더 확인), websocat(메시지까지), 게이트웨이가 한 일(티켓 검증과 제거, 신원 헤더 주입)
  - **거절된 핸드셰이크:** 같은 curl. 브라우저는 1006만 보므로, curl로 실제 상태(401, 404, 503)를 확인하라고 안내한다.
  - **TEXT:** 방향, opcode·FIN·MASK, 크기(payload + RFC 6455 §5.2 헤더, 클라이언트 프레임은 마스킹 키 4B 포함), 시각, 메시지 종류 설명, 필드별 의미, 펼친 JSON(복사 가능)
  - **CLOSE:** 코드와 RFC 범위, reason, 의미, 닫은 쪽, close 절차 완료 여부(`wasClean`), 연결 시간, 코드별 안내(1001 재연결, 1002 relay의 101 뒤 실패, 3000 재로그인)
  - **거절 케이스 탭:** 케이스마다 `curl` 버튼으로 같은 요청을 재현하는 명령(상태 코드만 출력)과 기대 출력, 설명을 본다.
- **curl에 비밀값을 넣지 않는다.** 화면에 토큰과 티켓을 남기지 않는 규칙(D23, D25)을 지키면서도 붙여 넣으면 바로 실행되게, 앞줄에서 새 값을 받아 변수로 넘긴다.
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:8070/auth/token ... | jq -r .token)
  TICKET=$(curl -s -X POST http://localhost:8070/auth/ws-ticket -H "Authorization: Bearer $TOKEN" | jq -r .ticket)
  curl -s -i -N --max-time 3 "http://localhost:8080/ws/echo?ticket=$TICKET" -H 'Connection: Upgrade' ...
  ```
  - 티켓은 1회용이라 화면에서 쓴 값을 다시 쓸 수도 없다. 그래서 새로 받는 방식이 맞다.
  - 토큰 발급 본문은 그 연결이 실제로 쓴 값(`userId`, `ttlSeconds`)이다.
  - `jq`가 필요하다(`docs/ws-cli-testing.md`와 같은 전제). 101이면 연결이 열린 채로 남으므로 `--max-time 3`으로 끊는다.
  - 응답 본문의 `token`, `ticket`은 `*** (JWT n자)`로 가린다.
- **대상 주소:** curl은 콘솔 중계(`/api`)가 아니라 실제 대상(auth-api, 게이트웨이)을 가리킨다. 화면이 auth-api 주소를 알아야 해서 `GET /api/config`에 `authBase`를 추가했다. 호출 자체는 여전히 콘솔 서버가 중계한다.
- **한계:** 브라우저 JS는 핸드셰이크 헤더 원문을 읽을 수 없다. 요청·응답 헤더는 WebSocket 표준에 따라 브라우저가 보내는 형태로 적었고, 화면에 그렇게 밝혔다. 프레임 크기도 압축이 없을 때의 계산값이다(협상된 extensions를 함께 보여 준다).
- **검증:** 화면이 만든 명령 17개를 페이지에서 뽑아 실제 서비스에 실행했다(progress.md).
- **정적 파일 캐시(같은 날 추가):** 상세 패널을 넣은 뒤 사용자 브라우저에서 curl이 보이지 않았다. 서버는 최신 파일을 주고 있었고, 원인은 브라우저 캐시였다. 콘솔이 정적 파일을 `Cache-Control` 없이 `Last-Modified`만 붙여 내려서, 브라우저가 `core.js`·`app.js`·`style.css`를 다시 확인하지 않고 예전 것을 썼다(일반 새로고침은 문서만 재검증한다).
  - `spring.web.resources.cache.cachecontrol.no-cache=true`로 정적 파일을 매번 재검증하게 했다(바뀌지 않았으면 304). 개발 도구라 캐시로 얻는 것이 없다.
  - `/`는 welcome page 라우터가 따로 서빙해 이 설정이 적용되지 않는다. `NoCacheIndexFilter`가 같은 헤더를 붙인다.
  - `ConsoleControllerTest`가 `/`, `/app.js`, `/core.js`, `/style.css`의 `Cache-Control: no-cache`를 확인한다.
  - 헤드리스 Chrome 확인은 매번 새 프로필이라 이 문제를 잡지 못했다. 화면을 고친 뒤에는 캐시가 있는 브라우저 기준으로도 본다.

## D27. M4 메트릭: 공통 `ws.*` 메트릭과 스펙에서 벗어난 점 (2026-11-15)
M4 중 메트릭만 먼저 구현했다(Grafana, compose, k6는 M6으로 옮겼다). 스펙 §5.5의 메트릭에 실험 E1~E4에 필요한 것을 더했다.

- **구성:** 새 모듈 `ws-metrics`(Spring 비의존, Micrometer만)가 이름과 태그를 정의한다(`WsMeters`, `EventLoopLagProbe`). ws-backend-core와 ws-gateway가 함께 쓴다. 이름을 한곳에 두어 서비스와 게이트웨이의 수치를 같은 쿼리로 비교한다. 게이트웨이는 core에 의존하지 않으므로 따로 뺐다.
- **노출:** ws-backend, ws-chat, ws-gateway가 `/actuator/prometheus`와 `/actuator/health`만 연다. core가 actuator를 `api`로 가져오므로 core 위의 서비스는 의존성을 따로 적지 않는다. 프로세스는 공통 태그 `application`(`spring.application.name`)으로 구분한다.
  - 지금은 서비스 포트와 같은 포트다. 게이트웨이는 외부에 노출되므로 M7(K8s 배포)에서 management 포트를 분리하거나 Ingress에서 `/actuator`를 막는다(후속 과제).
- **메트릭:**

  | 이름 | 타입 | 태그 | 비고 |
  |---|---|---|---|
  | `ws.sessions.active` | Gauge | scope | |
  | `ws.handshake` | Timer(히스토그램) | scope, `result`, `reason` | `result` = `ok`/`unauthorized`/`draining`/`error` |
  | `ws.messages` | Counter | scope, `direction` | TEXT/BINARY만. ping/pong 제외 |
  | `ws.messages.dropped` | Counter | scope | 서비스만(게이트웨이에는 송신 큐가 없다) |
  | `ws.close` | Counter | scope, `code` | 프레임 없이 끊기면 1005 |
  | `ws.session.duration` | Timer | scope | |
  | `ws.relay.upstream.connect` | Timer(히스토그램) | `route`, `result`, `exception` | 게이트웨이만 |
  | `ws.eventloop.lag` | Timer(히스토그램) | `pool` | `netty`, `parallel`(서비스만) |

  scope 태그는 서비스에서 `endpoint`(`@WsEndpoint` 선언 경로), 게이트웨이에서 `route`(라우트 id)다.
- **태그 값은 개수가 한정된 것만 쓴다.** userId, 방 이름, 세션 id, 요청 경로 원문은 쓰지 않는다.
  - 서비스의 `endpoint`는 요청 경로가 아니라 매칭된 선언 경로다. 매칭이 없으면 `unknown`이다.
  - `ws.close`의 `code`는 1000–1015, 3000, 3008만 그대로 두고 나머지는 `other`다. 클라이언트가 임의의 코드를 보낼 수 있다.
  - `unauthorized`의 `reason`은 `FailureReason`(소문자)이다. 자격증명이 아예 없는 경우를 위해 `MISSING`을 추가했다. E4에서 티켓 재사용(`replayed`)을 가려낸다.
- **스펙에서 벗어난 점: `ws.message.relay.latency`(게이트웨이 Timer)는 만들지 않는다.** 프레임마다 수신 시각을 기억했다가 반대편 쓰기 완료와 짝지어야 해서, 재려는 경로 자체가 느려진다. 중계 지연은 k6에서 echo 왕복 시간을 relay와 direct로 재고 그 차이로 본다(M6 k6 스크립트에서 다룬다).
- **스펙에 없지만 추가한 것:**
  - `ws.relay.upstream.connect`: SCG는 101을 먼저 응답하고 upstream을 나중에 연다(D14). 그래서 upstream 실패는 `ws.handshake`가 아니라 여기에 `result=error`로 나온다. E3(포트 고갈)의 오류 종류는 `exception` 태그로 본다.
  - `ws.eventloop.lag`: E1의 중단 기준이다. Reactor Netty 내장 메트릭에는 대기 작업 수(`reactor.netty.eventloop.pending.tasks`)만 있고 지연 시간이 없다. "100ms 뒤 실행"을 예약하고 늦은 만큼을 기록한다. 세션 코루틴은 Netty가 아니라 Reactor parallel 스케줄러에서 돌므로 서비스는 둘 다 잰다.
  - `ws.messages.dropped`, `ws.session.duration`: E2의 "드롭 여부", E4의 재연결 전후 비교용이다.
- **핸드셰이크 결과를 정하는 방법:** 필터 체인은 세션이 끝날 때가 아니라 업그레이드 응답이 나간 시점에 완료된다. 응답 상태 코드로는 성공을 알 수 없다(Reactor Netty는 101을 핸드셰이커가 직접 쓰고 응답 객체의 상태는 200으로 둔다).
  - 거절(401/503)은 인증 필터가, 성공은 `WebSocketService.handleRequest`의 정상 완료 시점에 기록한다(core는 `HandshakeMeteringWebSocketService`, 게이트웨이는 기존 `TrackingWebSocketService`). 체인이 끝날 때까지 기록이 없으면 `error`다. 한 건은 처음 기록만 남는다(`HandshakeTrace`).
  - 게이트웨이의 `ws.handshake`는 인증까지의 구간이다. upstream 연결은 포함하지 않는다.
- **게이트웨이 계측 방법:** SCG 필터는 건드리지 않고 빈을 감싼다(D24의 drain과 같은 방식).
  - 메시지 수는 클라이언트 쪽 세션을 감싸 `receive()`/`send()`를 지나가는 프레임을 센다(Counter 증가뿐이다).
  - upstream 연결은 `WebSocketClient` 빈을 감싸 잰다. 라우트 id는 Reactor context로 넘긴다(SCG의 프록시 핸들러가 `client.execute()`의 Mono를 그대로 돌려준다).
- **Reactor Netty 내장 메트릭**(스펙 §5.5): 서버는 `NettyServerCustomizer`, 게이트웨이의 upstream 클라이언트는 `HttpClientCustomizer`와 `httpclient.pool.metrics`로 켠다. `uri` 태그는 경로의 첫 구간만 쓴다(`/ws`, `/actuator`, `other`). 연결마다 계측 핸들러가 붙으므로 `ws.metrics.netty`(게이트웨이는 `ws.gateway.metrics.netty`)로 끌 수 있다.
- **함께 고친 것: drain 상태를 다시 시작할 수 있게 했다.** Spring Framework 7의 테스트는 쓰지 않는 컨텍스트를 멈췄다가(`SmartLifecycle.stop`) 재사용할 때 다시 시작한다. `DrainCoordinator`가 stop에서 켠 drain 상태가 그대로 남아, 컨텍스트를 공유하는 테스트 클래스가 생기자 503이 났다. `start()`에서 상태를 끈다(게이트웨이도 같다).
- **확인하지 않은 것:** 내장 메트릭을 켰을 때의 연결당 비용은 재지 않았다. E1/E2에서 켠 것과 끈 것을 비교한다.

## D28. M5 모니터링 콘솔: ws-console의 모니터링 탭 (2026-11-15)

- **ws-console에 "모니터링" 탭을 넣었다.** ws-gateway, ws-backend, ws-chat의 메트릭(D27)을 한 화면에서 본다. 새 메트릭은 만들지 않았다. 있으면 좋았을 것은 `docs/metrics-proposals.md`에 적었다.
- **콘솔 서버가 대신 긁는다(`GET /api/metrics`).** 브라우저가 대상의 `/actuator/prometheus`를 직접 부르면 대상마다 CORS가 필요하다. 대상은 `console.monitor.targets`로만 정하고 요청으로 주소를 받지 않는다. 화면이 쓰는 메트릭(`ws_*`와 JVM·프로세스·Reactor Netty 12개)만 남겨 JSON으로 준다.
  - 실패한 대상이 있어도 응답은 200이고 그 대상만 `up=false`다(이유: `HTTP 404`, `timeout`, 예외 이름).
  - 긁은 결과를 `refresh`(기본 2초)의 절반 동안 재사용한다. 화면을 여러 개 열어도 대상은 1초에 한 번보다 자주 긁히지 않는다.
- **콘솔에는 저장소가 없다.** 기록은 화면이 열려 있는 동안 브라우저가 갖는다(최대 16분). 누적 카운터의 차이로 초당 값과 구간 합계를, 히스토그램 버킷의 차이로 p99를 구한다(Prometheus의 `rate`, `increase`, `histogram_quantile`과 같은 계산). 새로 고치면 기록은 비워진다. 오래 보관하는 것은 M6의 Prometheus가 할 일이다.
  - 탭이 보이지 않아도 수집은 계속한다. 다른 탭에서 데모를 돌린 뒤 넘어와도 그 구간이 차트에 있다. 브라우저 창 자체가 가려지면 수집을 멈추고, 그 구간은 차트에서 끊긴다.
  - 구간 값은 화면을 연 뒤에 늘어난 만큼만 센다. 그 전의 양은 "누적" 열에 있다.
- **화면 구성:** 요약 수치 6개(활성 세션, 핸드셰이크 성공률, 메시지 처리량, 버린 메시지, event loop 지연 p99, upstream 연결 실패) → 프로세스 카드(자원, 라우트·엔드포인트별 세션과 처리량) → 추이 차트 6개 → 분류 표 3개(핸드셰이크 결과, 종료 코드, upstream 연결).
  - **전체 수치는 게이트웨이 기준이다.** relay에서는 연결 하나가 게이트웨이와 서비스에 한 번씩 잡히므로 둘을 더하면 두 배가 된다. 게이트웨이가 보이지 않으면 서비스의 합계를 쓴다. 게이트웨이인지는 `ws_sessions_active`에 `route` label이 있는지로 판단한다(모드를 알려 주는 메트릭이 없다).
  - 차트의 선은 프로세스이고 색은 대상 순서로 고정이다. 라우트·엔드포인트·결과별 구분은 색을 늘리지 않고 카드와 표로 보여 준다.
  - 차트는 라이브러리 없이 SVG로 그린다(콘솔은 빌드 없는 vanilla JS다, D23).
  - 지연과 CPU에는 경고 색을 넣지 않았다. 스펙에 기준값이 없다.
- **"데모 트래픽" 버튼:** 브라우저에서 공개 push(`/ws/public/push`, 토큰 불필요)에 연결 12개를 15초 동안 열고, 자격증명 없는 `/ws/echo` 연결을 3번 시도한다. 차트가 움직이는 것과 `unauthorized{reason=missing}`을 바로 볼 수 있다.
- **응답 JSON은 직접 쓴다(`MetricsSnapshot.toJson`).** Jackson 데이터 바인딩에 맡기면 jackson-module-kotlin이 처음 보는 컬렉션 클래스마다 kotlin-reflect로 메타데이터를 읽고, 이 jar 읽기가 event loop에서 일어난다. BlockHound가 테스트에서 잡았다. 기동 때 미리 한 번 직렬화하는 방법은 런타임 클래스(ArrayList, EmptyList, LinkedHashMap, …)마다 다시 걸려서 버렸다.
- **확인하지 않은 것:** 차트 색의 색각 이상 구분 검사(검사 스크립트가 Node를 요구하는데 로컬에 없다. 검증된 팔레트의 앞 세 색을 그대로 썼지만 배경색이 조금 다르다). direct 인스턴스를 대상에 넣은 구성. 대상이 죽은 상태의 화면은 테스트(`up=false`)로만 확인했고 화면으로는 보지 않았다.

## D29. 모듈 이름 `common-auth` → `auth-token` (2026-11-15)

- **변경:** 모듈 `modules/common-auth`를 `modules/auth-token`으로, 패키지 `com.example.wspoc.auth.common`을 `com.example.wspoc.auth.token`으로 바꿨다. 동작은 바뀌지 않는다.
- **이유:** "common"은 무엇이 들어 있는지 알려 주지 않는다. 이 모듈이 하는 일은 토큰과 티켓의 발급, 검증, 재사용 방지, 마스킹이다. 이름은 다른 모듈(`auth-api`, `ws-metrics`, `ws-backend-core`)처럼 "영역-역할" 형태로 맞췄다.
- **검토한 대안**
  - `auth-jwt`: 가장 구체적이지만, 티켓 재사용 방지·게이트웨이 신원 헤더·마스킹은 JWT 자체가 아니라 이름보다 내용이 넓다.
  - `auth-core`: `ws-backend-core`와 짝이 맞지만 "core"도 추상적이다.
  - `base-module-auth`: `modules/` 아래라 "module"이 겹치고 "base"도 추상적이다.
- 스펙(`docs/hypotheses.md` §2, §4, §5.1)의 `common-auth`는 원문 그대로 두었다. 이 문서의 다른 결정과 progress 기록은 새 이름으로 고쳐 적었다.
- **남은 점:** BlockHound 테스트 설정(`testFixtures`의 `WsPocBlockHoundIntegration`)이 이 모듈에 있다. 인증과 관계없는 것이라 이름과 어긋난다. 따로 옮길지는 후속 과제로 둔다.

## D30. 서비스 모듈 이름 `ws-backend` → `ws-backend-service-default`, `ws-chat` → `ws-backend-service-chat` (2026-11-15)

- **변경:** Gradle 모듈(폴더와 프로젝트 경로)만 바꿨다. `:modules:ws-backend`는 `:modules:ws-backend-service-default`, `:modules:ws-chat`은 `:modules:ws-backend-service-chat`이다. bootJar 이름도 모듈 이름을 따른다.
- **이유:** 모듈 목록에서 `ws-backend`가 공통 런타임(`ws-backend-core`) 위의 서비스 중 하나라는 것, `ws-chat`도 같은 런타임 위의 서비스라는 것이 보이지 않았다. `ws-backend-*` 접두어로 묶었다.
- **바꾸지 않은 것:** 런타임에서 쓰는 서비스 이름이다. `spring.application.name`(`ws-backend`, `ws-chat`), 게이트웨이의 `lb://` discovery id와 라우트 id, 메트릭 `application` 태그, 응답의 `server` 필드, smoke 스크립트와 콘솔의 기대값이 모두 이 이름을 쓴다. 바꾸면 게이트웨이 설정, 메트릭 시계열, 실험 비교 기준이 함께 바뀌므로 모듈 이름 변경과 분리했다. Kotlin 패키지(`com.example.wspoc.backend`, `com.example.wspoc.chat`)도 그대로다.

## D31. 읽기 쉽게 정리: 중복 제거와 큰 파일 분리 (2026-11-15)

ws-console을 뺀 모듈을 정리했다. 외부에서 보이는 동작(엔드포인트, 응답, close 코드, 메트릭 이름과 태그, 설정 키)은 바뀌지 않는다.

- **중복을 한곳으로 모았다.**
  - 핸드셰이크 자격증명 규칙(티켓이 있으면 티켓, 없으면 Bearer)이 서비스(self 모드)와 게이트웨이에 따로 있었다. `JwtVerifier.verifyHandshake`로 옮겼다. 자격증명이 없는 결과는 `AuthResult.MISSING`이다.
  - JWKS 갱신 루프의 수명 관리 클래스(`JwksKeySourceLifecycle`)가 core와 게이트웨이에 하나씩 있었다. auth-token의 `RefreshingJwksKeySource`(`AutoCloseable`) 하나로 바꿨다. Spring에 의존하지 않고, 빈으로 등록하면 컨텍스트가 닫힐 때 `close()`가 불린다.
  - scope 문자열 파싱(`AuthPrincipal.parseScopes`), 핸드셰이크 trace의 exchange attribute 이름(`HandshakeTrace.ATTRIBUTE`), event loop 측정 연결(`EventLoopLagProbe.watch(pool, executors)`)도 각각 한곳에 두었다.
- **큰 파일을 역할별로 나눴다.**
  - `ManagedWsHandler.kt`(289줄) → `ManagedWsHandler`(핸들러, 40줄 남짓)와 `ManagedSession`(연결 하나의 수명). 받은 프레임을 나르던 중간 타입(`Inbound`)은 없앴다.
  - `WsCoreAutoConfiguration` → 메트릭 빈을 `WsMetricsAutoConfiguration`으로 분리했다.
  - 게이트웨이 `RelaySessionTracking.kt`(189줄) → `RelaySessionTracking`(빈을 감싸는 설정), `TrackingWebSocketService`, `TrackingWebSocketClient`.
  - `JwtVerifier.verify`, `ChatProtocol.onMessage`, `EchoPushProtocol.onMessage`는 단계나 메시지 종류별 함수로 나눴다.
- **패키지 이름:** 게이트웨이 `gateway.drain` → `gateway.relay`. drain뿐 아니라 세션 추적, 메트릭, 세션 로그가 함께 있어서 이름이 내용보다 좁았다.
- **달라진 동작 하나:** 서비스가 PONG을 받은 것을 프로토콜 콜백 순서와 무관하게 수신 즉시 기록한다. 이전에는 프레임을 순서대로 넘기는 흐름 안에서 기록해서, 프로토콜이 오래 걸리면 PONG 확인도 함께 밀렸다. heartbeat가 살아 있는 연결을 3008로 끊을 여지가 줄어든다. 이 경우를 재현하는 테스트는 따로 만들지 않았다.
- **하지 않은 것:** core의 `DrainCoordinator`와 게이트웨이의 `GatewayDrainCoordinator`, 두 `MaskedAccessLogConfig`는 모양이 거의 같지만 그대로 두었다. 둘이 함께 쓸 Spring 의존 모듈이 없고, 하나를 만들 만큼 크지 않다.


## D32. 공유 모듈 `ws-server-support`와 구조 정리 (2026-11-17)

Kotlin 코드를 다시 정리했다. 설정 키(`ws.*`, `ws.gateway.*`), 메트릭 이름과 태그, `ws.session` 로그 형식, 엔드포인트, 메시지 형식, yml은 바뀌지 않는다.

- **공유 모듈을 만들었다(D31의 "하지 않은 것"을 뒤집는다).** core와 게이트웨이에 같은 코드가 두 벌씩 있었다. 게이트웨이는 core에 의존할 수 없다(core의 AutoConfiguration이 게이트웨이에 붙으면 안 된다). D31에서는 "하나를 만들 만큼 크지 않다"고 두었지만, 겹치는 조각이 drain 말고도 여섯 군데라 모듈 하나로 모았다.
  - `drain`: `DrainState`(두 클래스가 완전히 같았다), `DrainCoordinator`(닫는 방법만 달랐다). 무엇을 어떻게 닫는지는 `DrainTarget`이 정한다. core `SessionRegistry`는 세션 코루틴에 close를 맡기고, 게이트웨이 `RelaySessionRegistry`는 클라이언트 세션을 취소 불가로 닫는다(D9).
  - `accesslog`: `MaskedAccessLogAutoConfiguration`. 같은 파일이 두 개 있었다.
  - `handshake`: `JwtVerifier.verifyHandshake(request)`, `TICKET_PARAM`, `completeWith(status)`, `exchange.handshakeTrace`
  - `session`: `closeStatusOrNoStatus()`, `SessionLogFormat`
  - `metrics`: `ServerMetrics`
- **원칙: 이 모듈은 빈을 스스로 등록하지 않는다.** 일반 클래스와 함수만 두고, 빈 등록은 core의 AutoConfiguration과 게이트웨이의 `GatewayConfig`가 한다. 그래야 게이트웨이 컨텍스트에 원치 않는 빈이 붙지 않고, 설정 키도 각자 것을 그대로 쓴다. 예외는 access log 하나다: 설정 키(`ws.access-log.enabled`)와 형식이 양쪽에서 같아서 AutoConfiguration으로 두었다. 게이트웨이 테스트의 제외 목록에서 이 항목을 뺐다.
- **합치지 않은 것:** JWKS·`JwtVerifier` 빈 구성(두 줄이고 조건식이 다르다), `WsProperties`와 `GatewayProperties`(설정 키를 유지한다), 두 레지스트리(담는 타입이 다르다).
- **core 패키지:** `protocol`(서비스가 구현하고 쓰는 API: `WsSessionProtocol`, `WsSessionContext`, `JsonWsProtocol`)을 `session`(런타임)에서 떼어 냈다. `protocol`은 `session`을 import하지 않는다. `WsSessionRuntime`은 별도 파일로 뺐다. `ManagedSession.kt`의 내부 구성은 D31 그대로다.
- **그 밖의 분리:** 게이트웨이 `RelayLogContext` → `RelayContext`(로그와 메트릭 태그에 함께 쓰인다). ws-console `ConsoleController.probe` → `ProbeService`(자격증명 종류는 `ProbeCredential` enum), `MetricsSnapshot.kt`. auth-api `AuthExceptions.kt`. 본 코드의 SLF4J 직접 사용을 kotlin-logging으로 바꿨다.
- **테스트:** 5곳에 있던 테스트용 KeySource 설정을 core testFixtures의 `TestKeySourceConfig`로, 12곳에서 반복되던 self 모드 `@SpringBootTest` 설정을 `@SelfModeWsTest`로 모았다. 테스트 파일의 위치와 검증 내용은 그대로다. `DrainCoordinatorTest`(단위 테스트 3개)를 추가했다.
- **달라진 것 두 가지.**
  - drain 운영 로그: 로거가 `com.example.wspoc.support.drain.DrainCoordinator` 하나가 됐고 문구는 `Draining N <대상> over <window>`, `Drain finished, N <대상> still open`이다. 대상은 서비스가 `WebSocket sessions`, 게이트웨이가 `relayed WebSocket sessions`다. 이전의 `Gateway draining …` 접두어는 없다.
  - ws-console probe의 400: 컨트롤러의 `ResponseStatusException` 대신 서비스가 `@ResponseStatus(400)` 예외(`InvalidProbeRequestException`)를 던진다. 상태 코드와 메시지 문구는 같다.
