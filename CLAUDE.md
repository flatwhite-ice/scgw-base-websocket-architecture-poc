# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

This repo is a PoC that compares two ways of serving WebSockets on 1 vCPU / 4 GB pods:
- **A. Relay:** Client → `ws-gateway` (Spring Cloud Gateway, JWT check) → `ws-backend`
- **B. Direct:** Client → `ws-backend`, which verifies the JWT during the handshake

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
./gradlew :modules:auth-api:bootRun                                              # :8080
./gradlew :modules:ws-backend:bootRun                                            # :8081, default = relay (trusts gateway headers)
./gradlew :modules:ws-backend:bootRun --args='--spring.profiles.active=direct'   # :8083, verifies JWT itself
./gradlew :modules:ws-gateway:bootRun                                            # :8082 -> lb:ws://ws-backend (localhost:8081)
java scripts/WsSmoke.java http://localhost:8080 ws://localhost:8082 ws://localhost:8083   # JDK-only E2E smoke
```

For manual websocat/curl commands (tokens, tickets, echo/push, 401 and spoofing checks), see `docs/ws-cli-testing.md`.

ws-backend defaults to relay (gateway auth mode) on :8081, which is the gateway's default target. In that mode it trusts the injected headers, so it must only be reachable through the gateway. The `direct` profile switches to self mode on :8083 with JWKS. The JWKS bean only exists in self mode with a non-blank URI.

## Architecture

- **build-logic/** holds the convention plugins.
  - `wspoc.kotlin-conventions` sets the toolchain, ktlint, JUnit, and BlockHound JVM flags.
  - `wspoc.spring-app-conventions` adds Boot, WebFlux, and coroutines.
- Versions live in `gradle/libs.versions.toml`. Boot and Cloud BOMs come in as Gradle `platform()` deps; the dependency-management plugin isn't used.
- **common-auth** (Spring-free):
  - `JwtIssuer`: `suspend`, runs on `Dispatchers.IO` because SecureRandom reads `/dev/urandom`.
  - `JwtVerifier`: event-loop safe.
  - `StaticKeySource` / `JwksKeySource`: non-blocking `HttpClient.sendAsync`, refetches on an unknown `kid`.
  - `TicketReplayGuard`: Caffeine; single-use tickets, enforced by whoever consumes the ticket.
  - `GatewayHeaders`: `X-User-Id`, `X-Scopes`, `X-Auth-Exp`.
  - `TicketMasking`.
- **auth-api**: `/auth/token`, `/auth/ws-ticket` (requires a Bearer access token; the ticket carries a `sexp` claim with the access token's expiry, which bounds the WS session), and `/.well-known/jwks.json`.
- **ws-backend**: endpoints are `WebSocketHandler` beans annotated `@WsEndpoint("/ws/...")` (class or `@Bean` method); `WsEndpointHandlerMapping` collects them at startup and fails fast on duplicates (D16). Raw WebSocket in WebFlux has no `@RequestMapping` model, and STOMP is Servlet-only. `WsHandshakeAuthFilter` rejects with 401, or 503 while draining, **before the upgrade**. It passes the principal on via `exchange.mutate().principal()`, and the handler reads `HandshakeInfo.principal`. `WsSessionHandler` runs each session as a coroutine:
  - the bounded outbound `Channel` drops push messages when full
  - echo/subscribe JSON protocol
  - heartbeat timeout closes with 3008 (Timeout)
  - token expiry closes with 3000 (Unauthorized)
  - `DrainCoordinator` (SmartLifecycle) closes sessions with 1001 (Going Away) plus jitter
  - all close codes live in `CloseCodes` and must be RFC 6455 §7.4-valid: 1000–1003, 1007–1011, or 3000–4999 (D13), enforced by `CloseCodesRfc6455Test`. SCG rewrites anything else (e.g. 1012–1014) to 1002 on the relay path (D14).
- **ws-gateway**:
  - `WsAuthFilter` (GlobalFilter, highest precedence) always strips client identity headers. It authenticates upgrades only, injects headers, removes `ticket` and `Authorization`, and on a 401 never calls the chain, so no upstream connection is opened.
  - Routes are declared in YAML under `spring.cloud.gateway.server.webflux.routes` (no `RouteLocator` bean, D15). Default is `lb:ws://ws-backend`; the `static` profile (`application-static.yml`) sends to `${WS_BACKEND_URI}` for experiment E3. Profiles replace the whole route list, so redefine the full route when overriding.
  - `GatewayDrainCoordinator` closes relayed sessions with 1001 plus jitter on shutdown. SCG keeps sessions private, so `RelaySessionTracking` wraps the `WebSocketService` bean (BeanPostProcessor) to register them; SCG's own filter is left untouched.

## Rules and gotchas

- **Never block an event loop.** BlockHound runs in every test JVM. Isolate unavoidable blocking on `Dispatchers.IO`/`boundedElastic` and leave a comment saying why. One-time class-init exceptions go in `modules/common-auth/src/testFixtures/.../WsPocBlockHoundIntegration.kt`, each with a reason.
- Write handlers as `suspend`/`Flow`, and convert to and from Reactor only at the boundaries. Map `WebSocketMessage` payloads synchronously inside `receive().map{}`, because Reactor Netty frees the buffer right after `onNext`.
- Wrap `session.close()` in `withContext(NonCancellable)`. If it's cancelled mid-write, the close frame is lost and the client sees 1006 (D9).
- No per-connection or per-message INFO logs, since they distort the load tests. The access log is opt-in (`ws.access-log.enabled`) and masks `ticket=`.
- Put configuration in `@ConfigurationProperties` data classes (`WsProperties`, `GatewayProperties`, `AuthProperties`). To disable JWKS in tests, set the URI to `""`; the conditions check for a blank value.
- Tests:
  - `ws-backend` `testFixtures` provides `TestKeys` (stands in for auth-api) and `WsTestClient`; the gateway tests reuse them.
  - Gateway E2E tests boot a real ws-backend in the same JVM (`RelayBackend`, `spring.config.name=ws-backend-e2e` so it doesn't load the gateway's yml). Helper Spring configs in a scanned test package must be `@TestConfiguration`.
- Write all code comments (KDoc, `//`, and `#` in yml/toml) in Korean. Keep identifiers, config keys, close codes and RFC section names as they are.
- Stay inside the current milestone's scope. Log follow-ups under "후속 과제" (follow-ups) in `docs/progress.md`.
