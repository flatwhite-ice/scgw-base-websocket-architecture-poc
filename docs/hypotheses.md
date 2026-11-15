# 가설과 검증 계획 — WebSocket 게이트웨이 PoC (Spring Boot + Kotlin)

> 프로젝트가 끝난 뒤 검증할 가설(§1 H1~H5)과 그 검증 방법(§8 실험 E1~E5)을 관리하는 문서다.
> 처음에는 착수용 인계 문서(handoff)였다. 그래서 §2~§7, §9~§11에는 착수 당시의 명세와 마일스톤이 그대로 남아 있다.
> 구현이 이 명세와 다른 점은 `docs/decisions.md`에, 마일스톤 진행은 `docs/progress.md`에 기록한다.

---

## 1. 배경과 목적

실서비스에 WebSocket을 도입할 때 **Spring Cloud Gateway(SCG)로 중계(relay)하는 구조**와 **인증만 핸드셰이크에서 처리하고 서비스에 직접 연결하는 구조**의 비용 차이를 수치로 확인한다.

검증할 가설:

| # | 가설 | 확인 방법 |
|---|---|---|
| H1 | SCG 중계 시 세션당 연결이 2개(클라이언트↔GW, GW↔백엔드)가 되어 메모리·FD·conntrack이 약 2배 든다 | E1 |
| H2 | 1 vCPU / 4GB 파드에서 병목은 메모리가 아니라 CPU(TLS 핸드셰이크, 프레임 중계, GC)다 | E1, E2, E5 |
| H3 | GW→백엔드를 단일 VIP(ClusterIP)로 연결하면 목적지당 ephemeral port 예산(~28K)에서 고갈된다. 파드 IP 직접 라우팅이면 해소된다 | E3 |
| H4 | GW 파드 장애나 배포 시 재연결 폭주의 병목은 핸드셰이크 CPU와 TIME_WAIT다 | E4 |
| H5 | 인증을 핸드셰이크 시점으로 분리(직접 연결)하면 중계 홉의 비용이 통째로 사라진다 | E1~E4를 A/B로 비교 |

**비교 대상 아키텍처**

- **A. Relay:** Client → `ws-gateway`(SCG, JWT 검증) → `ws-backend`
- **B. Direct:** Client → (Ingress/L4) → `ws-backend`(핸드셰이크 시 JWT 검증)
- 두 구조 모두 토큰은 `auth-api`가 발급한다. 수명이 짧은 WS 티켓도 같은 방식으로 발급한다.

---

## 2. 범위

### 포함
- Gradle 멀티모듈 Kotlin 프로젝트
- `auth-api`, `ws-backend`, `ws-gateway`, `common-auth` 모듈
- Micrometer + Prometheus 메트릭, Grafana 대시보드 JSON 1개
- 로컬 실행용 docker compose
- K8s 매니페스트(kustomize): `relay`, `direct` 오버레이, 파드 사양 1 vCPU / 4GB
- k6 부하 테스트 스크립트, 실험 E1~E5 실행 절차와 결과 기록

### 제외 (2단계 후보)
- Istio Ingress Gateway + `ext_authz` 구성
- STOMP / 메시지 브로커 연동, 멀티 인스턴스 간 브로드캐스트(Redis/Kafka)
- 실제 IdP 연동 (PoC는 자체 서명 JWT 사용)

---

## 3. 기술 스택

> 버전은 **착수 시 start.spring.io와 Spring Cloud 호환 매트릭스로 확인해 확정**하고 `gradle/libs.versions.toml`에 고정한다. 확정한 버전은 `docs/decisions.md`에 기록한다.

