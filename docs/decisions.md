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
