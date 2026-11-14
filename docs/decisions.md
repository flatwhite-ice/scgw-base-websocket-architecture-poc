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

- 티켓의 `exp`(30초)는 **교환 가능 기간**일 뿐이다. 이 값을 세션 만료로 쓰면 티켓으로 연결한 모든 세션이 30초 뒤에 4401로 끊긴다.
- **결정:** auth-api가 티켓을 발급할 때 원래 access token의 만료 시각을 `sexp` 클레임에 담는다. `JwtVerifier`는 티켓 검증 시 `AuthPrincipal.expiresAt = sexp`로 설정한다. 그래서 ticket으로 연결하든 Bearer로 연결하든 세션은 access token 만료 시각에 4401로 종료된다.
- JWT `exp`는 초 단위이므로 `IssuedToken.expiresAt`도 초 단위로 절삭해 claim 값과 일치시켰다.

## D8. 세션 처리 구조 (M2)

- **핸드셰이크 인증:** `CoWebFilter`(`WsHandshakeAuthFilter`)가 업그레이드 전에 401 또는 503을 응답하고 chain을 호출하지 않는다. 인증 결과는 `exchange.mutate().principal(...)`로 넘기고, 핸들러는 `HandshakeInfo.principal`로 받는다.
  - `HandshakeWebSocketService`의 attribute predicate는 *WebSession* 속성만 복사한다. 이 경로를 쓰면 연결마다 세션 스토어 항목이 생기므로 쓰지 않는다.
- **relay 모드 헤더:** `X-User-Id`, `X-Scopes`, `X-Auth-Exp`(epoch seconds). `X-Auth-Exp`는 스펙에 없지만 relay 모드에서도 4401을 처리하려면 필요해서 추가했다.
- **세션 코루틴 dispatcher:** `Schedulers.parallel().asCoroutineDispatcher()`
  - `delay()`가 kotlinx의 단일 DefaultExecutor 스레드 대신 Reactor 타이머(코어 수만큼)를 쓴다. 2만 세션 × 1초 push에서 타이머 스레드 하나가 병목이 되는 것을 피하기 위해서다.
  - parallel 스레드는 non-blocking 스레드라 BlockHound 검사 대상이다.
- **outbound:** 세션마다 bounded `Channel`(`ws.outbound-buffer-size`, 기본 256)을 둔다.
  - push는 `trySend`로 보내고, 큐가 가득 차면 드롭하고 `droppedPushMessages`에 집계한다(E2의 "드롭 여부").
  - echo 응답은 `send`로 보내 수신 루프에 backpressure를 건다.
- **inbound:** `receive().map(...)`에서 텍스트를 동기적으로 추출한 뒤 Flow로 넘긴다. Reactor Netty는 `onNext` 직후 프레임 버퍼를 해제하기 때문이다.
- **heartbeat:** `ws.heartbeat.interval`마다 ping을 보내고, pong 없이 연속 `max-missed`(2)회가 되면 **4408**(heartbeat timeout, PoC에서 정한 코드)로 종료한다.
  - 서버 업그레이드는 `handlePing(false)`로 설정해 PONG 프레임이 세션까지 전달되게 했다.
- **drain:** `DrainCoordinator`(SmartLifecycle, phase=`DEFAULT_PHASE`)를 둔다.
  - 웹서버 graceful shutdown(phase `DEFAULT_PHASE-1024`)보다 **먼저** stop된다.
  - 신규 핸드셰이크는 503으로 거절하고, 기존 세션은 window 안의 랜덤 지터 시점에 1001로 닫는다.
  - `spring.lifecycle.timeout-per-shutdown-phase`(35초)는 drain window(20초)보다 크게 잡았다.
- **실행 프로필:** `relay`(gateway 모드, 8081), `direct`(self 모드, 8083, JWKS=auth-api).

## D9. close 프레임 유실 (1006) 원인과 수정 (M2)

- **증상:** 전체 스위트를 반복 실행하면 약 1/14 확률로 클라이언트가 1001/4401/4408 대신 **1006**(close 프레임 없이 끊김)을 받았다. 이때 서버에는 `Failed to release a message: CloseWebSocketFrame(...freed)` 경고가 남았다.
- **원인:** Reactor Netty의 `sendClose()`는 inbound를 즉시 종료하고, 구독이 취소되면(`doOnCancel`) close 프레임을 해제한다.
  1. 세션 코루틴에서 `session.close()`를 await한다.
  2. inbound가 끝나면서 수신 루프가 종료된다.
  3. `finally { cancelChildren() }`가 close를 await 중인 코루틴까지 취소한다.
  4. 쓰기 중인 프레임이 해제된다.
- **수정:** close 쓰기를 `withContext(NonCancellable)`로 감쌌다. 수정 후 전체 스위트를 16회 반복 실행했고 실패 0건, 경고 0건이었다.
  - 처음에는 "outbound를 먼저 비우고 close"로 고쳤는데 오히려 11/16 실패가 났다. 서스펜션 지점이 늘어나 취소 창이 넓어졌기 때문이며, 이 결과로 원인을 확정했다.
- **실험 영향:** 이 버그가 남아 있었다면 E4(재연결 폭주)에서 drain 시 1001 대신 1006이 섞여, 클라이언트 재연결 정책 비교가 왜곡됐을 것이다.

## D10. ws-gateway (M3)

- **라우트:** `RouteLocatorBuilder` 빈으로 정의한다. `ws.gateway.backend.mode`로 대상을 전환한다.
  - `discovery`(기본): `lb:ws://ws-backend`
  - `static`: `ws.gateway.backend.uri`. ClusterIP VIP로 보내는 E3용 모드다.
  - 로컬과 compose는 `SimpleDiscoveryClient` 정적 목록을 쓴다. K8s 파드 IP 디스커버리는 M5에서 붙인다.
- **`WsAuthFilter`:** GlobalFilter이고 order는 HIGHEST_PRECEDENCE다.
  - 모든 요청에서 `X-User-Id`, `X-Scopes`, `X-Auth-Exp`를 제거한다.
  - Upgrade 요청에서만 인증한다.
  - 성공하면 identity 헤더를 주입하고, `ticket` 쿼리와 `Authorization` 헤더를 제거한 뒤 forward한다.
  - 실패하면 401 `setComplete()`로 끝내고 chain을 호출하지 않는다. 따라서 upstream 연결이 생기지 않으며, E2E 테스트에서 backend 수신 기록이 0건인 것으로 확인했다.
- **LoadBalancer eager-load:** `spring.cloud.loadbalancer.eager-load.clients=ws-backend`
  - Spring Cloud LoadBalancer는 서비스별 child ApplicationContext를 **첫 요청 때 event loop에서** 만들고, 이때 class 파일을 파싱한다.
  - BlockHound가 이 호출을 잡았다. 첫 핸드셰이크 지연의 원인이기도 해서, 기동 시점에 미리 만들도록 바꿨다.
- **Drain:** `GatewayDrainState`가 shutdown 시 신규 업그레이드를 503으로 거절한다.
  - 기존 relay 세션은 SCG `WebsocketRoutingFilter`가 소유하므로, 세션별 1001 + 지터 종료는 구현하지 않았다. 웹서버 종료 시 연결이 닫힌다.
  - E4에서 게이트웨이 종료 시 클라이언트가 받는 close 코드를 관찰하고, 필요하면 후속 과제로 처리한다.
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