| 항목 | 기준 |
|---|---|
| JDK | 21 LTS 이상 (25 LTS를 쓸 수 있으면 25) |
| Kotlin | 2.x 최신 안정판, `kotlin("plugin.spring")` 사용 |
| Spring Boot | 최신 안정판 (Boot 4.x를 쓸 수 있으면 4.x) |
| Spring Cloud | 위 Boot 버전과 호환되는 릴리스 트레인 |
| 웹 스택 | **WebFlux + Reactor Netty** (MVC 사용 금지) |
| 비동기 스타일 | Kotlin Coroutines (`kotlinx-coroutines-reactor`) |
| 게이트웨이 | Spring Cloud Gateway (WebFlux 서버 버전). 아티팩트명과 프로퍼티 prefix가 버전마다 다르므로 반드시 확인 |
| JWT | `com.nimbusds:nimbus-jose-jwt` (RS256, JWKS) |
| 메트릭 | Micrometer, `micrometer-registry-prometheus`, Reactor Netty 메트릭 활성화 |
| 테스트 | JUnit 5, Kotest assertions(선택), `WebTestClient`, `ReactorNettyWebSocketClient`, **BlockHound** |
| 부하 테스트 | k6 (`k6/websockets` 모듈) |
| 빌드 | Gradle Kotlin DSL, version catalog |

---

## 4. 저장소 구조

```
ws-poc/
├── settings.gradle.kts
├── build.gradle.kts                 # 공통 플러그인/설정 (convention은 buildSrc 또는 build-logic)
├── gradle/libs.versions.toml
├── modules/
│   ├── common-auth/                 # JWT 발급·검증 라이브러리 (Spring 의존 최소화)
│   ├── auth-api/                    # 토큰/티켓 발급 HTTP API, JWKS 노출
│   ├── ws-backend/                  # WebSocket 서비스 (직접 연결 시 자체 인증)
│   └── ws-gateway/                  # SCG 기반 WS 게이트웨이 (relay 구조)
├── loadtest/k6/
│   ├── lib/                         # 토큰 발급, 공통 옵션
│   ├── e1-idle-connections.js
│   ├── e2-message-throughput.js
│   ├── e3-port-exhaustion.js
│   └── e4-reconnect-storm.js
├── deploy/
│   ├── compose/docker-compose.yml   # auth-api, ws-backend x2, ws-gateway, prometheus, grafana
│   ├── grafana/ws-poc-dashboard.json
│   └── k8s/
│       ├── base/
│       └── overlays/
│           ├── relay/               # Client → gateway → backend
│           └── direct/              # Client → backend
└── docs/
    ├── progress.md                  # 마일스톤별 진행 기록
    ├── decisions.md                 # 버전, 설계 결정과 이유
    └── results.md                   # 실험 결과표와 결론
```

---

## 5. 모듈 상세 명세

### 5.1 `common-auth`
- `JwtIssuer`: RS256 서명, claim은 `sub`(userId), `scope`, `exp`, `jti`, `typ`(`access` | `ws-ticket`)
- `JwtVerifier`: JWKS URL 또는 로컬 공개키로 검증. JWKS는 캐시하고 주기적으로 갱신
- 검증 결과는 `AuthPrincipal(userId, scopes, expiresAt)`
- 블로킹 I/O 금지. JWKS 갱신은 논블로킹 클라이언트로 처리

### 5.2 `auth-api` (port 8080)
| Method | Path | 설명 |
|---|---|---|
| POST | `/auth/token` | `{userId}` → access token (TTL 15분). PoC이므로 비밀번호 없음 |
| POST | `/auth/ws-ticket` | access token 필요 → WS 티켓 (TTL 30초, 1회용, `jti` 기록) |
| GET | `/.well-known/jwks.json` | 공개키 |

- 티켓 1회용 검증은 PoC에서 인메모리(Caffeine)로 처리한다. 멀티 인스턴스 한계는 `decisions.md`에 기록

### 5.3 `ws-backend` (port 8081)
- 엔드포인트: `/ws/echo`, `/ws/push`
- 인증 모드는 프로퍼티 `ws.auth.mode`로 전환
  - `gateway`: 게이트웨이가 넣어준 `X-User-Id` 헤더를 신뢰 (relay 구조)
  - `self`: 핸드셰이크에서 JWT 검증 (direct 구조). 브라우저는 WS 요청에 헤더를 넣을 수 없으므로 `?ticket=` 쿼리와 `Authorization` 헤더를 모두 지원
