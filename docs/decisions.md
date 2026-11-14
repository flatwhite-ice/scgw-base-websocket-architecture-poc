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
