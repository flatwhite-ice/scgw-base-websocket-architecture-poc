# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

This repo is a PoC that compares two ways of serving WebSockets on 1 vCPU / 4 GB pods:
- **A. Relay:** Client → `ws-gateway` (Spring Cloud Gateway, JWT check) → WebSocket service, chosen by path
- **B. Direct:** Client → WebSocket service, which verifies the JWT during the handshake

There are two WebSocket services built on the shared `ws-backend-core`: `ws-backend` (echo/push, `/ws/echo` and `/ws/push`) and `ws-chat` (room chat, `/ws/chat`) (D17, D18).

**The spec is `docs/handoff/handoff.md` (in Korean).** Track milestones in `docs/progress.md` and record decisions, and any deviation from the spec, in `docs/decisions.md` (D1, D2, …). Read both before making changes. M0–M3 are done on branch `poc/m0-m3`. M4 and later need docker, k6, and kind or k3d, and none of those are installed locally yet.

Stack: JDK 25 (Temurin, installed at `~/Library/Java/JavaVirtualMachines/temurin-25.0.5`), Kotlin 2.3, Spring Boot 4.1, Spring Cloud 2025.1 (SCG 5.0, prefix `spring.cloud.gateway.server.webflux.*`), WebFlux + Reactor Netty + coroutines, and Jackson 3 (`tools.jackson.*`). **MVC is forbidden.**

## Commands

```bash
./gradlew build                                    # compile + ktlintCheck + all tests (must pass before commit)
./gradlew ktlintFormat                             # auto-fix style (ktlint_official, max line 140)
./gradlew :modules:ws-backend:test                 # one module
./gradlew :modules:ws-backend:test --tests '*SelfModeTest'                       # one class
./gradlew :modules:ws-backend:test --tests '*SelfModeTest.echo round trip*'      # one test (backtick names)

# Run locally (relay + direct side by side)
./gradlew :modules:auth-api:bootRun                                              # :8070
./gradlew :modules:ws-backend:bootRun                                            # :8090, default = relay (trusts gateway headers)
./gradlew :modules:ws-backend:bootRun --args='--spring.profiles.active=direct'   # :8092, verifies JWT itself
./gradlew :modules:ws-chat:bootRun                                               # :8091, default = relay
./gradlew :modules:ws-chat:bootRun --args='--spring.profiles.active=direct'      # :8093, verifies JWT itself
./gradlew :modules:ws-gateway:bootRun                                            # :8080, /ws/echo,/ws/push -> :8090, /ws/chat/** -> :8091
java scripts/WsSmoke.java http://localhost:8070 ws://localhost:8080 ws://localhost:8092 ws://localhost:8093   # JDK-only E2E smoke
```

The smoke script checks only the services each address serves; it skips any whose handshake returns 404. For manual websocat/curl commands (tokens, tickets, echo/push, chat, routing, 401 and spoofing checks), see `docs/ws-cli-testing.md`.

Both services default to relay (gateway auth mode): ws-backend on :8090 and ws-chat on :8091, which are the gateway's discovery targets. In that mode a service trusts the injected headers, so it must only be reachable through the gateway. The `direct` profile switches to self mode with JWKS, on :8092 for ws-backend and :8093 for ws-chat. The JWKS bean only exists in self mode with a non-blank URI.

## Architecture