- 인증 실패 시 **업그레이드 전에 HTTP 401로 거절**한다. 연결 후 close가 아니다
- 메시지 프로토콜(JSON)
  ```json
  { "type": "echo", "id": "uuid", "ts": 1731570000000, "payload": "..." }
  { "type": "subscribe", "topic": "ticker", "intervalMs": 1000, "payloadBytes": 256 }
  ```
  - `echo`: 그대로 반환하되 서버 수신 시각 `srvTs`를 추가 (왕복 지연 측정용)
  - `subscribe`: 세션별로 `intervalMs` 주기로 `payloadBytes` 크기의 push 메시지 전송
- 서버 heartbeat: WS ping을 `ws.heartbeat.interval`(기본 30초)마다 보내고, pong 미수신 2회면 종료
- 토큰 만료 처리: `exp` 도달 시 close code `4401`로 종료. 클라이언트 재인증 흐름을 검증하기 위함
- Graceful shutdown: 종료 신호를 받으면 신규 핸드셰이크를 거절하고, 기존 세션은 `ws.drain.window`(기본 20초) 안에서 지터를 섞어 close `1001`을 전송

### 5.4 `ws-gateway` (port 8082)
- SCG 라우트
  - `/ws/**` → `lb:ws://ws-backend`
  - 백엔드 탐색: compose에서는 `SimpleDiscoveryClient` 정적 목록, K8s에서는 **파드 IP로 직접 라우팅**(Spring Cloud Kubernetes discovery 또는 headless Service). 실험 E3을 위해 ClusterIP 경유 모드도 프로퍼티로 전환할 수 있어야 한다
- `GlobalFilter` (`WsAuthFilter`)
  - Upgrade 요청에서만 동작한다. `?ticket=` 또는 `Authorization` 검증
  - 성공 시 `X-User-Id`, `X-Scopes` 헤더를 추가하고, 클라이언트가 보낸 같은 이름의 헤더는 제거(스푸핑 방지)
  - 실패 시 401로 응답하고 업스트림 연결을 만들지 않는다
- Reactor Netty 튜닝 항목을 프로퍼티로 노출: 업스트림 커넥션 풀, 연결/응답 타임아웃, WS 최대 프레임 크기
- Graceful shutdown은 5.3과 같은 방식

### 5.5 공통 메트릭 (모든 WS 모듈)
| 메트릭 | 타입 | 용도 |
|---|---|---|
| `ws.sessions.active` | Gauge | 활성 세션 수. HPA 기준 후보 |
| `ws.handshake` | Timer + 결과 태그(`ok`/`unauthorized`/`error`) | 초당 핸드셰이크 수, 지연 |
| `ws.messages` | Counter (방향 태그 `in`/`out`) | 처리량 |
| `ws.message.relay.latency` | Timer (gateway만) | 중계 지연 |
| `ws.close` | Counter (close code 태그) | 종료 원인 분석 |
| Reactor Netty 이벤트 루프 / 커넥션 풀 / ByteBuf 할당자 | 내장 | 이벤트 루프 지연, 다이렉트 메모리 |
| `process.files.open` | 내장 | FD |
| JVM heap, GC pause | 내장 | GC 영향 |

OS 수준 지표는 실험 스크립트가 파드에서 주기적으로 수집해 CSV로 남긴다.
- `ss -s`, `cat /proc/net/sockstat` (TIME_WAIT, 소켓 메모리)
- `cat /proc/sys/net/netfilter/nf_conntrack_count` (노드 접근 가능 시)
- 목적지별 로컬 포트 사용량: `ss -tan state established dst <backend>` 집계

---

## 6. 런타임 설정 기준 (1 vCPU / 4GB 파드)

```yaml
resources:
  requests: { cpu: "1", memory: "4Gi" }
  limits:   { memory: "4Gi" }        # CPU limit은 실험 변수. 기본은 미설정, E5에서 limit=1과 비교
```

JVM 옵션 (`JAVA_TOOL_OPTIONS`):
```
-XX:+UseG1GC                     # 1 CPU 인식 시 SerialGC로 떨어지는 것 방지
-XX:ActiveProcessorCount=2
-XX:MaxRAMPercentage=50
-XX:MaxDirectMemorySize=1g
-Dreactor.netty.ioWorkerCount=2
```

