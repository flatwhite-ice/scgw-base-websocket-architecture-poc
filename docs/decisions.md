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

모든 버전은 `gradle/libs.versions.toml`에 고정했다. BOM이 관리하는 라이브러리는 catalog에 버전을 적지 않는다.

## D2. 빌드 구조

- 공통 설정은 `build-logic/`(included build)의 precompiled script plugin으로 둔다.
  - `wspoc.kotlin-conventions`: toolchain, ktlint, JUnit/BlockHound를 담당한다.
  - `wspoc.spring-app-conventions`: Boot, WebFlux, coroutines를 담당한다.
- `io.spring.dependency-management` 플러그인을 쓰지 않는다. 대신 Boot와 Cloud BOM을 Gradle `platform()`으로 가져온다. 이렇게 하면 `common-auth`도 Spring을 런타임 의존성으로 갖지 않으면서 버전을 정렬할 수 있다.
- MVC 의존성(`spring-boot-starter-webmvc*`)은 제거했다. 웹 스택은 WebFlux와 Reactor Netty만 쓴다. gateway 클래스패스에 있는 `tomcat-embed-el`은 Bean Validation의 EL 구현체이며 서블릿 컨테이너가 아니다.

## D3. BlockHound × JDK 25

- 테스트 JVM에 `-XX:+AllowRedefinitionToAddDeleteMethods`(JDK 13+에서 필요, 25에서도 deprecated 상태로 존재)와 `-XX:+EnableDynamicAgentLoading`을 준다.
- 설치는 `blockhound-junit-platform`의 TestExecutionListener가 자동으로 한다. 활성 여부는 `common-auth`의 `BlockHoundSmokeTest`가 매 빌드마다 확인한다.
- 리스크: 향후 JDK에서 이 플래그가 제거되면 BlockHound가 동작하지 않는다. 그때는 BlockHound 후속 버전이 나왔는지 확인한다.

## D4. WS 티켓 1회용 검증 위치 (M1)

- **결정:** 티켓을 소비하는 쪽(relay는 `ws-gateway`, direct는 `ws-backend`)이 `common-auth`의 `TicketReplayGuard`로 검증한다. 소비된 `jti`는 Caffeine에 티켓 만료 시각까지 보관한다.
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
  - push는 `trySend`로 보내고, 큐가 가득 차면 드롭하고 `droppedPushMessages`에 집계한다(E2의 "드롭 여부"). D17 이후 이름은 `ManagedWsHandler.droppedMessages`이고 채팅 broadcast의 드롭도 함께 센다.
  - echo 응답은 `send`로 보내 수신 루프에 backpressure를 건다.
- **inbound:** `receive().map(...)`에서 텍스트를 동기적으로 추출한 뒤 Flow로 넘긴다. Reactor Netty는 `onNext` 직후 프레임 버퍼를 해제하기 때문이다.
- **heartbeat:** `ws.heartbeat.interval`마다 ping을 보내고, pong 없이 연속 `max-missed`(2)회가 되면 **3008 Timeout**으로 종료한다(D13).
  - 서버 업그레이드는 `handlePing(false)`로 설정해 PONG 프레임이 세션까지 전달되게 했다.
- **drain:** `DrainCoordinator`(SmartLifecycle, phase=`DEFAULT_PHASE`)를 둔다.
  - 웹서버 graceful shutdown(phase `DEFAULT_PHASE-1024`)보다 **먼저** stop된다.
  - 신규 핸드셰이크는 503으로 거절하고, 기존 세션은 window 안의 랜덤 지터 시점에 1012 Service Restart로 닫는다(D13).
  - `spring.lifecycle.timeout-per-shutdown-phase`(35초)는 drain window(20초)보다 크게 잡았다.
- **실행 프로필**
  - 기본값(프로필 없음) = **relay**: gateway 모드, 8081. 게이트웨이의 기본 라우팅 대상(`localhost:8081`)과 맞물리고 JWKS가 필요 없다. `relay` 프로필은 명시용이며 기본값과 같다.
  - `direct`: self 모드, 8083, JWKS=`http://localhost:8080/.well-known/jwks.json`. relay 인스턴스와 같은 머신에서 함께 띄울 수 있다.
  - JWKS 빈은 `self` 모드이고 URI가 비어 있지 않을 때만 만든다. relay에서는 JWKS 갱신 루프가 돌지 않는다.
  - **변경 이력**
    1. 처음에는 JWKS URI를 `direct` 프로필에만 넣었다. 그래서 프로필 없이 기동하면 self 모드에 키가 없어 실패했다.
    2. 이를 고치려고 기본값을 self로 바꿨다. 그러자 기본 포트 8081이 게이트웨이 대상인데 모드가 맞지 않게 됐다. 게이트웨이가 `ticket`과 `Authorization`을 제거해 forward하므로, self 모드 backend는 401을 낸다.
    3. 최종적으로 기본값을 relay로 정했다. `StartupConfigTest`가 프로필 없음, direct, relay 세 가지 기동을 회귀 검증한다.
  - **보안 전제:** gateway 모드 backend는 `X-User-Id` 등을 그대로 신뢰한다. 게이트웨이를 우회해 직접 접근할 수 있으면 신원 위조가 가능하다. 로컬 PoC에서는 허용한다. K8s(M5)에서는 NetworkPolicy로 backend ingress를 ws-gateway 파드로만 제한해야 한다.

