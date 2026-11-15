# Websocket Service Architecture (w. Spring Cloud Gateway)

Spring Cloud Gateway를 이용하여 WebSocket 서비스를 제공하기 위한 아키텍처 초안이다. 작업 완료 후에는 
WebSocket을 **게이트웨이로 중계(relay)할 때**와 **서비스에 직접 연결(direct)할 때**의 비용 차이를 수치로 비교하는 PoC를 수행한다.
대상 환경은 1 vCPU / 4 GB 파드다. Kotlin, Spring Boot 4, WebFlux(Reactor Netty), Spring Cloud Gateway로 만들었다.

```
A. Relay   클라이언트 ──▶ ws-gateway (SCG, JWT 검증) ──▶ ws-backend / ws-chat
B. Direct  클라이언트 ─────────────────────────────────▶ ws-backend / ws-chat (핸드셰이크에서 JWT 직접 검증)

두 구조 모두 토큰과 1회용 WS 티켓은 auth-api가 발급한다.
```

검증하려는 가설(요약, 자세한 내용과 실험 방법은 [`docs/hypotheses.md`](docs/hypotheses.md)):

| # | 가설 |
|---|---|
| H1 | relay는 세션당 연결이 2개라 메모리·FD·conntrack이 약 2배 든다 |
| H2 | 1 vCPU / 4 GB 파드의 병목은 메모리가 아니라 CPU다 |
| H3 | 게이트웨이 → 백엔드를 단일 VIP로 연결하면 ephemeral port가 고갈되고, 파드 IP로 직접 라우팅하면 해소된다 |
| H4 | 장애나 배포 때 재연결 폭주의 병목은 핸드셰이크 CPU와 TIME_WAIT다 |
| H5 | 인증을 핸드셰이크로 분리해 직접 연결하면 중계 홉의 비용이 통째로 사라진다 |

## 현재 상태

| 마일스톤 | 내용 | 상태 |
|---|---|---|
| M0–M3 | 골격, 인증, ws-backend(direct), ws-gateway(relay) | 완료 |
| M4 | 공통 메트릭(`ws.*`, `/actuator/prometheus`) | 완료 |
| M5 | ws-console 모니터링 탭 | 완료 |
| M6 | Grafana 대시보드, docker compose, k6 스모크 | 예정 |
| M7 | K8s 배포(kustomize relay/direct) | 예정 |
| M8 | 실험 E1–E5와 결과 정리 | 예정 |

진행 기록은 [`docs/progress.md`](docs/progress.md)에 있다. 마일스톤 번호는 스펙과 다르며, 대응 관계는 그 문서 머리말에 있다.

## 모듈

| 모듈 | 종류 | 설명 |
|---|---|---|
| `modules/auth-token` | 라이브러리 | JWT 발급·검증(RS256, JWKS), 1회용 티켓 재사용 방지, 게이트웨이 신원 헤더, 티켓 마스킹. Spring 비의존 |
| `modules/auth-api` | 앱 :8070 | `/auth/token`, `/auth/ws-ticket`, `/.well-known/jwks.json` |
| `modules/ws-metrics` | 라이브러리 | 서비스와 게이트웨이가 함께 쓰는 메트릭 정의(`WsMeters`, `EventLoopLagProbe`) |
| `modules/ws-backend-core` | 라이브러리 | WebSocket 서비스 공통 런타임: `@WsEndpoint`, 핸드셰이크 인증(업그레이드 전 401), 세션 수명(heartbeat, 토큰 만료, drain), 메트릭 |
| `modules/ws-backend-service-default` | 앱 :8090 / :8092 | 서비스 `ws-backend`: `/ws/echo`, `/ws/push`, 익명 `/ws/public/push` |
| `modules/ws-backend-service-chat` | 앱 :8091 / :8093 | 서비스 `ws-chat`: `/ws/chat`(방 단위 채팅) |
| `modules/ws-gateway` | 앱 :8080 | Spring Cloud Gateway. 경로로 서비스를 고르고 라우트 단위로 인증(`WsAuth`)한다 |
| `modules/ws-console` | 앱 :8060 | 브라우저 테스트 콘솔. 시나리오별 화면·프로토콜 타임라인, 거절 케이스 probe, 메트릭 모니터링 탭 |
| `build-logic` | Gradle | convention 플러그인(toolchain, ktlint, BlockHound) |