커널 파라미터 (가능한 범위에서 파드 `securityContext.sysctls`로 설정, 불가하면 `decisions.md`에 기록):
- `net.ipv4.ip_local_port_range`: 기본값과 축소값(E3 재현용, 예: `40000 41023`)을 실험별로 전환
- `net.ipv4.tcp_tw_reuse=1` (E4에서 on/off 비교)

---

## 7. 마일스톤

각 마일스톤은 **완료 조건을 모두 만족**해야 다음으로 넘어간다.

### M0. 프로젝트 골격
- Gradle 멀티모듈, version catalog, 공통 convention(ktlint 또는 detekt, JVM toolchain)
- 모든 모듈이 빈 Spring Boot 앱으로 기동
- **완료 조건:** `./gradlew build` 통과, `docs/decisions.md`에 확정 버전 기록

### M1. 인증 (`common-auth`, `auth-api`)
- 토큰, 티켓 발급과 JWKS
- **완료 조건:** 발급→검증 단위 테스트, 만료·서명 오류·티켓 재사용 거절 테스트 통과

### M2. `ws-backend` (direct 구조)
- `/ws/echo`, `/ws/push`, `self` 인증 모드, heartbeat, 토큰 만료 종료, graceful drain
- **완료 조건:**
  - `ReactorNettyWebSocketClient` 통합 테스트: 정상 연결, 미인증 시 401(업그레이드 전), echo 왕복, push 주기, 만료 시 4401
  - BlockHound를 켠 상태에서 테스트 통과

### M3. `ws-gateway` (relay 구조)
- SCG 라우트, `WsAuthFilter`, 헤더 스푸핑 방지, `gateway` 인증 모드 연동
- **완료 조건:** 게이트웨이 경유 E2E 테스트(정상, 401, 스푸핑 헤더 제거 확인), BlockHound 통과

### M4. 관측성과 로컬 환경
- 5.5의 메트릭, Grafana 대시보드, docker compose
- **완료 조건:** `docker compose up` 후 k6 스모크 테스트(100 연결, 1분) 실행, 대시보드에서 세션·핸드셰이크·메시지가 보임

### M5. K8s 배포
- kustomize `base` + `relay`/`direct` 오버레이, readiness/liveness, preStop + `terminationGracePeriodSeconds`(drain window + 10초)
- 로컬은 kind 또는 k3d. 부하 생성기는 클러스터 안에서 별도 노드나 파드로 실행
- **완료 조건:** 두 오버레이 모두 배포되고 스모크 테스트 통과

### M6. 실험 E1~E5 실행과 결과 정리 (§8)
- **완료 조건:** `docs/results.md`에 실험별 표, 그래프 캡처 경로, 가설 H1~H5 판정(지지/기각/보류)과 근거

---

## 8. 실험 계획

> 부하 생성기 자체의 포트·FD 한계에 주의한다. 클라이언트 1대당 목적지 1개 기준 ~28K 연결이 한계이므로 **k6 인스턴스를 여러 개(파드 여러 개)로 나눠 실행**한다. 부하 생성기 쪽 리소스 사용률도 함께 기록해 측정 오류를 배제한다.