## D9. close 프레임 유실 (1006) 원인과 수정 (M2)

- **증상:** 전체 스위트를 반복 실행하면 약 1/14 확률로 클라이언트가 의도한 close code(당시 1001/4401/4408, 현재 1012/3000/3008) 대신 **1006**(close 프레임 없이 끊김)을 받았다. 이때 서버에는 `Failed to release a message: CloseWebSocketFrame(...freed)` 경고가 남았다.
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
  - 로컬과 compose는 `SimpleDiscoveryClient` 정적 목록을 쓴다. K8s 파드 IP 디스커버리는 M5에서 붙인다.
- **`WsAuthFilter`:** GlobalFilter이고 order는 HIGHEST_PRECEDENCE다.
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

- **규칙:** 서버가 보내는 코드는 §7.4.1에서 프레임에 실을 수 있는 코드(1000–1003, 1007–1011) 또는 §7.4.2 애플리케이션 범위(3000–4999)만 쓴다.
  - 모든 코드는 `CloseCodes` 한 곳에 정의한다.
  - `CloseCodesRfc6455Test`가 이 규칙을 검증한다.
- **이 기준이 필요한 이유:** SCG가 relay할 때 그대로 전달하는 집합과 정확히 같기 때문이다(D14). 이 범위를 벗어나면 relay 클라이언트는 1002 Protocol error를 받는다.
- **변경 이력**
  1. handoff 스펙대로 4401, 4408, 1001
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
- **대상 전환:** 기본 `application.yml`은 `lb:ws://ws-backend`(discovery). `static` 프로필(`application-static.yml`)은 `${WS_BACKEND_URI:ws://localhost:8081}`로 보낸다.
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
  - 스펙(handoff.md)은 ws-backend 하나를 전제로 한다. 이 결정과 D18이 그 차이다.
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

- **서비스:** ws-chat(`modules/ws-chat`, relay 8084 / direct 8085)을 추가했다. 엔드포인트는 `/ws/chat` 하나이고 방 단위 채팅이다(join / say / leave).
- **게이트웨이 라우트:**
  - `ws-backend`: `Path=/ws/echo,/ws/push` → `lb:ws://ws-backend`
  - `ws-chat`: `Path=/ws/chat/**` → `lb:ws://ws-chat` (`/ws/chat` 자신도 매칭)
  - ws-backend 경로를 `/ws/**`가 아니라 **명시적인 목록**으로 적었다. 경로가 겹치지 않으면 라우트 `order`에 결과가 좌우되지 않고, 새 서비스를 추가할 때 기존 라우트를 건드리지 않아도 된다.
  - 어느 라우트에도 맞지 않는 `/ws/...`는 게이트웨이가 404로 응답한다. GlobalFilter(`WsAuthFilter`)는 매칭된 라우트에서만 실행되므로 인증 전에 404가 나고, upstream 연결도 없다.
  - LoadBalancer eager-load 대상과 simple discovery 목록에 `ws-chat`을 추가했다.
- **경로 prefix를 떼지 않는다(StripPrefix 없음):** 클라이언트가 보는 경로와 서비스의 `@WsEndpoint` 경로가 같다. 기존 `/ws/echo` 클라이언트가 그대로 동작한다.
- **한계: 방은 인스턴스 로컬이다.** `ChatRooms`는 프로세스 메모리에 있어서 ws-chat을 여러 대 띄우면 같은 방 이름이라도 인스턴스마다 멤버가 따로다. 인스턴스 간 fan-out(Redis pub/sub 등)은 후속 과제로 둔다.
  - broadcast는 `trySend`로 보낸다. 느린 멤버 하나 때문에 다른 멤버가 막히지 않는 대신, 큐가 가득 찬 멤버의 메시지는 버려진다.
- **검증:** `ChatTest`(방 전달, 방 분리, leave/disconnect, 오류, 401, `/ws/echo` 404), `GatewayE2ETest`(경로별로 해당 서비스만 핸드셰이크를 받음, 미라우팅 404), `RouteDefinitionTest`(discovery·static 각 2개 라우트)