- **build-logic/** holds the convention plugins.
  - `wspoc.kotlin-conventions` sets the toolchain, ktlint, JUnit, and BlockHound JVM flags.
  - `wspoc.spring-app-conventions` adds Boot, WebFlux, and coroutines.
  - `wspoc.spring-library-conventions` is for Spring libraries with no bootJar (`ws-backend-core`).
- Versions live in `gradle/libs.versions.toml`. Boot and Cloud BOMs come in as Gradle `platform()` deps; the dependency-management plugin isn't used.
- **common-auth** (Spring-free):
  - `JwtIssuer`: `suspend`, runs on `Dispatchers.IO` because SecureRandom reads `/dev/urandom`.
  - `JwtVerifier`: event-loop safe.
  - `StaticKeySource` / `JwksKeySource`: non-blocking `HttpClient.sendAsync`, refetches on an unknown `kid`.
  - `TicketReplayGuard`: Caffeine; single-use tickets, enforced by whoever consumes the ticket.
  - `GatewayHeaders`: `X-User-Id`, `X-Scopes`, `X-Auth-Exp`.
  - `TicketMasking`.
- **auth-api**: `/auth/token`, `/auth/ws-ticket` (requires a Bearer access token; the ticket carries a `sexp` claim with the access token's expiry, which bounds the WS session), and `/.well-known/jwks.json`.
- **ws-backend-core** (library, package `com.example.wspoc.wscore`, registered by AutoConfiguration, D17): the runtime shared by every WebSocket service.
  - Endpoints are `WebSocketHandler` beans annotated `@WsEndpoint("/ws/...")` (class or `@Bean` method). `auth = WsEndpoint.Auth.NONE` makes an anonymous endpoint: no handshake auth and no 3000 expiry, but heartbeat and drain still apply (D20). `WsEndpointHandlerMapping` collects them at startup and fails fast on duplicates (D16). Raw WebSocket in WebFlux has no `@RequestMapping` model, and STOMP is Servlet-only.
  - `WsHandshakeAuthFilter` rejects with 401, or 503 while draining, **before the upgrade**. It passes the principal on via `exchange.mutate().principal()`, and the handler reads `HandshakeInfo.principal`.
  - `ManagedWsHandler` runs each session as a coroutine and owns its lifecycle. A service supplies only a per-session `WsSessionProtocol` (`onOpen`/`onText`/`onClose`), usually a `JsonWsProtocol`, which gives every service the same `{"type":...}` dispatch and error replies. The lifecycle covers:
    - the bounded outbound `Channel`: `send` applies backpressure for replies, `trySend` drops when full for push and broadcast (counted in `droppedMessages`)
    - heartbeat timeout closes with 3008 (Timeout)
    - token expiry closes with 3000 (Unauthorized)
    - `DrainCoordinator` (SmartLifecycle) closes sessions with 1001 (Going Away) plus jitter
  - All close codes live in `CloseCodes` and must be RFC 6455 §7.4-valid: 1000–1003, 1007–1011, or 3000–4999 (D13), enforced by `CloseCodesRfc6455Test`. SCG rewrites anything else (e.g. 1012–1014) to 1002 on the relay path (D14).
- **ws-backend**: `EchoPushProtocol` (echo/subscribe/unsubscribe), with service settings in `PushProperties` (`ws.push`).
- **ws-chat**: `ChatProtocol` (join/say/leave). `ChatRooms` is in-memory per instance, so multiple instances split rooms; cross-instance fan-out is a follow-up (D18).
- **ws-gateway**:
  - Auth is per route (D20). `GatewayEdgeFilter` is a GlobalFilter at highest precedence and runs on every routed request: it always strips client identity headers and returns 503 to upgrades while draining. The route filter `WsAuth` (`WsAuthGatewayFilterFactory`, declared as `filters: [WsAuth]`) authenticates every request on that route, injects identity headers, and removes `ticket` and `Authorization`. On a 401 it never calls the chain, so no upstream connection is opened. Its order is pinned to Edge + 1 so stripping always runs before injection.
  - A new route that needs auth must declare `WsAuth`. If it's forgotten, the backend still rejects (fail-closed), but the client sees a handshake failure instead of a 401.
  - Routes are declared in YAML under `spring.cloud.gateway.server.webflux.routes` (no `RouteLocator` bean, D15). There is one route per service, with non-overlapping paths (D18):
    - `Path=/ws/echo,/ws/push` → `lb:ws://ws-backend`, with `WsAuth`
    - `Path=/ws/chat/**` → `lb:ws://ws-chat`, with `WsAuth`
    - `Path=/ws/public/**` → `lb:ws://ws-backend`, no auth (anonymous read-only push)
    - Any other `/ws/...` gets a 404 before auth, because GlobalFilters only run on matched routes.
  - When adding a service, also add it to `loadbalancer.eager-load.clients` and to the simple-discovery instances.
  - The `static` profile (`application-static.yml`) sends to `${WS_BACKEND_URI}` and `${WS_CHAT_URI}` for experiment E3. Profiles replace the whole route list, so redefine every route when overriding.
  - `GatewayDrainCoordinator` closes relayed sessions with 1001 plus jitter on shutdown. SCG keeps sessions private, so `RelaySessionTracking` wraps the `WebSocketService` bean (BeanPostProcessor) to register them; SCG's own filter is left untouched.

## Rules and gotchas

- **Never block an event loop.** BlockHound runs in every test JVM. Isolate unavoidable blocking on `Dispatchers.IO`/`boundedElastic` and leave a comment saying why. One-time class-init exceptions go in `modules/common-auth/src/testFixtures/.../WsPocBlockHoundIntegration.kt`, each with a reason.
- Write handlers as `suspend`/`Flow`, and convert to and from Reactor only at the boundaries. Map `WebSocketMessage` payloads synchronously inside `receive().map{}`, because Reactor Netty frees the buffer right after `onNext`.
- Wrap `session.close()` in `withContext(NonCancellable)`. If it's cancelled mid-write, the close frame is lost and the client sees 1006 (D9).
- Logging: no per-message logs. Session open/close logs go to the dedicated `ws.session` logger at INFO and are on by default (`SessionLog` in core, `RelaySessionLog` in the gateway; D21). Turn them off for load tests with `logging.level.ws.session=WARN`, because per-connection logs distort the results. Log URI paths only, never the query (`ticket=`). The access log is opt-in (`ws.access-log.enabled`) and masks `ticket=`.
- New log code uses kotlin-logging (`KotlinLogging.logger`, lazy lambdas). SLF4J and Logback come from Spring Boot and its BOM; don't declare or pin them.
- Spring tests that end in `= runBlocking { ... }` must return `Unit`. If the last expression is a value, the method isn't void and JUnit silently skips it.
- Put configuration in `@ConfigurationProperties` data classes (`WsProperties`, `GatewayProperties`, `AuthProperties`). To disable JWKS in tests, set the URI to `""`; the conditions check for a blank value.
- Tests:
  - `ws-backend-core` `testFixtures` provides `TestKeys` (stands in for auth-api) and `WsTestClient`. The service and gateway tests reuse them.
  - Gateway E2E tests boot real services in the same JVM: `RelayBackend` and `RelayChat`, both instances of `RelayApp`. Each records the handshakes it received, so tests can assert routing. They use `spring.config.name=ws-backend-e2e` so they don't load the gateway's yml.
  - Because the services are on the gateway's test classpath, the gateway's `src/test/resources/application.properties` excludes core's AutoConfiguration from the gateway context. Helper Spring configs in a scanned test package must be `@TestConfiguration`.
  - Don't write `/**` inside KDoc: it opens a nested Kotlin comment.
- Write all code comments (KDoc, `//`, and `#` in yml/toml) in Korean. Keep identifiers, config keys, close codes and RFC section names as they are.
- Stay inside the current milestone's scope. Log follow-ups under "후속 과제" (follow-ups) in `docs/progress.md`.