| ID | 목적 | 방법 | 기록할 값 |
|---|---|---|---|
| **E1** 유휴 연결 상한 | A·B 각각 파드 1개가 버티는 최대 세션 수 | 초당 500씩 연결을 늘리고 메시지 없이 heartbeat만 유지. 메모리 80%, 핸드셰이크 실패율 1%, 이벤트 루프 지연 임계 중 먼저 닿는 지점에서 중단 | 최대 세션 수, 세션당 메모리(heap + direct + 소켓), FD, conntrack |
| **E2** 메시지 처리량 | 실제 트래픽에서의 CPU 한계 | 세션 5K / 10K / 20K × `subscribe` 1000ms, 256B | CPU, p50/p99 지연, GC pause, 드롭이나 지연 누적 여부 |
| **E3** 포트 고갈 재현 | H3 검증 | relay 구조에서 `ip_local_port_range`를 1024개로 축소. ① ClusterIP 경유 ② 파드 IP 직접(백엔드 4개). 연결 증가 시 실패 지점 비교 | 실패 시점의 세션 수, 에러 종류(`EADDRNOTAVAIL` 등), 목적지별 포트 사용량 |
| **E4** 재연결 폭주 | H4 검증 | 세션 2만 상태에서 GW(또는 backend) 파드 1개 강제 종료 / 롤링 재시작. 클라이언트는 지수 백오프+지터 vs 즉시 재연결 비교. `tcp_tw_reuse` on/off | 전원 재연결 완료 시간, 초당 핸드셰이크 피크, CPU, TIME_WAIT 수 |
| **E5** CPU 조건 비교 | TLS·스로틀링 영향 | E2 기준으로 ① TLS를 파드에서 종단 vs Ingress에서 종단 ② CPU limit 없음 vs limit=1 | CPU, 지연 p99, 스로틀링 지표(`container_cpu_cfs_throttled_*`) |

결과 요약표 형식 (`docs/results.md`):

```
| 구조 | 파드 사양 | 최대 세션 | 운영 권장 세션 (×0.6, N−1 반영) | 병목 리소스 | 비고 |
```

---

## 9. 코딩 규칙 (Claude Code 작업 지침)

- **논블로킹 원칙:** 이벤트 루프에서 블로킹 호출 금지. 테스트에서 BlockHound로 강제한다. 불가피한 블로킹은 `Dispatchers.IO`나 `boundedElastic`으로 격리하고 주석으로 이유를 남긴다
- 핸들러는 `suspend` 함수와 `Flow` 기반으로 작성하고, Reactor 타입과의 변환은 경계에서만 한다
- 설정값은 `@ConfigurationProperties`(data class)로 묶고, 하드코딩하지 않는다
- 로그는 구조화(JSON)하고 세션 ID, userId를 MDC 대신 Reactor Context로 전달한다. 연결이나 메시지 단위 INFO 로그는 남기지 않는다 (부하 실험 왜곡 방지)
- 작은 단위로 커밋하고, 커밋 전 `./gradlew build`를 통과시킨다
- 마일스톤 범위를 벗어나는 개선은 하지 말고 `docs/progress.md`의 "후속 과제"에 적는다
- 의사결정이 필요한데 이 문서에 기준이 없으면, 합리적인 선택을 하고 `docs/decisions.md`에 이유와 대안을 기록한 뒤 진행한다

---

## 10. 알려진 함정 체크리스트

- [ ] 브라우저 WebSocket은 커스텀 헤더를 못 보낸다 → `?ticket=` 지원 필수. 티켓이 액세스 로그에 남지 않도록 쿼리 마스킹
- [ ] SCG에서 인증 실패를 업그레이드 **전에** 거절하는지 확인 (업스트림 연결이 생기지 않아야 함)
- [ ] 클라이언트가 보낸 `X-User-Id` 헤더 제거 (스푸핑)
- [ ] K8s에서 JVM이 프로세서를 1개로 인식 → SerialGC 선택, Netty 이벤트 루프 1~2개
- [ ] CPU limit 사용 시 CFS 스로틀링으로 꼬리 지연 증가
- [ ] Ingress / LB의 idle timeout(보통 60초)보다 heartbeat 주기가 짧아야 함
- [ ] 스케일아웃해도 기존 연결은 재분배되지 않음 → LB는 least-connections
- [ ] preStop drain 시간 < `terminationGracePeriodSeconds`
- [ ] 부하 생성기 자체의 포트·FD 한계 (k6 분산 실행)

---

## 11. 첫 작업 지시 (Claude Code에게)

1. 이 문서를 읽고 `docs/progress.md`를 만들어 마일스톤 체크리스트를 옮긴다
2. §3의 버전을 확인·확정하고 `docs/decisions.md`에 기록한다
3. M0부터 시작한다. 마일스톤마다 완료 조건을 검증한 결과(테스트 출력 요약)를 `progress.md`에 남긴다