서비스 앱의 포트는 relay 모드(게이트웨이 헤더 신뢰) / `direct` 프로필(JWT 직접 검증) 순서다.

## 시작하기

필요한 것: JDK 25. 나머지는 Gradle wrapper가 받는다.

```bash
./gradlew build      # 컴파일 + ktlint + 전체 테스트(BlockHound 포함)
```

로컬 실행(터미널마다 하나씩):

```bash
./gradlew :modules:auth-api:bootRun                                   # :8070
./gradlew :modules:ws-backend-service-default:bootRun                 # :8090 (relay)
./gradlew :modules:ws-backend-service-chat:bootRun                    # :8091 (relay)
./gradlew :modules:ws-gateway:bootRun                                 # :8080
./gradlew :modules:ws-console:bootRun                                 # :8060 → http://localhost:8060

# direct 구조도 함께 보려면
./gradlew :modules:ws-backend-service-default:bootRun --args='--spring.profiles.active=direct'   # :8092
./gradlew :modules:ws-backend-service-chat:bootRun --args='--spring.profiles.active=direct'      # :8093
```

확인:

```bash
# JDK만으로 하는 E2E smoke (relay + direct)
java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080 ws://localhost:8092 ws://localhost:8093

# 메트릭
curl -s localhost:8080/actuator/prometheus | grep '^ws_'
```

websocat/curl로 직접 확인하는 방법은 [`docs/ws-cli-testing.md`](docs/ws-cli-testing.md)에 있다.

## 주요 동작

- **인증**: 브라우저는 WebSocket 요청에 헤더를 넣을 수 없어서 수명 30초의 1회용 티켓을 `?ticket=`으로 보낸다. `Authorization: Bearer`도 받는다. 인증 실패는 업그레이드 전에 HTTP 401로 거절하고, relay에서는 upstream 연결도 열지 않는다.
- **스푸핑 방지**: 게이트웨이는 클라이언트가 보낸 `X-User-Id` 등 신원 헤더를 항상 지우고, 인증된 값만 넣어 보낸다.
- **세션 종료 코드**(RFC 6455 §7.4): 토큰 만료 3000, heartbeat 타임아웃 3008, 종료·배포(drain) 1001, 서버 프로토콜 오류 1011. 이 범위의 코드만 SCG를 거쳐도 그대로 전달된다.
- **Graceful drain**: 종료 신호를 받으면 새 핸드셰이크는 503으로 거절하고, 기존 세션은 20초 안에 지터를 두고 1001로 닫는다.
- **논블로킹**: event loop에서 블로킹 호출을 금지하며, 모든 테스트에서 BlockHound로 강제한다.
- **메트릭**: `ws.sessions.active`, `ws.handshake`, `ws.messages`, `ws.close`, `ws.eventloop.lag`, `ws.relay.upstream.connect` 등. 태그는 개수가 한정된 값(엔드포인트, 라우트 id, 결과, close 코드)만 쓴다.

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/hypotheses.md`](docs/hypotheses.md) | 검증할 가설(H1–H5)과 실험 계획(E1–E5), 착수 당시 명세 |
| [`docs/progress.md`](docs/progress.md) | 마일스톤별 진행과 검증 기록, 후속 과제 |
| [`docs/decisions.md`](docs/decisions.md) | 설계 결정과 스펙에서 벗어난 점(D1–D30) |
| [`docs/ws-cli-testing.md`](docs/ws-cli-testing.md) | websocat/curl로 수동 확인하는 방법 |
| [`docs/metrics-proposals.md`](docs/metrics-proposals.md) | 추가하면 좋을 메트릭 제언 |
| [`CLAUDE.md`](CLAUDE.md) | 이 저장소에서 작업할 때의 규칙과 아키텍처 요약 |

## 기술 스택

JDK 25, Kotlin 2.3, Spring Boot 4.1, Spring Cloud 2025.1(Spring Cloud Gateway 5.0), WebFlux + Reactor Netty + Kotlin 코루틴, Jackson 3, nimbus-jose-jwt, Micrometer + Prometheus, JUnit 5 + BlockHound, Gradle Kotlin DSL(version catalog). Spring MVC는 쓰지 않는다.
